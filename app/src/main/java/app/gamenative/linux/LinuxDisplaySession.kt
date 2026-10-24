package app.gamenative.linux

import android.content.Context
import android.view.View
import app.gamenative.linux.rfb.RfbView
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
    override val display: Int,
    /** Loopback port the presenter connects to. */
    val port: Int,
    /** Whether this session is one application or a desktop. */
    private val mode: LinuxDesktopConfig.Mode,
    /** Which distro and desktop a [LinuxDesktopConfig.Mode.DESKTOP] session runs; Ubuntu and openbox otherwise. */
    private val target: LinuxDesktopSessions.Target,
) : LinuxSession {

    private val rootfs: File =
        LinuxDistros.installedRootfs(context, target.distroId) ?: LinuxRootfs.rootfsDir(context)

    private val ubuntu: Boolean get() = target.distroId == LinuxDistroCatalog.UBUNTU

    private var server: Process? = null
    private var wm: Process? = null
    private var settings: Process? = null
    private var panel: Process? = null

    /** Keeps application windows filling the display; see [LinuxDesktopConfig.FIT_WINDOWS]. */
    private var fitter: Process? = null

    /**
     * The programs launched into this session, which used to be started and forgotten.
     *
     * Without this list the application the user actually opened was the one thing teardown did
     * not end, and a session that is expected to host several of them has to know what it holds.
     */
    private val apps = mutableListOf<Process>()

    @Volatile
    override var isRunning = false
        private set

    companion object {
        /** Away from :0, which the Wine path uses inside its own image. */
        private const val FIRST_DISPLAY = 10

        /** Above the 5900 range so a user's own VNC server is not disturbed. */
        private const val FIRST_PORT = 5950

        /** How many sessions can be up at once, one display each. */
        private const val DISPLAY_COUNT = 16

        private const val READY_TIMEOUT_MS = 20_000L

        /**
         * Ports handed out but not yet listening.
         *
         * [freePort] asks the network whether a port is taken, and an X server takes a second or
         * two to get there. Two sessions starting at once would both be told 5950 was free, and
         * the second server would exit unable to bind -- which matters now that starting several
         * is the point rather than an edge case.
         */
        private val claimed = mutableSetOf<Int>()

        /**
         * Brings a session up and returns once the X server accepts connections.
         *
         * [width] and [height] are the starting size only; the presenter resizes the
         * desktop to match its surface as soon as it knows how big that is.
         *
         * [densityDpi] is Android's density for this display. It is converted before the
         * server sees it: X clients size against a 96dpi baseline where Android uses 160,
         * so passing it through unchanged draws everything about 1.7x too large. See
         * [LinuxDisplayScale], which also applies the user's scale preference.
         */
        suspend fun start(
            context: Context,
            width: Int,
            height: Int,
            densityDpi: Int,
            scalePercent: Int,
            mode: LinuxDesktopConfig.Mode,
            target: LinuxDesktopSessions.Target =
                LinuxDesktopSessions.Target(LinuxDistroCatalog.UBUNTU, LinuxDesktopSessions.Session.OPENBOX),
        ): Result<LinuxDisplaySession> = withContext(Dispatchers.IO) {
            runCatching {
                if (target.isBuiltIn) {
                    if (!LinuxRootfs.isInstalled(context)) {
                        throw IOException("The Linux userland is not installed")
                    }
                    if (!LinuxRootfs.hasDisplaySession(context)) {
                        throw IOException("The graphical session is not installed")
                    }
                } else if (!LinuxDesktopSessions.isInstalled(context, target.distroId, target.session)) {
                    throw IOException(
                        "${target.session.displayName} is not installed in ${LinuxDistroCatalog.nameOf(target.distroId)}",
                    )
                }

                val port = claimPort()
                val session = LinuxDisplaySession(context, FIRST_DISPLAY + (port - FIRST_PORT), port, mode, target)
                runCatching { session.launch(width, height, LinuxDisplayScale.xdpi(densityDpi, scalePercent)) }
                    .onFailure {
                        // Nothing is listening on it and nothing will be, so holding the claim
                        // would retire a display for the life of the process.
                        release(port)
                        throw it
                    }
                session
            }
        }

        /**
         * A port nothing is listening on and no other session has been promised.
         *
         * Binding it here to hold it properly would leave the X server unable to take it, so the
         * claim is a note to ourselves that lasts until the server is up or has failed.
         */
        @Synchronized
        private fun claimPort(): Int {
            for (port in FIRST_PORT until FIRST_PORT + DISPLAY_COUNT) {
                if (port in claimed) continue
                val free = runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) }
                }.isFailure
                if (free) {
                    claimed += port
                    return port
                }
            }
            throw IOException("All $DISPLAY_COUNT graphical sessions are in use")
        }

        @Synchronized
        private fun release(port: Int) {
            claimed -= port
        }

        /**
         * Whether something on [port] answers as an RFB server, which is what a live session's
         * X server does.
         */
        internal fun isServing(port: Int): Boolean = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 200)
                socket.soTimeout = 500
                val header = ByteArray(4)
                java.io.DataInputStream(socket.getInputStream()).readFully(header)
                header.decodeToString() == "RFB "
            }
        }.getOrDefault(false)
    }

    private suspend fun launch(width: Int, height: Int, dpi: Int) {
        Timber.i(
            "[LinuxDisplaySession]: starting display :%d on port %d (%dx%d at %d dpi)",
            display, port, width, height, dpi,
        )
        clearStaleDisplay()
        // TigerVNC has RENDER/Xft; restore faceName so a prior Android X session's core-font
        // ~/.Xdefaults does not leave xterm on "fixed" under this backend.
        writeXdefaults(dpi)
        if (ubuntu) LinuxRootfs.writeFirefoxScale(rootfs, dpi)
        val xServer = LinuxDesktopSessions.X_SERVERS.firstOrNull { File(rootfs, it).exists() }
            ?: throw IOException("No X server in ${LinuxDistroCatalog.nameOf(target.distroId)}")

        // Audio before any client that might open Cubeb/Pulse (browsers, media players).
        LinuxPulse.acquire(context)
        try {
            // -localhost so the desktop is not reachable from the network: this is a transport
            // between two processes on one device, not a remote desktop.
            server = start(
                "/$xServer :$display" +
                    " -geometry ${width}x$height" +
                    " -depth 24" +
                    " -dpi $dpi" +
                    " -rfbport $port" +
                    " -SecurityTypes None" +
                    " -localhost" +
                    " -AlwaysShared" +
                    // Skip unchanged tiles (helps UI chrome); FrameRate caps encode storms on video.
                    " -CompareFB 1" +
                    " -FrameRate 30" +
                    " -desktop gamenative",
                tag = "Xtigervnc",
            ) ?: throw IOException("Could not start the X server")

            awaitGreeting()

            val desktop = mode == LinuxDesktopConfig.Mode.DESKTOP
            val rc = if (desktop) LinuxDesktopConfig.DESKTOP_RC else LinuxDesktopConfig.APP_RC

            if (desktop && target.session != LinuxDesktopSessions.Session.OPENBOX) {
                // A full desktop brings its own window manager, panel and settings daemon.
                LinuxDesktopSessions.prepare(rootfs, target.session)
                val (command, env) = LinuxDesktopSessions.launch(target.session)
                wm = start(command, tag = target.session.id, extraEnv = env)
                    ?: throw IOException("Could not start ${target.session.displayName}")
                isRunning = true
                Timber.i("[LinuxDisplaySession]: display :%d ready (%s in %s)", display, target.session.id, target.distroId)
                return
            }

            // Built from what is installed right now, since the package manager runs in our own
            // terminal. The menu lists only this distro's apps: it launches them in this rootfs.
            if (desktop) {
                if (!ubuntu) {
                    LinuxDesktopSessions.prepare(rootfs, target.session)
                    LinuxBrowserSetup.configure(context, target.distroId)
                }
                LinuxDesktopConfig.writeDesktop(context, LinuxAppScanner.scan(context, target.distroId), rootfs)
                // Opening the desktop is another moment apps become visible; publish stubs here
                // so a visit to Linux Apps is not required after an earlier install.
                LinuxAppReconciler.reconcile(context, LinuxAppScanner.scan(context))
            }

            // Started after the server is up: they all connect to it, and none of them retries.
            wm = start("/usr/bin/openbox --config-file $rc", tag = "openbox")
            if (File(rootfs, "usr/bin/xsettingsd").exists()) {
                settings = start("/usr/bin/xsettingsd", tag = "xsettingsd")
            }

            // Only for an application, whose window is the Android window and should fill it. On a
            // desktop, a window that asked to be small is one the user can move and resize.
            if (!desktop) {
                fitter = start("/bin/sh ${LinuxDesktopConfig.FIT_WINDOWS}", tag = "fit-windows")
            }

            if (desktop) {
                // Not left to the X server, whose idea of an unset root window is a monochrome
                // weave. Runs and exits, so it is not one of the processes we hold on to.
                start("/usr/bin/xsetroot -solid ${LinuxDesktopConfig.BACKGROUND}", tag = "xsetroot")
                panel = start("/usr/bin/tint2 -c ${LinuxDesktopConfig.PANEL_RC}", tag = "tint2")
            }

            isRunning = true
            Timber.i("[LinuxDisplaySession]: display :%d ready (%s)", display, mode.name.lowercase())
        } catch (t: Throwable) {
            // Including cancellation, when the window reopens the session at a new size: a server
            // left running holds the display, and the next attempt's greeting check finds it
            // instead of its own, which has already exited unable to bind.
            if (server != null) stop() else LinuxPulse.release(context)
            throw t
        }
    }

    /**
     * Removes the lock and socket a previous session left behind. Xtigervnc refuses to
     * start on a display whose lock file exists, and PRoot's guest does not always get to
     * clean up when the app goes away.
     *
     * "Stale" is checked rather than assumed. This used to delete whatever it found, which was
     * safe only while one session existed at a time; with several up at once, a session shutting
     * down would take a live neighbour's socket out from under it if it ever saw the same display
     * number, and X clients hold that path open for their whole lives.
     */
    private fun clearStaleDisplay() {
        if (isServing(port)) {
            Timber.i("[LinuxDisplaySession]: display :%d is live, leaving its lock alone", display)
            return
        }

        val leftovers = listOf(
            File(rootfs, "tmp/.X$display-lock"),
            File(rootfs, "tmp/.X11-unix/X$display"),
        ).filter { it.exists() }
        leftovers.forEach {
            Timber.i("[LinuxDisplaySession]: removing stale %s", it.name)
            it.delete()
        }
    }

    private fun start(argv: String, tag: String, extraEnv: Map<String, String> = emptyMap()): Process? =
        LinuxProgramLauncher.start(
            context = context,
            argv = argv,
            tag = tag,
            extraEnv = extraEnv + ("DISPLAY" to ":$display"),
            // The session's own socket directory inside the rootfs, shared by every process
            // that runs there. Binding the Wine image's directory over it would put this X
            // server's socket where the Wine path expects to find its own.
            displaySocketDir = null,
            rootfs = rootfs,
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
    override fun run(argv: String) {
        val process = start(argv, tag = "linux-app") ?: run {
            Timber.w("[LinuxDisplaySession]: could not launch %s on display :%d", argv, display)
            return
        }
        synchronized(apps) { apps += process }
    }

    override fun createView(context: Context, onClosed: (Throwable?) -> Unit): View =
        RfbView(context, port) { failure -> onClosed(failure) }

    override fun releaseView(view: View) {
        (view as? RfbView)?.disconnect()
    }

    /**
     * Rewrites the XSETTINGS file and signals the daemon, so a density change reaches
     * running GTK and Qt apps. They read DPI once at startup otherwise.
     */
    override fun setDpi(densityDpi: Int, scalePercent: Int) {
        val dpi = LinuxDisplayScale.xdpi(densityDpi, scalePercent)
        writeXdefaults(dpi)
        if (ubuntu) LinuxRootfs.writeFirefoxScale(rootfs, dpi)
        val settings = File(rootfs, "root/.xsettingsd")
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
            rootfs = rootfs,
        )
        Timber.i("[LinuxDisplaySession]: dpi set to %d", dpi)
    }

    /**
     * RFB [app.gamenative.linux.rfb.RfbView] already requests desktop size from surface
     * changes; nothing further to do on the session.
     */
    override fun resize(width: Int, height: Int) {
        Timber.d(
            "[LinuxDisplaySession]: resize(%dx%d) deferred to RFB desktop-size path",
            width,
            height,
        )
    }

    /**
     * Xft faceName for xterm under TigerVNC. Android X overwrites this with core fonts;
     * rewriting here keeps the two backends from fighting when the user switches.
     */
    private fun writeXdefaults(dpi: Int) {
        File(rootfs, "root/.Xdefaults").writeText(
            """
            Xft.dpi: $dpi
            XTerm*faceName: DejaVu Sans Mono
            XTerm*faceSize: 11
            XTerm*toolBar: false
            XTerm*background: black
            XTerm*foreground: white
            XTerm*scrollBar: false
            XTerm*saveLines: 4096
            UXTerm*faceName: DejaVu Sans Mono
            UXTerm*faceSize: 11
            UXTerm*toolBar: false
            UXTerm*background: black
            UXTerm*foreground: white
            """.trimIndent() + "\n",
        )
    }

    override fun stop() {
        isRunning = false

        // Applications first and the X server last: killing the server first makes everything
        // still connected to it die noisily on a lost connection, and openbox treats that as
        // fatal. Each of these is a PRoot host whose guest processes go with it -- see
        // [GuestProcesses], which is where the work of actually ending them lives.
        val held = synchronized(apps) { apps.toList().also { apps.clear() } }
        val labelled = held.map { it to "application" } +
            listOf(
                fitter to "fit-windows",
                panel to "tint2",
                settings to "xsettingsd",
                wm to "openbox",
                server to "Xtigervnc",
            )
                .mapNotNull { (process, label) -> process?.let { it to label } }

        for ((process, label) in labelled) {
            val pid = LinuxProgramLauncher.pidOf(process)
            if (pid > 0) {
                GuestProcesses.end(process, pid, "$label on display :$display")
            } else {
                // No pid means the reflection this depends on has broken, so the tree cannot be
                // walked; the handle is all there is.
                Timber.w("[LinuxDisplaySession]: no pid for %s, killing the handle only", label)
                process.destroyForcibly()
            }
        }

        fitter = null
        panel = null
        settings = null
        wm = null
        server = null

        clearStaleDisplay()
        release(port)
        LinuxPulse.release(context)
        Timber.i("[LinuxDisplaySession]: display :%d stopped", display)
    }
}
