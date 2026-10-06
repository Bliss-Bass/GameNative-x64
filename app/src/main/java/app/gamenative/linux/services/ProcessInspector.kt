package app.gamenative.linux.services

import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * What is actually running in the guest, as opposed to what the supervisor believes is.
 *
 * Every guest process runs as this app's uid, so /proc tells the whole story: which processes
 * exist, which job or session they descend from, and whether they are doing anything. The
 * supervisor's view can drift from it -- a PRoot killed without cleanup leaves its guests behind,
 * reparented to init, still holding ports -- and the inspector is where that becomes visible.
 */
object ProcessInspector {

    /** Exported by every job script, naming the job. */
    const val JOB_MARKER = "LINUX_SERVICES_JOB"

    /** CPU share since the previous sample at which a process counts as running. */
    private const val ACTIVE_CPU_PERCENT = 1.0

    enum class Status(val key: String) {
        RUNNING("running"),
        SLEEPING("sleeping"),

        /** Stopped by a signal, or frozen by Android's cached-app freezer. */
        PAUSED("paused"),

        /** No longer under this app's process tree: left behind by a PRoot that was killed. */
        STALE("stale"),
        ZOMBIE("zombie"),
    }

    private class Proc(
        val pid: Int,
        val ppid: Int,
        val stateChar: Char,
        val name: String,
        val cmdline: String,
        val cpuTicks: Long,
        val rssKb: Long,
        val wchan: String,
    )

    private val ticksPerSecond: Long by lazy {
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1)
    }

    /** The previous sample's CPU ticks per pid, for a CPU share between consecutive calls. */
    private var previous: Map<Int, Long> = emptyMap()
    private var previousAt = 0L

    /**
     * A snapshot of every guest process. [owners] maps a job's PRoot pid to the job id; anything
     * else under a PRoot is filed as a session.
     */
    @Synchronized
    fun inspect(owners: Map<Int, String>): JSONObject {
        val me = Process.myPid()
        val uid = Process.myUid()
        val procs = readAll(uid).filter { it.pid != me }
        val byPid = procs.associateBy { it.pid }

        val now = SystemClock.elapsedRealtime()
        val elapsedTicks = if (previousAt > 0) (now - previousAt) * ticksPerSecond / 1000 else 0
        val sample = procs.associate { it.pid to it.cpuTicks }

        val array = JSONArray()
        procs.forEach { p ->
            val chain = ancestors(p.pid, byPid)
            val underApp = chain.contains(me) || p.ppid == me
            val owner = (listOf(p.pid) + chain).firstNotNullOfOrNull { pid ->
                owners[pid] ?: byPid[pid]?.cmdline?.let(::jobFromCommand)
            } ?: jobFromEnviron(p.pid)
            val cpu = previous[p.pid]?.let { before ->
                if (elapsedTicks > 0) ((p.cpuTicks - before) * 100.0 / elapsedTicks).coerceAtLeast(0.0) else null
            }
            // The state letter is only the main thread's: a browser's sits in poll() while its
            // other threads decode and render. CPU used since the last sample, which counts every
            // thread, is what says whether the process is doing anything.
            val busy = (cpu != null && cpu >= ACTIVE_CPU_PERCENT) || p.stateChar == 'R' || anyThreadRunning(p.pid)
            val status = when {
                p.stateChar == 'Z' -> Status.ZOMBIE
                !underApp -> Status.STALE
                p.wchan.contains("freez") || p.wchan.contains("refrigerator") -> Status.PAUSED
                busy -> Status.RUNNING
                // 't' is PRoot holding a tracee at a syscall, which is routine; only a group stop
                // (SIGSTOP / SIGTSTP) is a pause.
                p.stateChar == 'T' -> Status.PAUSED
                else -> Status.SLEEPING
            }
            array.put(JSONObject().apply {
                put("pid", p.pid)
                put("ppid", p.ppid)
                put("name", p.name)
                put("cmdline", p.cmdline)
                put("status", status.key)
                put("state", p.stateChar.toString())
                put("rssKb", p.rssKb)
                put("cpuSeconds", p.cpuTicks.toDouble() / ticksPerSecond)
                cpu?.let { put("cpuPercent", Math.round(it * 10) / 10.0) }
                put("proot", p.name.startsWith("libproot") || p.cmdline.contains("libproot-linux.so"))
                put("owner", owner ?: if (underApp) "session" else "orphan")
            })
        }

        previous = sample
        previousAt = now
        return JSONObject().apply {
            put("takenAt", System.currentTimeMillis())
            put("appPid", me)
            put("processes", array)
        }
    }

    /**
     * Ends [pid] if it is one of this app's guest processes: never the app itself, and never a
     * process another uid owns, which the kill would be refused for anyway.
     */
    fun end(pid: Int): Boolean {
        if (pid <= 1 || pid == Process.myPid()) return false
        val status = File("/proc/$pid/status")
        val ownerUid = runCatching {
            status.useLines { lines -> lines.first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt() }
        }.getOrNull() ?: return false
        if (ownerUid != Process.myUid()) return false
        // A paused process cannot act on SIGTERM until it is continued.
        runCatching { Os.kill(pid, OsConstants.SIGCONT) }
        runCatching { Os.kill(pid, OsConstants.SIGTERM) }
        Thread.sleep(500)
        if (File("/proc/$pid").exists()) runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        return true
    }

    /**
     * Ends every guest process still carrying [jobId]'s marker, wherever it was reparented to.
     * Returns how many there were.
     */
    fun endJob(jobId: String): Int {
        val me = Process.myPid()
        val pids = File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }
            .filter { it != me && jobFromEnviron(it) == jobId }
        if (pids.isEmpty()) return 0
        pids.forEach { pid ->
            runCatching { Os.kill(pid, OsConstants.SIGCONT) }
            runCatching { Os.kill(pid, OsConstants.SIGTERM) }
        }
        Thread.sleep(500)
        pids.filter { File("/proc/$it").exists() }.forEach { runCatching { Os.kill(it, OsConstants.SIGKILL) } }
        return pids.size
    }

    /**
     * The job a process belongs to, by the marker every job script exports. Descendants inherit
     * it, so it survives exec and reparenting where the script path in the command line does not.
     * Other uids' environments are unreadable, which keeps this to guest processes.
     */
    private fun jobFromEnviron(pid: Int): String? = runCatching {
        File("/proc/$pid/environ").readText().split('\u0000')
            .firstOrNull { it.startsWith("$JOB_MARKER=") }?.substringAfter('=')
    }.getOrNull()?.ifBlank { null }

    private val jobScript = Regex(Regex.escape(JobSupervisor.SCRIPT_DIR) + "/([A-Za-z0-9._-]+)\\.sh")

    /**
     * The job a command belongs to, by the script it runs. This is what files a GUI job's
     * processes under it, since its PRoot is the session's and the supervisor never sees its pid.
     */
    private fun jobFromCommand(cmdline: String): String? = jobScript.find(cmdline)?.groupValues?.get(1)

    private fun anyThreadRunning(pid: Int): Boolean =
        File("/proc/$pid/task").listFiles().orEmpty().any { task ->
            runCatching {
                val stat = File(task, "stat").readText()
                stat[stat.lastIndexOf(')') + 2] == 'R'
            }.getOrDefault(false)
        }

    private fun ancestors(pid: Int, byPid: Map<Int, Proc>): List<Int> {
        val chain = mutableListOf<Int>()
        var current = byPid[pid]?.ppid ?: return chain
        // Bounded, in case pid reuse between reads ever makes a loop.
        repeat(64) {
            chain += current
            current = byPid[current]?.ppid ?: return chain
        }
        return chain
    }

    private fun readAll(uid: Int): List<Proc> =
        File("/proc").listFiles().orEmpty().mapNotNull { entry ->
            val pid = entry.name.toIntOrNull() ?: return@mapNotNull null
            runCatching { read(pid, uid) }.getOrNull()
        }

    private fun read(pid: Int, uid: Int): Proc? {
        val dir = File("/proc/$pid")
        val status = File(dir, "status").readLines()
        val ownerUid = status.first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
        if (ownerUid != uid) return null

        // comm may contain spaces and parentheses, so fields are counted from the last ')'.
        val stat = File(dir, "stat").readText()
        val name = stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'))
        val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
        val rssKb = status.firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
        val cmdline = runCatching { File(dir, "cmdline").readText().replace('\u0000', ' ').trim() }.getOrDefault("")

        return Proc(
            pid = pid,
            ppid = fields[1].toInt(),
            stateChar = fields[0].first(),
            name = name,
            cmdline = cmdline.ifBlank { "[$name]" },
            cpuTicks = fields[11].toLong() + fields[12].toLong(),
            rssKb = rssKb,
            wchan = runCatching { File(dir, "wchan").readText() }.getOrDefault(""),
        )
    }
}
