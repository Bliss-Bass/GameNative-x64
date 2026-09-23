package app.gamenative.linux

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.enums.LinuxSessionMode
import app.gamenative.service.LinuxSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Every Linux session the app is running, and how long each one lives.
 *
 * Sessions used to be owned by the screen showing them: one field in a composable, torn down in
 * its `onDispose`. That made two things impossible -- a session that survives being closed, and
 * more than one at a time -- and one thing wrong, since a session whose teardown failed became
 * unreachable rather than merely still running.
 *
 * Process-scoped state rather than a bound service, which matches how the game path already
 * holds its X environment. [LinuxSessionService] exists to keep the process alive and to say so
 * in the shade; it is not where the sessions live, because binding to reach them would make
 * every caller asynchronous for no gain within one process.
 *
 * One display per application, not one shared desktop. Each gets its own X server, so Android
 * can size and remember each window independently and a game's display is unaffected.
 */
object LinuxSessions {

    /** The key for the desktop session, which is a place rather than an application. */
    const val DESKTOP_KEY = "::desktop"

    /** What the UI needs to know about a session without reaching into it. */
    data class Snapshot(
        val key: String,
        val label: String,
        val display: Int,
        /** Whether a window is currently showing it, as opposed to it running unattended. */
        val watched: Boolean,
    )

    private class Held(
        val key: String,
        val label: String,
        val argv: String?,
        val session: LinuxSession,
        /** Remembered so a restart can bring the session back the size it was. */
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        var viewers: Int = 0,
    )

    private val held = linkedMapOf<String, Held>()

    private val _sessions = MutableStateFlow<List<Snapshot>>(emptyList())

    /** The running sessions, for the UI to show and offer controls for. */
    val sessions: StateFlow<List<Snapshot>> = _sessions.asStateFlow()

    /** For stops and restarts asked for from the UI, which must not block it. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The key a session is filed under.
     *
     * The desktop entry id where there is one, because it is stable across rescans, reinstalls
     * and upgrades, and is what the generated launcher entry is named after. A stub built before
     * entry ids were carried supplies only a command, so that is hashed to something stable
     * instead -- launching the same application twice must find the session it already has.
     */
    fun keyFor(entryId: String?, argv: String?): String = when {
        entryId != null && entryId.isNotBlank() -> entryId
        argv != null -> "argv:${argv.hashCode()}"
        else -> DESKTOP_KEY
    }

    fun of(key: String): LinuxSession? = synchronized(held) { held[key]?.session }

    fun isRunning(key: String): Boolean = synchronized(held) { held[key]?.session?.isRunning == true }

    /**
     * The session for [key], started if it is not already up, with [argv] launched into it.
     *
     * Returning a session that was already running is the point: a second launch of an
     * application that is still open should show what is there rather than start a rival copy on
     * a second display.
     */
    suspend fun open(
        context: Context,
        key: String,
        label: String,
        argv: String?,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Result<LinuxSession> {
        synchronized(held) {
            held[key]?.takeIf { it.session.isRunning }?.let {
                Timber.i("[LinuxSessions]: reusing display :%d for %s", it.session.display, key)
                return Result.success(it.session)
            }
            // A session that is present but no longer running is a failed teardown or a crashed
            // server; drop it rather than hand it back.
            held.remove(key)?.also { Timber.w("[LinuxSessions]: %s was registered but not running", key) }
        }

        val mode = if (argv == null) LinuxDesktopConfig.Mode.DESKTOP else LinuxDesktopConfig.Mode.APP
        return LinuxDisplaySession.start(context, width, height, densityDpi, mode)
            .onSuccess { session ->
                synchronized(held) {
                    held[key] = Held(key, label, argv, session, width, height, densityDpi)
                }
                withContext(Dispatchers.IO) { argv?.let { session.run(it) } }
                publish()

                // Started while a window is up, which is what makes starting a foreground
                // service legal at all; by the time the window goes away it is already running.
                LinuxSessionService.ensureRunning(context)
            }
            .onFailure { Timber.e(it, "[LinuxSessions]: could not open %s", key) }
    }

    /** Notes that a window is showing [key], so closing it can be told from leaving it running. */
    fun attach(key: String) {
        synchronized(held) { held[key]?.let { it.viewers++ } }
        publish()
    }

    /**
     * Notes that a window showing [key] has gone, and applies the lifetime the user chose.
     *
     * This is where the mode does its work: the session either goes with the window it was
     * opened from, or stays up until something asks it to stop.
     */
    fun detach(context: Context, key: String) {
        val stop = synchronized(held) {
            val session = held[key] ?: return
            session.viewers = (session.viewers - 1).coerceAtLeast(0)
            session.viewers == 0 && !PrefManager.linuxSessionMode.holdsWhenClosed
        }

        if (stop) {
            stop(context, key)
        } else {
            Timber.i("[LinuxSessions]: holding %s with no window open", key)
            publish()
        }
    }

    fun stop(context: Context, key: String) {
        val session = synchronized(held) { held.remove(key) } ?: return
        publish()

        scope.launch {
            session.session.stop()
            LinuxSessionService.stopIfIdle(context)
            // Desktop (and apps that shell out to apt) may have changed .desktop entries.
            LinuxAppReconciler.request(context)
        }
    }

    fun stopAll(context: Context) {
        val all = synchronized(held) { held.values.toList().also { held.clear() } }
        publish()

        scope.launch {
            all.forEach { it.session.stop() }
            LinuxSessionService.stopIfIdle(context)
            LinuxAppReconciler.request(context)
        }
    }

    /**
     * Stops [key] and starts it again with the same program and geometry.
     *
     * For an application that has wedged itself, which is the ordinary reason to want this: with
     * the session held in the background there is otherwise nothing to close and reopen.
     */
    fun restart(context: Context, key: String) {
        val previous = synchronized(held) { held.remove(key) } ?: return
        publish()

        scope.launch {
            previous.session.stop()
            open(
                context = context,
                key = previous.key,
                label = previous.label,
                argv = previous.argv,
                width = previous.width,
                height = previous.height,
                densityDpi = previous.densityDpi,
            )
        }
    }

    /** How the service describes itself, and how it knows whether to keep running. */
    fun count(): Int = synchronized(held) { held.size }

    private fun publish() {
        _sessions.value = synchronized(held) {
            held.values.map { Snapshot(it.key, it.label, it.session.display, it.viewers > 0) }
        }
    }
}
