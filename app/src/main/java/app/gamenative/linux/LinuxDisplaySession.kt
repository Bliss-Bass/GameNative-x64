package app.gamenative.linux

import android.content.Context
import com.winlator.core.ProcessHelper
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A graphical session in the rootfs: an X server that speaks RFB, a window manager, and a
 * settings daemon.
 *
 * Xtigervnc is both the X server and the RFB server, so damage arrives as the server
 * generates it rather than through a screen-scraper polling a second process, and the
 * screen can be resized while clients are running. Openbox is what makes such a resize
 * reach the application: X clients do not resize because the screen did, they resize when
 * a window manager configures them.
 */
class LinuxDisplaySession private constructor(
    private val context: Context,
    /** X display number, as in ":2". */
    val display: Int,
    /** Loopback port the presenter connects to. */
    val port: Int,
    /** Whether this session is one application or a desktop. */
    private val mode: LinuxDesktopConfig.Mode,
) {

    private var server: Process? = null
    private var wm: Process? = null
    private var settings: Process? = null
    private var panel: Process? = null

    @Volatile
    var isRunning = false
        private set

    companion object {
        /** Away from :0, which the Wine path uses inside its own image. */
        private const val FIRST_DISPLAY = 10

        /** Above the 5900 range so a user's own VNC server is not disturbed. */
        private const val FIRST_PORT = 5950

        private const val READY_TIMEOUT_MS = 20_000L

        /**
         * Brings a session up and returns once the X server accepts connections.
         *
         * [width] and [height] are the starting size only; the presenter resizes the
         * desktop to match its surface as soon as it knows how big that is.
         *
         * [densityDpi] is what the server will report to clients. Every Xft client sizes
         * type from it, so leaving it at the X default of 96 draws text at roughly half
         * size on a panel like this one.
         */
        suspend fun start(
            context: Context,
            width: Int,
            height: Int,
            densityDpi: Int,
            mode: LinuxDesktopConfig.Mode,
        ): Result<LinuxDisplaySession> = withContext(Dispatchers.IO) {
            runCatching {
                if (!LinuxRootfs.isInstalled(context)) {
                    throw IOException("The Linux userland is not installed")
                }
                if (!LinuxRootfs.hasDisplaySession(context)) {
                    throw IOException("The graphical session is not installed")
                }

                val port = freePort()
                val session = LinuxDisplaySession(context, FIRST_DISPLAY + (port - FIRST_PORT), port, mode)
                session.launch(width, height, densityDpi.coerceIn(96, 400))
                session
            }
        }

        /**
         * A port nothing is listening on. Racy in principle, but the alternative -- binding
         * it here to hold it -- would leave the X server unable to take it.
         */
        private fun freePort(): Int {
            for (port in FIRST_PORT until FIRST_PORT + 16) {
                val free = runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) }
                }.isFailure
                if (free) return port
            }
            throw IOException("No free port for the graphical session")
        }
    }

    private suspend fun launch(width: Int, height: Int, dpi: Int) {
        Timber.i(
            "[LinuxDisplaySession]: starting display :%d on port %d (%dx%d at %d dpi)",
            display, port, width, height, dpi,
        )
        clearStaleDisplay()

        // -localhost so the desktop is not reachable from the network: this is a transport
        // between two processes on one device, not a remote desktop.
        server = start(
            "/usr/bin/Xtigervnc :$display" +
                " -geometry ${width}x$height" +
                " -depth 24" +
                " -dpi $dpi" +
                " -rfbport $port" +
                " -SecurityTypes None" +
                " -localhost" +
                " -AlwaysShared" +
                " -desktop gamenative",
            tag = "Xtigervnc",
        ) ?: throw IOException("Could not start the X server")

        awaitGreeting()

        val desktop = mode == LinuxDesktopConfig.Mode.DESKTOP
        val rc = if (desktop) LinuxDesktopConfig.DESKTOP_RC else LinuxDesktopConfig.APP_RC

        // Built from what is installed right now, since apt runs in our own terminal.
        if (desktop) {
            LinuxDesktopConfig.writeDesktop(context, LinuxAppScanner.scan(context), dpi)
        }


        // Started after the server is up: they all connect to it, and none of them retries.
        wm = start("/usr/bin/openbox --config-file $rc", tag = "openbox")
        settings = start("/usr/bin/xsettingsd", tag = "xsettingsd")

        if (desktop) {
            // Not left to the X server, whose idea of an unset root window is a monochrome
            // weave. Runs and exits, so it is not one of the processes we hold on to.
            start("/usr/bin/xsetroot -solid ${LinuxDesktopConfig.BACKGROUND}", tag = "xsetroot")
            panel = start("/usr/bin/tint2 -c ${LinuxDesktopConfig.PANEL_RC}", tag = "tint2")
        }

        isRunning = true
        Timber.i("[LinuxDisplaySession]: display :%d ready (%s)", display, mode.name.lowercase())
    }

    /**
     * Removes the lock and socket a previous session left behind. Xtigervnc refuses to
     * start on a display whose lock file exists, and PRoot's guest does not always get to
     * clean up when the app goes away.
     */
    private fun clearStaleDisplay() {
        val rootfs = LinuxRootfs.rootfsDir(context)
        val leftovers = listOf(
            File(rootfs, "tmp/.X$display-lock"),
            File(rootfs, "tmp/.X11-unix/X$display"),
        ).filter { it.exists() }
        leftovers.forEach {
            Timber.i("[LinuxDisplaySession]: removing stale %s", it.name)
            it.delete()
        }
    }

    private fun start(argv: String, tag: String): Process? = LinuxProgramLauncher.start(
        context = context,
        argv = argv,
        tag = tag,
        extraEnv = mapOf("DISPLAY" to ":$display"),
        // The session's own socket directory inside the rootfs, shared by every process
        // that runs there. Binding the Wine image's directory over it would put this X
        // server's socket where the Wine path expects to find its own.
        displaySocketDir = null,
    )

    /**
     * Waits until something on the port identifies itself as an RFB server.
     *
     * A bare TCP connect is not enough: the port may be held by a dying server from an
     * earlier session, in which case the connect succeeds and the real server, unable to
     * bind, has already exited.
     */
    private suspend fun awaitGreeting() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var lastFailure: String? = null

        while (System.currentTimeMillis() < deadline) {
            if (server?.isAlive == false) {
                throw IOException("The X server exited during startup, exit code ${server?.exitValue()}")
            }

            val greeting = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                    socket.soTimeout = 1_000
                    val header = ByteArray(12)
                    java.io.DataInputStream(socket.getInputStream()).readFully(header)
                    header.decodeToString()
                }
            }
            greeting.onSuccess { header ->
                if (header.startsWith("RFB ")) {
                    Timber.i("[LinuxDisplaySession]: server greeting %s", header.trim())
                    return
                }
                lastFailure = "unexpected greeting '${header.trim()}'"
            }
            greeting.onFailure { lastFailure = it.message }
            delay(200)
        }

        stop()
        throw IOException("The X server did not start within ${READY_TIMEOUT_MS / 1000}s ($lastFailure)")
    }

    /** Runs [argv] against this session's display, for launching applications into it. */
    fun run(argv: String) {
        start(argv, tag = "linux-app")
    }

    /**
     * Rewrites the XSETTINGS file and signals the daemon, so a density change reaches
     * running GTK and Qt apps. They read DPI once at startup otherwise.
     */
    fun setDpi(densityDpi: Int) {
        val dpi = densityDpi.coerceIn(96, 400)
        val settings = File(LinuxRootfs.rootfsDir(context), "root/.xsettingsd")
        val text = settings.takeIf { it.isFile }?.readText() ?: return
        val updated = text.lines().joinToString("\n") { line ->
            if (line.startsWith("Xft/DPI ")) "Xft/DPI ${dpi * 1024}" else line
        }
        if (updated == text) return

        settings.writeText(updated)
        // xsettingsd rereads its config on HUP; there is no other way to poke it.
        LinuxProgramLauncher.exec(
            context = context,
            argv = "/usr/bin/pkill -HUP xsettingsd",
            extraEnv = mapOf("DISPLAY" to ":$display"),
            displaySocketDir = null,
        )
        Timber.i("[LinuxDisplaySession]: dpi set to %d", dpi)
    }

    fun stop() {
        isRunning = false
        // The X server last: killing it first makes the clients die noisily on a lost
        // connection, and openbox in particular logs a fatal error.
        for (process in listOf(panel, settings, wm, server)) {
            val child = process ?: continue
            // TERM first so PRoot's --kill-on-exit can take the guest down with it;
            // destroyForcibly is SIGKILL, which PRoot cannot pass on.
            val pid = LinuxProgramLauncher.pidOf(child)
            if (pid > 0) runCatching { ProcessHelper.terminateProcess(pid) }
            if (!child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly()
        }
        panel = null
        settings = null
        wm = null
        server = null

        clearStaleDisplay()
        Timber.i("[LinuxDisplaySession]: display :%d stopped", display)
    }
}
