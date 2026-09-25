package app.gamenative.linux

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.view.View
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A Linux graphical session on the in-process Android [XServer] (same stack as Wine games).
 *
 * Replaces the old Xtigervnc + RFB path: guests speak X11 over AF_UNIX into
 * Winlator's server, and the freeform window presents with [LinuxXServerView] instead of
 * copying Raw RFB pixels.
 *
 * One Android X per freeform app — never Wine's imagefs `:0`.
 */
class LinuxAndroidXSession private constructor(
    private val context: Context,
    /** Slot id for the host socket directory; guests always see DISPLAY=:0. */
    override val display: Int,
    private val mode: LinuxDesktopConfig.Mode,
    private val sessionRoot: File,
) : LinuxSession {

    private var xServer: XServer? = null
    private var xServerComponent: XServerComponent? = null
    private var sysVComponent: SysVSharedMemoryComponent? = null

    private var wm: Process? = null
    private var settings: Process? = null
    private var panel: Process? = null
    private var fitter: Process? = null
    private val apps = mutableListOf<Process>()

    private var viewCallback: ((Throwable?) -> Unit)? = null
    private var sessionDpi: Int = LinuxDisplayScale.X_BASELINE_DPI

    @Volatile
    override var isRunning = false
        private set

    /** Host directory bound onto the guest's `/tmp/.X11-unix`. */
    val displaySocketDir: File
        get() = File(sessionRoot, "tmp/.X11-unix")

    companion object {
        private const val FIRST_DISPLAY = 10
        private const val DISPLAY_COUNT = 16
        private const val READY_TIMEOUT_MS = 15_000L

        private val claimed = mutableSetOf<Int>()

        suspend fun start(
            context: Context,
            width: Int,
            height: Int,
            densityDpi: Int,
            mode: LinuxDesktopConfig.Mode,
        ): Result<LinuxAndroidXSession> = withContext(Dispatchers.IO) {
            runCatching {
                if (!LinuxRootfs.isInstalled(context)) {
                    throw IOException("The Linux userland is not installed")
                }
                if (!LinuxRootfs.hasDisplaySession(context)) {
                    throw IOException("The graphical session is not installed")
                }

                val display = claimDisplay(context)
                val sessionRoot = sessionRootDir(context, display).apply {
                    mkdirs()
                    File(this, "tmp/.X11-unix").mkdirs()
                    File(this, "tmp/.sysvshm").mkdirs()
                }
                val session = LinuxAndroidXSession(context, display, mode, sessionRoot)
                runCatching {
                    session.launch(
                        width.coerceAtLeast(320),
                        height.coerceAtLeast(240),
                        LinuxDisplayScale.xdpi(densityDpi),
                    )
                }.onFailure {
                    releaseDisplay(display)
                    throw it
                }
                session
            }
        }

        fun sessionRootDir(context: Context, display: Int): File =
            File(LinuxRootfs.rootDir(context), "x11/$display")

        @Synchronized
        private fun claimDisplay(context: Context): Int {
            for (display in FIRST_DISPLAY until FIRST_DISPLAY + DISPLAY_COUNT) {
                if (display in claimed) continue
                val socket = File(sessionRootDir(context, display), "tmp/.X11-unix/X0")
                if (socket.exists() && isUnixSocketLive(socket)) continue
                claimed += display
                return display
            }
            throw IOException("All $DISPLAY_COUNT graphical sessions are in use")
        }

        @Synchronized
        private fun releaseDisplay(display: Int) {
            claimed -= display
        }
    }

    private suspend fun launch(width: Int, height: Int, dpi: Int) {
        Timber.i(
            "[LinuxAndroidXSession]: starting Android X slot %d (%dx%d at %d dpi) root=%s",
            display, width, height, dpi, sessionRoot.absolutePath,
        )
        clearStaleSockets()

        LinuxPulse.acquire(context)
        try {
            sessionDpi = dpi
            val server = XServer(ScreenInfo(width, height, dpi), /* useGlibcContainer = */ true)
            // No guest WM answers MapRequest; map/configure top-levels ourselves.
            server.windowManager.setAutomanageTopLevel(true)
            xServer = server

            val shm = SysVSharedMemoryComponent(
                server,
                UnixSocketConfig.createSocket(sessionRoot.absolutePath, UnixSocketConfig.SYSVSHM_SERVER_PATH),
            )
            val xComp = XServerComponent(
                server,
                UnixSocketConfig.createSocket(sessionRoot.absolutePath, UnixSocketConfig.XSERVER_PATH),
            )
            sysVComponent = shm
            xServerComponent = xComp
            shm.start()
            xComp.start()

            awaitSocket()
            writeXresources(dpi)
            installAppWindowFitter(server)

            val desktop = mode == LinuxDesktopConfig.Mode.DESKTOP

            if (desktop) {
                val scanned = LinuxAppScanner.scan(context)
                LinuxDesktopConfig.writeDesktop(context, scanned)
                LinuxAppReconciler.reconcile(context, scanned)
            }

            // Openbox needs XKB/Shape/RandR that Winlator does not advertise and SIGSEGVs on
            // BadImplementation. Wine games never use a guest WM either — DesktopHelper plus
            // [installAppWindowFitter] fill application windows instead.
            settings = startGuest("/usr/bin/xsettingsd", tag = "xsettingsd")
            if (desktop) {
                startGuest("/usr/bin/xsetroot -solid ${LinuxDesktopConfig.BACKGROUND}", tag = "xsetroot")
                // tint2 also expects a WM; skip until we have a compatible panel story.
            }

            isRunning = true
            Timber.i("[LinuxAndroidXSession]: slot %d ready (%s)", display, mode.name.lowercase())
        } catch (t: Throwable) {
            LinuxPulse.release(context)
            throw t
        }
    }

    /**
     * When an application window maps, force it to the full Android X screen so freeform
     * Linux apps fill their task the way openbox+fit-windows did under Xtigervnc.
     *
     * GTK often maps before size hints relax, so we also re-fit shortly after map.
     */
    private fun installAppWindowFitter(server: XServer) {
        if (mode != LinuxDesktopConfig.Mode.APP) return
        val w = server.screenInfo.width
        val h = server.screenInfo.height
        val mainHandler = Handler(Looper.getMainLooper())
        server.windowManager.addOnWindowModificationListener(
            object : com.winlator.xserver.WindowManager.OnWindowModificationListener {
                override fun onMapWindow(window: com.winlator.xserver.Window) {
                    Timber.i(
                        "[LinuxAndroidXSession]: map window id=%d %dx%d parent=%s override=%s io=%s",
                        window.id,
                        window.width.toInt(),
                        window.height.toInt(),
                        if (window.parent == server.windowManager.rootWindow) "root" else "other",
                        window.attributes.isOverrideRedirect,
                        window.isInputOutput,
                    )
                    fitTopLevel(server, window, w, h)
                    mainHandler.postDelayed({ fitTopLevel(server, window, w, h) }, 750L)
                    mainHandler.postDelayed({ fitTopLevel(server, window, w, h) }, 2000L)
                }

                override fun onUpdateWindowGeometry(
                    window: com.winlator.xserver.Window,
                    resized: Boolean,
                ) {
                    if (!resized) return
                    // Firefox/GTK often Configure themselves smaller right after map; snap back.
                    fitTopLevel(server, window, w, h)
                }
            },
        )
    }

    private fun fitTopLevel(
        server: XServer,
        window: com.winlator.xserver.Window,
        w: Short,
        h: Short,
    ) {
        if (!isRunning) return
        if (window.parent != server.windowManager.rootWindow) return
        if (!window.isInputOutput) return
        if (!window.attributes.isMapped) return
        // Skip override-redirect chrome (menus, tooltips).
        if (window.attributes.isOverrideRedirect) return
        // Skip tiny dialogs (xmessage ~90×60). Everything else in APP mode fills the
        // freeform client area so xterm/Firefox match the Android window frame.
        if (window.width < 200 || window.height < 150) return
        // Skip already-full windows (avoids drawable churn on the delayed re-fit).
        if (window.width == w && window.height == h && window.x.toInt() == 0 && window.y.toInt() == 0) return
        try {
            server.lock(
                XServer.Lockable.WINDOW_MANAGER,
                XServer.Lockable.DRAWABLE_MANAGER,
            ).use {
                server.windowManager.forceGeometry(window, 0, 0, w, h)
            }
            Timber.i(
                "[LinuxAndroidXSession]: fitted window %#x to %dx%d",
                window.id,
                w.toInt(),
                h.toInt(),
            )
        } catch (t: Throwable) {
            Timber.w(t, "[LinuxAndroidXSession]: could not fit window %#x", window.id)
        }
    }

    private fun writeXresources(dpi: Int) {
        val rootfs = LinuxRootfs.rootfsDir(context)
        val settingsFile = File(rootfs, "root/.xsettingsd")
        if (settingsFile.isFile) {
            val text = settingsFile.readText()
            val updated = text.lines().joinToString("\n") { line ->
                if (line.startsWith("Xft/DPI ")) "Xft/DPI ${dpi * 1024}" else line
            }
            if (updated != text) settingsFile.writeText(updated)
        }
        // Android X has no RENDER, so Xft faceName leaves xterm with nothing to draw.
        // Core fonts + server-side ImageText/PolyText are what work here; keep faceSize
        // out and pin Xft.dpi for any client that still reads ~/.Xdefaults.
        File(rootfs, "root/.Xdefaults").writeText(
            """
            Xft.dpi: $dpi
            XTerm*font: fixed
            XTerm*toolBar: false
            XTerm*background: black
            XTerm*foreground: white
            XTerm*scrollBar: false
            XTerm*saveLines: 4096
            UXTerm*font: fixed
            UXTerm*toolBar: false
            UXTerm*background: black
            UXTerm*foreground: white
            """.trimIndent() + "\n",
        )
    }

    private fun clearStaleSockets() {
        val x0 = File(displaySocketDir, "X0")
        if (x0.exists() && !isUnixSocketLive(x0)) {
            Timber.i("[LinuxAndroidXSession]: removing stale %s", x0.absolutePath)
            x0.delete()
        }
        val sm0 = File(sessionRoot, "tmp/.sysvshm/SM0")
        if (sm0.exists()) sm0.delete()
    }

    private suspend fun awaitSocket() {
        val socket = File(displaySocketDir, "X0")
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists() && isUnixSocketLive(socket)) {
                Timber.i("[LinuxAndroidXSession]: X socket ready at %s", socket.absolutePath)
                return
            }
            last = if (socket.exists()) "socket present but not accepting" else "socket missing"
            delay(100)
        }
        stop()
        throw IOException("Android X did not listen within ${READY_TIMEOUT_MS / 1000}s ($last)")
    }

    private fun startGuest(argv: String, tag: String): Process? = LinuxProgramLauncher.start(
        context = context,
        argv = argv,
        tag = tag,
        extraEnv = graphicalEnv(),
        displaySocketDir = displaySocketDir,
        extraBinds = LinuxProgramLauncher.graphicalBinds(),
    )

    private fun graphicalEnv(): Map<String, String> = buildMap {
        put("DISPLAY", ":0")
        putAll(LinuxProgramLauncher.mesaEnv())
        // Qt does not always honor XSETTINGS; pin the same matched DPI the server advertises.
        put("QT_FONT_DPI", sessionDpi.toString())
    }

    override fun run(argv: String) {
        val process = startGuest(argv, tag = "linux-app") ?: run {
            Timber.w("[LinuxAndroidXSession]: could not launch %s on slot %d", argv, display)
            return
        }
        synchronized(apps) { apps += process }
        Thread({
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            Timber.i("[LinuxAndroidXSession]: app exited status=%d slot=%d", code, display)
            val remaining = synchronized(apps) {
                apps.remove(process)
                apps.count { it.isAlive }
            }
            if (mode == LinuxDesktopConfig.Mode.APP && remaining == 0 && isRunning) {
                viewCallback?.let { cb ->
                    Handler(Looper.getMainLooper()).post { cb(null) }
                }
            }
        }, "linux-app-wait-$display").apply { isDaemon = true }.start()
    }

    override fun createView(context: Context, onClosed: (Throwable?) -> Unit): View {
        val server = xServer ?: throw IllegalStateException("X server not started")
        viewCallback = onClosed
        return LinuxXServerView(context, server)
    }

    override fun releaseView(view: View) {
        (view as? LinuxXServerView)?.release()
        viewCallback = null
    }

    override fun setDpi(densityDpi: Int) {
        val dpi = LinuxDisplayScale.xdpi(densityDpi)
        sessionDpi = dpi
        writeXresources(dpi)
        LinuxProgramLauncher.exec(
            context = context,
            argv = "/usr/bin/pkill -HUP xsettingsd",
            extraEnv = graphicalEnv(),
            displaySocketDir = displaySocketDir,
            extraBinds = LinuxProgramLauncher.graphicalBinds(),
        )
        Timber.i("[LinuxAndroidXSession]: dpi set to %d", dpi)
    }

    override fun stop() {
        isRunning = false
        viewCallback = null

        val held = synchronized(apps) { apps.toList().also { apps.clear() } }
        val labelled = held.map { it to "application" } +
            listOf(
                fitter to "fit-windows",
                panel to "tint2",
                settings to "xsettingsd",
                wm to "openbox",
            ).mapNotNull { (process, label) -> process?.let { it to label } }

        for ((process, label) in labelled) {
            val pid = LinuxProgramLauncher.pidOf(process)
            if (pid > 0) {
                GuestProcesses.end(process, pid, "$label on Android X slot $display")
            } else {
                Timber.w("[LinuxAndroidXSession]: no pid for %s, killing handle only", label)
                process.destroyForcibly()
            }
        }

        fitter = null
        panel = null
        settings = null
        wm = null

        runCatching { xServerComponent?.stop() }
        runCatching { sysVComponent?.stop() }
        xServerComponent = null
        sysVComponent = null
        xServer = null

        clearStaleSockets()
        releaseDisplay(display)
        LinuxPulse.release(context)
        Timber.i("[LinuxAndroidXSession]: slot %d stopped", display)
    }
}

private fun isUnixSocketLive(socket: File): Boolean = runCatching {
    val address = LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)
    LocalSocket().use { local ->
        local.connect(address)
        true
    }
}.getOrDefault(false)
