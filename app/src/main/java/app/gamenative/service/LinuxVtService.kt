package app.gamenative.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.gamenative.linux.GuestProcesses
import app.gamenative.linux.LinuxProgramLauncher
import app.gamenative.linux.LinuxRootfs
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithType()

        if (!PlatformCapabilities.refresh(this).linuxVt || !LinuxRootfs.isInstalled(this)) {
            Timber.i("[LinuxVtService]: consoles unavailable here, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            startWatcher()
        }
        // Sticky: the consoles should come back if Android kills the process.
        return START_STICKY
    }

    private fun vtExec(): String = File(applicationInfo.nativeLibraryDir, VT_EXEC).absolutePath

    private fun startWatcher() {
        val process = runCatching {
            ProcessBuilder(vtExec(), "--watch", WATCH_TTY).redirectErrorStream(true).start()
        }.onFailure { Timber.e(it, "[LinuxVtService]: cannot watch the active console") }.getOrNull() ?: return
        watcher = process

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
            extraEnv = LinuxVtConsoles.guestEnv(vt, distroName(assignment.distroId), shell, assignment),
            // A text console has no X server to point DISPLAY at.
            displaySocketDir = null,
        )
        val builder = ProcessBuilder(listOf(vtExec(), "/dev/tty$vt") + argv)
            .directory(rootfs)
            .redirectErrorStream(true)
        builder.environment().apply {
            clear()
            LinuxProgramLauncher.prootEnv(this@LinuxVtService).toStringArray().forEach { entry ->
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

    /** Where [distroId]'s userland lives; only Ubuntu until per-distro installs exist. */
    private fun rootfsFor(distroId: String): File? =
        if (distroId == LinuxVtConsoles.DEFAULT_DISTRO && LinuxRootfs.isInstalled(this)) LinuxRootfs.rootfsDir(this) else null

    private fun distroName(distroId: String): String =
        if (distroId == LinuxVtConsoles.DEFAULT_DISTRO) "Ubuntu" else distroId

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

        /** Any console GameNative owns; opening one does not switch to it. */
        private const val WATCH_TTY = "/dev/tty4"

        private const val RESPAWN_DELAY_MS = 1_000L

        /**
         * Starts the consoles when this device provides them and the userland is installed. Safe to
         * call anywhere: it is a no-op on other devices, and a refused background start only logs.
         */
        fun startIfSupported(context: Context) {
            val app = context.applicationContext
            if (!PlatformCapabilities.refresh(app).linuxVt || !LinuxRootfs.isInstalled(app)) return
            runCatching { app.startForegroundService(Intent(app, LinuxVtService::class.java)) }
                .onFailure { Timber.w(it, "[LinuxVtService]: could not start") }
        }
    }
}
