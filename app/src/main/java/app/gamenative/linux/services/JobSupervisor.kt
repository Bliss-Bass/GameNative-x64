package app.gamenative.linux.services

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import app.gamenative.linux.GuestProcesses
import app.gamenative.linux.LinuxProgramLauncher
import app.gamenative.linux.LinuxRootfs
import app.gamenative.linux.LinuxSessions
import app.gamenative.ui.LinuxSessionActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Runs the Linux Services addon's jobs and brings them back when they exit.
 *
 * A service job is a PRoot process with no display, its output going to [JobLogStore]. A GUI or
 * terminal job is a [LinuxSessions] session like any other Linux application's; what makes it a
 * job is only that its window is opened through the addon, which places it, and that its session
 * ending counts as an exit.
 *
 * Process-scoped like [LinuxSessions]. The definitions and the wish to have a job running are the
 * addon's and are resent whenever it binds, so nothing here needs to outlive the process.
 */
object JobSupervisor {

    /** Where each job's generated script lives, inside the guest. */
    const val SCRIPT_DIR = "/var/lib/linux-services"

    /** A run at least this long is taken as a success, and the backoff starts over. */
    private const val STABLE_RUN_MS = 60_000L
    private const val BACKOFF_MIN_MS = 1_000L
    private const val BACKOFF_MAX_MS = 60_000L

    enum class State(val key: String) {
        STOPPED("stopped"),

        /** A service is being started, or a window has been asked for and has not opened. */
        STARTING("starting"),
        RUNNING("running"),

        /** Exited and waiting out the backoff before its restart. */
        BACKOFF("backoff"),

        /** Exited, and its policy did not bring it back. */
        EXITED("exited"),

        /** Could not start, or restarted too often; stays down until started again. */
        FAILED("failed"),
    }

    interface Listener {
        fun onState(id: String, state: JSONObject)
        fun onLaunchRequest(id: String, launch: PendingIntent)
    }

    private class Runner(var job: LinuxJob) {
        /** Whether the job should be up, as opposed to whether it is. */
        var desired = false
        var state = State.STOPPED
        var process: Process? = null
        var pid = -1
        var exitCode: Int? = null
        var message: String? = null
        var startedAt = 0L
        var startedWallMs = 0L
        var restarts = 0
        val recentRestarts = ArrayDeque<Long>()
        var backoffStep = 0
        var pendingRestart: Job? = null

        /** Bumped on every start, so the exit of a run that was replaced is not taken for this one's. */
        var generation = 0

        /** Whether this GUI job's session has been seen, so its absence can be read as an exit. */
        var sessionSeen = false
    }

    private lateinit var appContext: Context
    private lateinit var logs: JobLogStore

    private val runners = linkedMapOf<String, Runner>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var listener: Listener? = null

    private val _active = MutableStateFlow(false)

    /** Whether any job is up or due back, which is what the foreground service holds the process for. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        logs = JobLogStore(appContext)
        scope.launch {
            LinuxSessions.sessions.collect { sessions -> onSessions(sessions.map { it.key }.toSet()) }
        }
    }

    fun logFile(id: String): File? = logs.fileFor(id).takeIf { it.exists() }

    fun sync(jobs: List<LinuxJob>) {
        val halted = mutableListOf<Runner>()
        val relaunched = mutableListOf<Runner>()

        synchronized(runners) {
            val ids = jobs.map { it.id }.toSet()
            runners.keys.filter { it !in ids }.forEach { id ->
                runners.remove(id)?.let { it.desired = false; halted += it }
            }
            jobs.forEach { job ->
                val runner = runners[job.id]
                when {
                    runner == null -> runners[job.id] = Runner(job)
                    runner.job != job -> {
                        val wasUp = runner.desired
                        halted += runner.also { it.desired = false }
                        runner.job = job
                        if (wasUp && job.enabled) relaunched += runner
                    }
                }
            }
        }

        halted.forEach(::halt)
        relaunched.forEach { start(it.job.id) }
        Timber.i("[JobSupervisor]: synced %d jobs (%d stopped, %d restarted)", jobs.size, halted.size, relaunched.size)
        publishAll()
    }

    fun start(id: String): Boolean {
        val runner = synchronized(runners) {
            val runner = runners[id]?.takeIf { it.job.enabled } ?: return false
            runner.desired = true
            if (runner.state == State.RUNNING || runner.state == State.STARTING) return true
            runner.pendingRestart?.cancel()
            runner.recentRestarts.clear()
            runner.backoffStep = 0
            runner.state = State.STARTING
            runner
        }
        launch(runner)
        return true
    }

    fun stop(id: String): Boolean {
        val runner = synchronized(runners) { runners[id]?.also { it.desired = false } } ?: return false
        halt(runner)
        return true
    }

    fun stopAll() {
        synchronized(runners) { runners.values.onEach { it.desired = false }.toList() }.forEach(::halt)
    }

    fun status(): JSONArray = synchronized(runners) { JSONArray(runners.values.map(::describe)) }

    /**
     * The process snapshot, with each job's state beside what /proc shows for it. A job the
     * supervisor calls running with no process left is marked stale: that is the disagreement
     * the inspector exists to show.
     */
    fun inspect(): JSONObject {
        val (owners, jobs) = synchronized(runners) {
            runners.values.filter { it.pid > 0 }.associate { it.pid to it.job.id } to
                runners.values.map(::describe)
        }
        val snapshot = ProcessInspector.inspect(owners)
        val processes = snapshot.getJSONArray("processes")
        val byOwner = (0 until processes.length()).map { processes.getJSONObject(it) }.groupBy { it.getString("owner") }

        snapshot.put("jobs", JSONArray(jobs.map { job ->
            val mine = byOwner[job.getString("id")].orEmpty()
            job.put("processCount", mine.size)
            job.put("rssKb", mine.sumOf { it.getLong("rssKb") })
            job.put("cpuPercent", Math.round(mine.sumOf { it.optDouble("cpuPercent", 0.0) } * 10) / 10.0)
            val claimsUp = job.getString("state") == State.RUNNING.key
            job.put("stale", claimsUp && mine.none { it.getString("status") != ProcessInspector.Status.ZOMBIE.key })
            job.put("paused", mine.isNotEmpty() && mine.all { it.getString("status") == ProcessInspector.Status.PAUSED.key })
        }))
        return snapshot
    }

    /** The window for [id], or null for a service or an unknown job. */
    fun launchIntent(id: String): PendingIntent? {
        val job = synchronized(runners) { runners[id]?.job } ?: return null
        if (!job.type.hasWindow) return null
        writeScript(job)
        return pendingIntentFor(job)
    }

    /** The [LinuxSessions] key a GUI job's session is filed under. */
    fun sessionKey(id: String): String = "linux-job-$id"

    private fun launch(runner: Runner) {
        val job = runner.job
        val generation = synchronized(runners) {
            runner.generation++
            runner.state = State.STARTING
            runner.message = null
            runner.exitCode = null
            runner.sessionSeen = false
            runner.generation
        }
        publish(runner)

        if (!LinuxProgramLauncher.isAvailable(appContext)) {
            fail(runner, "The Linux environment is not installed")
            return
        }
        runCatching { writeScript(job) }.onFailure {
            fail(runner, "Could not write the job script: ${it.message}")
            return
        }

        if (job.type.hasWindow) {
            // Already open from an earlier launch: the request below only brings it forward, and
            // no session change is coming to say it is running.
            if (LinuxSessions.isRunning(sessionKey(job.id))) {
                synchronized(runners) {
                    runner.sessionSeen = true
                    runner.state = State.RUNNING
                }
                publish(runner)
            }
            logs.note(job.id, job.logMaxBytes, "opening window")
            listener?.onLaunchRequest(job.id, pendingIntentFor(job))
            return
        }

        val process = runCatching { startService(job) }.getOrElse {
            fail(runner, "Could not start: ${it.message}")
            return
        }
        val pid = LinuxProgramLauncher.pidOf(process)
        synchronized(runners) {
            runner.process = process
            runner.pid = pid
            runner.state = State.RUNNING
            runner.startedAt = SystemClock.elapsedRealtime()
            runner.startedWallMs = System.currentTimeMillis()
        }
        logs.note(job.id, job.logMaxBytes, "started (proot pid $pid)")
        publish(runner)

        Thread({
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            onExit(runner, generation, code)
        }, "linux-job-${job.id}").apply { isDaemon = true }.start()
    }

    private fun startService(job: LinuxJob): Process {
        val argv = LinuxProgramLauncher.buildArgv(
            appContext,
            listOf("/bin/sh", "$SCRIPT_DIR/${job.id}.sh"),
            displaySocketDir = null,
        )
        val builder = ProcessBuilder(argv)
            .directory(LinuxRootfs.rootfsDir(appContext))
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            LinuxProgramLauncher.prootEnv(appContext).toStringArray().forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq > 0) put(kv.substring(0, eq), kv.substring(eq + 1))
            }
        }
        val process = builder.start()
        // No one types into a service: end of input rather than a pipe that never delivers.
        runCatching { process.outputStream.close() }

        val log = logs.writer(job.id, job.logMaxBytes)
        Thread({
            log.use { writer ->
                runCatching { process.inputStream.bufferedReader().forEachLine(writer::line) }
            }
        }, "linux-job-log-${job.id}").apply { isDaemon = true }.start()
        return process
    }

    /**
     * Writes the script a job runs, rewritten on every start so it follows the definition.
     *
     * A script rather than an argument list because a GUI job's command reaches the guest as one
     * string split on spaces, which cannot carry quoting; this way every type runs the same file.
     */
    private fun writeScript(job: LinuxJob) {
        val dir = File(LinuxRootfs.rootfsDir(appContext), SCRIPT_DIR.removePrefix("/")).apply { mkdirs() }
        val body = buildString {
            appendLine("#!/bin/sh")
            appendLine("# Linux Services job \"${job.name}\" (${job.id}); rewritten by GameNative on every start.")
            // Re-exec once so the marker is in this shell's own environment, not just its children's.
            val marker = ProcessInspector.JOB_MARKER
            appendLine("[ \"\$$marker\" = ${quote(job.id)} ] || exec env $marker=${quote(job.id)} /bin/sh \"\$0\"")
            job.env.forEach { (key, value) -> appendLine("export $key=${quote(value)}") }
            appendLine("cd ${quote(job.cwd)} 2>/dev/null || cd /root")
            if (job.argv != null) {
                appendLine("exec " + job.argv.joinToString(" ", transform = ::quote))
            } else {
                appendLine(job.shell)
            }
        }
        File(dir, "${job.id}.sh").writeText(body)
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun pendingIntentFor(job: LinuxJob): PendingIntent {
        val script = "$SCRIPT_DIR/${job.id}.sh"
        val argv = when (job.type) {
            // -hold keeps the output on screen for a job that will not be restarted; one that will
            // must be allowed to exit, or its window would outlive it and it would never come back.
            LinuxJob.Type.TERMINAL ->
                if (job.restart.policy == LinuxJob.Policy.NEVER) "xterm -hold -e /bin/sh $script" else "xterm -e /bin/sh $script"
            else -> "/bin/sh $script"
        }
        val intent = LinuxSessionActivity.intent(appContext, argv, sessionKey(job.id), job.name)
        return PendingIntent.getActivity(
            appContext,
            job.id.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun onSessions(keys: Set<String>) {
        val exited = mutableListOf<Pair<Runner, Int>>()
        val changed = mutableListOf<Runner>()
        synchronized(runners) {
            runners.values.filter { it.job.type.hasWindow }.forEach { runner ->
                val open = sessionKey(runner.job.id) in keys
                when {
                    open && !runner.sessionSeen -> {
                        runner.sessionSeen = true
                        runner.state = State.RUNNING
                        runner.startedAt = SystemClock.elapsedRealtime()
                        runner.startedWallMs = System.currentTimeMillis()
                        changed += runner
                    }
                    // The session reports no exit status, so its end reads as a clean exit.
                    !open && runner.sessionSeen -> {
                        runner.sessionSeen = false
                        exited += runner to runner.generation
                    }
                }
            }
        }
        changed.forEach(::publish)
        exited.forEach { (runner, generation) -> onExit(runner, generation, 0) }
    }

    private fun onExit(runner: Runner, generation: Int, code: Int) {
        val job = runner.job
        val ranMs = SystemClock.elapsedRealtime() - runner.startedAt
        synchronized(runners) {
            if (generation != runner.generation) return
            runner.process = null
            runner.pid = -1
            runner.exitCode = code
        }
        logs.note(job.id, job.logMaxBytes, "exited with status $code after ${ranMs / 1000}s")
        endLeftovers(job)

        synchronized(runners) {
            when {
                !runner.desired -> runner.state = State.STOPPED
                !job.shouldRestart(code) -> {
                    runner.desired = false
                    runner.state = State.EXITED
                }
                else -> scheduleRestart(runner, ranMs)
            }
        }
        publish(runner)
    }

    /** Called holding the lock. */
    private fun scheduleRestart(runner: Runner, ranMs: Long) {
        val job = runner.job
        val now = SystemClock.elapsedRealtime()
        while (runner.recentRestarts.isNotEmpty() && now - runner.recentRestarts.first() > job.restart.windowMs) {
            runner.recentRestarts.removeFirst()
        }
        if (runner.recentRestarts.size >= job.restart.maxRestarts) {
            runner.desired = false
            runner.state = State.FAILED
            runner.message = "Restarted ${runner.recentRestarts.size} times in ${job.restart.windowMs / 1000}s; giving up"
            logs.note(job.id, job.logMaxBytes, runner.message!!)
            Timber.w("[JobSupervisor]: %s is crash-looping, marked failed", job.id)
            return
        }

        if (ranMs >= STABLE_RUN_MS) runner.backoffStep = 0
        val wait = (BACKOFF_MIN_MS shl runner.backoffStep).coerceAtMost(BACKOFF_MAX_MS)
        runner.backoffStep = (runner.backoffStep + 1).coerceAtMost(6)
        runner.recentRestarts.addLast(now)
        runner.restarts++
        runner.state = State.BACKOFF
        runner.message = "Restarting in ${wait / 1000}s"

        runner.pendingRestart = scope.launch {
            delay(wait)
            val due = synchronized(runners) { runner.desired && runner.state == State.BACKOFF }
            if (due) launch(runner)
        }
    }

    /**
     * Ends guest processes a job left behind. A PRoot killed outright does not take its guests
     * with it, and a restart next to them would run the job twice and find its port taken.
     */
    private fun endLeftovers(job: LinuxJob) {
        val ended = ProcessInspector.endJob(job.id)
        if (ended > 0) {
            logs.note(job.id, job.logMaxBytes, "ended $ended leftover process(es)")
            Timber.w("[JobSupervisor]: %s left %d processes behind, ended them", job.id, ended)
        }
    }

    private fun fail(runner: Runner, message: String) {
        synchronized(runners) {
            runner.desired = false
            runner.state = State.FAILED
            runner.message = message
        }
        logs.note(runner.job.id, runner.job.logMaxBytes, message)
        Timber.w("[JobSupervisor]: %s failed: %s", runner.job.id, message)
        publish(runner)
    }

    /** Ends a job's processes, or its session. The caller has already cleared [Runner.desired]. */
    private fun halt(runner: Runner) {
        val (process, pid, sessionSeen) = synchronized(runners) {
            runner.pendingRestart?.cancel()
            runner.pendingRestart = null
            // A new generation before anything is ended, so the exit this stop causes is not
            // handled as a crash (or swept twice), and a start straight after it is not refused
            // as already running.
            runner.generation++
            val seen = runner.sessionSeen
            runner.sessionSeen = false
            Triple(runner.process, runner.pid, seen)
        }
        val wasUp = process != null || sessionSeen
        if (runner.job.type.hasWindow) {
            if (LinuxSessions.isRunning(sessionKey(runner.job.id)) || sessionSeen) {
                LinuxSessions.stop(appContext, sessionKey(runner.job.id))
            }
        } else if (process != null) {
            GuestProcesses.end(process, pid, "job ${runner.job.id}")
        }
        endLeftovers(runner.job)
        synchronized(runners) {
            runner.process = null
            runner.pid = -1
            if (runner.state != State.FAILED && runner.state != State.EXITED) runner.state = State.STOPPED
        }
        if (wasUp) logs.note(runner.job.id, runner.job.logMaxBytes, "stopped")
        publish(runner)
    }

    private fun describe(runner: Runner): JSONObject = JSONObject().apply {
        put("id", runner.job.id)
        put("name", runner.job.name)
        put("type", runner.job.type.key)
        put("state", runner.state.key)
        put("desired", runner.desired)
        if (runner.pid > 0) put("pid", runner.pid)
        runner.exitCode?.let { put("exitCode", it) }
        put("restarts", runner.restarts)
        if (runner.startedWallMs > 0) put("startedAt", runner.startedWallMs)
        runner.message?.let { put("message", it) }
    }

    private fun publish(runner: Runner) {
        val state = synchronized(runners) { describe(runner) }
        updateActive()
        listener?.onState(runner.job.id, state)
    }

    private fun publishAll() {
        updateActive()
        val all = synchronized(runners) { runners.values.map { it.job.id to describe(it) } }
        all.forEach { (id, state) -> listener?.onState(id, state) }
    }

    private fun updateActive() {
        _active.value = synchronized(runners) {
            runners.values.any { it.desired || it.process != null }
        }
    }
}
