package app.gamenative.linux.services

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import app.gamenative.R
import app.gamenative.linux.LinuxRootfs
import app.gamenative.service.NotificationHelper
import app.gamenative.ui.LinuxSessionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/**
 * The Linux Services addon's way in: a binder for [ILinuxJobEngine], and the foreground service
 * that keeps the process running -- and unfrozen -- while any job is up.
 *
 * Bound, not started, by the addon, which keeps the binding for as long as it runs. The service
 * promotes itself to the foreground only while [JobSupervisor.active] or while
 * [EnvironmentInstaller] is downloading the environment: a job's processes are this
 * app's children, and Android freezes a cached app's children along with it, so a job left running
 * behind a background app would stop dead without any exit to restart it from.
 *
 * Without the addon nothing binds, nothing starts, and this does nothing at all.
 */
class LinuxJobService : Service() {

    private val notifications by lazy { NotificationHelper(applicationContext) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val callbacks = RemoteCallbackList<ILinuxJobCallback>()

    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        JobSupervisor.init(this)
        JobSupervisor.listener = object : JobSupervisor.Listener {
            override fun onState(id: String, state: JSONObject) {
                broadcast { it.onJobState(id, state.toString()) }
                if (foreground) notifications.notifyLinuxJobs(summary())
            }

            override fun onLaunchRequest(id: String, launch: PendingIntent) {
                broadcast { it.onLaunchRequest(id, launch) }
            }
        }
        EnvironmentInstaller.listener = object : EnvironmentInstaller.Listener {
            override fun onEnvironment(state: JSONObject) {
                broadcast { it.onEnvironment(state.toString()) }
                if (foreground) notifications.notifyLinuxJobs(summary())
            }
        }

        scope.launch {
            busy.collect { active ->
                if (active) {
                    // Legal from the background only because the addon puts this app on the
                    // power allowlist; without it the start is refused and logged here.
                    runCatching { startForegroundService(Intent(this@LinuxJobService, LinuxJobService::class.java)) }
                        .onFailure { Timber.e(it, "[LinuxJobService]: could not enter the foreground") }
                } else if (foreground) {
                    leaveForeground()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The foreground-service contract: post within seconds of the start, even if the jobs
        // went idle in between, and step down again straight after if they did.
        enterForeground()
        if (!busy.value) leaveForeground()
        // The jobs die with the process, and the addon rebinds and restarts them when it notices;
        // a sticky restart would only post a notification for jobs that no longer exist.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        JobSupervisor.listener = null
        EnvironmentInstaller.listener = null
        // Destroyed only once unbound and idle, or with the process; a job still up here has lost
        // its supervisor, so end it rather than leave it running untracked.
        JobSupervisor.stopAll()
        callbacks.kill()
        scope.cancel()
        if (foreground) leaveForeground()
        super.onDestroy()
    }

    private fun enterForeground() {
        val notification = notifications.createLinuxJobsNotification(summary())
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        if (type == 0) {
            startForeground(NotificationHelper.NOTIFICATION_ID_LINUX_JOBS, notification)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_LINUX_JOBS, notification, type)
        }
        notifications.markActive(NotificationHelper.NOTIFICATION_ID_LINUX_JOBS)
        foreground = true
    }

    private fun leaveForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NotificationHelper.NOTIFICATION_ID_LINUX_JOBS)
        foreground = false
        // Stays alive while the addon is bound; this only ends the started state.
        stopSelf()
    }

    private fun summary(): String {
        if (EnvironmentInstaller.installing.value) return getString(R.string.linux_environment_installing)
        val jobs = JobSupervisor.status()
        val running = (0 until jobs.length()).count { jobs.getJSONObject(it).getString("state") == "running" }
        return getString(R.string.linux_jobs_running, running, jobs.length())
    }

    private fun broadcast(action: (ILinuxJobCallback) -> Unit) = synchronized(callbacks) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) runCatching { action(callbacks.getBroadcastItem(i)) }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private val binder = object : ILinuxJobEngine.Stub() {

        private fun enforce() {
            if (!LinuxJobCallers.isAllowed(this@LinuxJobService, Binder.getCallingUid())) {
                throw SecurityException("Only the Linux Services system addon may use the job engine")
            }
        }

        /** Runs [block] as this app rather than the caller, since it reads and writes our own state. */
        private inline fun <T> asSelf(block: () -> T): T {
            enforce()
            val token = Binder.clearCallingIdentity()
            try {
                return block()
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }

        override fun apiVersion(): Int = asSelf { API_VERSION }

        override fun syncJobs(jobsJson: String?): String? = asSelf {
            runCatching { JobSupervisor.sync(LinuxJob.parseAll(jobsJson ?: "[]")) }
                .exceptionOrNull()
                ?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?.also { Timber.w("[LinuxJobService]: refused sync: %s", it) }
        }

        override fun start(id: String?): Boolean = asSelf { id != null && JobSupervisor.start(id) }

        override fun stop(id: String?): Boolean = asSelf { id != null && JobSupervisor.stop(id) }

        override fun status(): String = asSelf { JobSupervisor.status().toString() }

        override fun openLog(id: String?): ParcelFileDescriptor? = asSelf {
            id?.let(JobSupervisor::logFile)?.let {
                ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        override fun guiLaunchIntent(id: String?): PendingIntent? = asSelf { id?.let(JobSupervisor::launchIntent) }

        override fun inspect(): String = asSelf { JobSupervisor.inspect().toString() }

        override fun endProcess(pid: Int): Boolean = asSelf { ProcessInspector.end(pid) }

        override fun environment(): String = asSelf { EnvironmentInstaller.state(this@LinuxJobService).toString() }

        override fun installEnvironment(): Boolean = asSelf { EnvironmentInstaller.install(this@LinuxJobService) }

        override fun terminalIntent(): PendingIntent? = asSelf {
            if (!LinuxRootfs.isInstalled(this@LinuxJobService) || !LinuxRootfs.hasDisplaySession(this@LinuxJobService)) {
                null
            } else {
                // One terminal document: the launcher entry brings it back rather than stacking more.
                PendingIntent.getActivity(
                    this@LinuxJobService,
                    TERMINAL_REQUEST_CODE,
                    LinuxSessionActivity.intent(this@LinuxJobService, "xterm", TERMINAL_SESSION, getString(R.string.linux_terminal_label)),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        }

        override fun registerCallback(callback: ILinuxJobCallback?) {
            enforce()
            callback?.let { synchronized(callbacks) { callbacks.register(it) } }
        }

        override fun unregisterCallback(callback: ILinuxJobCallback?) {
            enforce()
            callback?.let { synchronized(callbacks) { callbacks.unregister(it) } }
        }
    }

    companion object {
        const val API_VERSION = 2

        private const val TERMINAL_SESSION = "linux-terminal"
        private const val TERMINAL_REQUEST_CODE = 0x7e57

        /** Jobs to keep running, or an environment install under way: either needs the foreground. */
        private val busy: StateFlow<Boolean> =
            combine(JobSupervisor.active, EnvironmentInstaller.installing) { jobs, installing -> jobs || installing }
                .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, false)
    }
}
