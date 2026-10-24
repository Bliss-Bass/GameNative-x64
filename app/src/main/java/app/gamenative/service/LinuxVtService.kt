package app.gamenative.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import app.gamenative.linux.GuestProcesses
import app.gamenative.linux.LinuxDistroCatalog
import app.gamenative.linux.LinuxDistros
import app.gamenative.linux.LinuxProgramLauncher
import app.gamenative.linux.LinuxVtConsoles
import app.gamenative.platform.PlatformCapabilities
import java.io.File
import timber.log.Timber

/**
 * Login shells from the Linux userland on Ctrl+Alt+F4 to F6 (Bass OS only).
 *
 * Like getty: nothing runs on a console until it is first shown, the shell stays when the user
 * switches away, and one that exits while its console is showing is started again. The active VT
 * comes from `libvtexec.so --watch`, since apps may not read sysfs; shells start through
 * `libvtexec.so /dev/ttyN`, which makes them session leaders with the console as their terminal.
 *
 * Foreground for as long as it runs: a shell is a child of this process, and a console that went
 * blank because Android reclaimed an idle app would be worse than a notification.
 */
class LinuxVtService : Service() {

    private val notifications by lazy { NotificationHelper(applicationContext) }

    private val shells = HashMap<Int, Process>()
    private var watcher: Process? = null

    @Volatile
    private var running = false

    @Volatile
    private var activeVt = -1

    @Volatile
    private var watchedVt = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithType()

        val inUse = consolesInUse()
        if (!PlatformCapabilities.refresh(this).linuxVt || LinuxDistros.integrated(this).isEmpty() || inUse.isEmpty()) {
            Timber.i("[LinuxVtService]: no consoles to serve here, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        allocate(inUse)
        if (!running) {
            running = true
            startWatcher()
        } else if (intent?.action == ACTION_RELOAD) {
            reload()
            // The watcher holds its console open; move it off one that was just turned off.
            if (watchedVt !in inUse) watcher?.destroy()
        }
        // Sticky: the consoles should come back if Android kills the process.
        return START_STICKY
    }

    private fun consolesInUse(): List<Int> = LinuxVtConsoles.CONSOLES.filter { LinuxVtConsoles.get(this, it) != null }

    /**
     * Opens each console once so the kernel allocates it. Ctrl+Alt+Fn ignores a VT nothing has
     * opened yet, and a shell is only started once its console is showing; consoles set to Off are
     * left unallocated. O_NOCTTY: an open tty must not become this process's controlling terminal.
     */
    private fun allocate(consoles: List<Int>) {
        for (vt in consoles) {
            runCatching { Os.close(Os.open("/dev/tty$vt", OsConstants.O_RDONLY or OsConstants.O_NOCTTY or OsConstants.O_CLOEXEC, 0)) }
                .onFailure { Timber.w(it, "[LinuxVtService]: could not allocate tty%d", vt) }
        }
    }

    private fun vtExec(): String = File(applicationInfo.nativeLibraryDir, VT_EXEC).absolutePath

    private fun startWatcher() {
        // Any console reports the active VT; one in use, so watching does not allocate an unused one.
        val vt = consolesInUse().firstOrNull() ?: return
        val process = runCatching {
            ProcessBuilder(vtExec(), "--watch", "/dev/tty$vt").redirectErrorStream(true).start()
        }.onFailure { Timber.e(it, "[LinuxVtService]: cannot watch the active console") }.getOrNull() ?: return
        watcher = process
        watchedVt = vt

        Thread({
            process.inputStream.bufferedReader().forEachLine { line ->
                val vt = line.trim().toIntOrNull()
                if (vt == null) {
                    Timber.w("[LinuxVtService]: watcher: %s", line)
                    return@forEachLine
                }
                activeVt = vt
                if (vt in LinuxVtConsoles.CONSOLES) ensureShell(vt)
            }
            if (running) {
                Timber.w("[LinuxVtService]: console watcher exited (%s), restarting", process.waitFor())
                Thread.sleep(RESPAWN_DELAY_MS)
                if (running) startWatcher()
            }
        }, "linux-vt-watch").apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun ensureShell(vt: Int) {
        if (!running || shells[vt]?.isAlive == true) return
        val assignment = LinuxVtConsoles.get(this, vt) ?: return
        val rootfs = rootfsFor(assignment.distroId)
        if (rootfs == null) {
            Timber.w("[LinuxVtService]: tty%d wants %s, which is not installed", vt, assignment.distroId)
            return
        }

        val shell = LinuxVtConsoles.shellFor(rootfs, assignment)
        val argv = LinuxProgramLauncher.buildArgv(
            this,
            LinuxVtConsoles.guestCommand(assignment),
            extraEnv = LinuxVtConsoles.guestEnv(vt, LinuxDistroCatalog.nameOf(assignment.distroId), shell, assignment),
            // A text console has no X server to point DISPLAY at.
            displaySocketDir = null,
            rootfs = rootfs,
        )
        val builder = ProcessBuilder(listOf(vtExec(), "/dev/tty$vt") + argv)
            .directory(rootfs)
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            LinuxProgramLauncher.prootEnv(this@LinuxVtService, rootfs).toStringArray().forEach { entry ->
                val split = entry.indexOf('=')
                if (split > 0) put(entry.substring(0, split), entry.substring(split + 1))
            }
        }

        val process = runCatching { builder.start() }
            .onFailure { Timber.e(it, "[LinuxVtService]: could not start tty%d", vt) }
            .getOrNull() ?: return
        shells[vt] = process
        Timber.i("[LinuxVtService]: tty%d: %s %s as %s", vt, assignment.distroId, shell, assignment.user)

        Thread({
            // Only libvtexec's own errors arrive here: the shell's output goes to the console.
            process.inputStream.bufferedReader().forEachLine { Timber.w("[LinuxVtService]: tty%d: %s", vt, it) }
            val status = process.waitFor()
            Timber.i("[LinuxVtService]: tty%d shell exited (%d)", vt, status)
            synchronized(this) { if (shells[vt] === process) shells.remove(vt) }
            // Like getty, a console left showing an exited shell gets a new one.
            Thread.sleep(RESPAWN_DELAY_MS)
            if (running && activeVt == vt) ensureShell(vt)
        }, "linux-vt-tty$vt").apply { isDaemon = true }.start()
    }

    private fun rootfsFor(distroId: String): File? =
        LinuxDistros.installedRootfs(this, distroId)?.takeIf { LinuxDistros.isIntegrated(this, distroId) }

    /**
     * Ends every console's shell so each starts with its current assignment the next time it is
     * shown. The one on screen comes straight back, through the same respawn as a shell that exited.
     */
    @Synchronized
    private fun reload() {
        for ((vt, process) in shells.toList()) {
            shells.remove(vt)
            val pid = LinuxProgramLauncher.pidOf(process)
            if (pid > 0) GuestProcesses.end(process, pid, "Linux console tty$vt") else process.destroyForcibly()
        }
    }

    private fun startForegroundWithType() {
        val notification = notifications.createLinuxVtNotification()
        // specialUse: a dataSync service is stopped after six hours, and consoles stay up. Older
        // releases have no specialUse, so they get the other type the manifest declares.
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        if (type == 0) {
            startForeground(NotificationHelper.NOTIFICATION_ID_LINUX_VT, notification)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_LINUX_VT, notification, type)
        }
    }

    override fun onDestroy() {
        running = false
        watcher?.destroy()
        watcher = null
        synchronized(this) {
            for ((vt, process) in shells) {
                val pid = LinuxProgramLauncher.pidOf(process)
                if (pid > 0) GuestProcesses.end(process, pid, "Linux console tty$vt") else process.destroyForcibly()
            }
            shells.clear()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val VT_EXEC = "libvtexec.so"

        private const val RESPAWN_DELAY_MS = 1_000L

        private const val ACTION_RELOAD = "app.gamenative.action.RELOAD_LINUX_VT"

        /**
         * Starts the consoles when this device provides them and a distro is installed. Safe to
         * call anywhere: it is a no-op on other devices, and a refused background start only logs.
         */
        fun startIfSupported(context: Context) = start(context, null)

        /** Restarts the consoles' shells with their current assignments, after the user changed them. */
        fun reloadIfSupported(context: Context) = start(context, ACTION_RELOAD)

        private fun start(context: Context, action: String?) {
            val app = context.applicationContext
            if (!PlatformCapabilities.refresh(app).linuxVt || LinuxDistros.integrated(app).isEmpty()) return
            runCatching { app.startForegroundService(Intent(app, LinuxVtService::class.java).setAction(action)) }
                .onFailure { Timber.w(it, "[LinuxVtService]: could not start") }
        }
    }
}
