package app.gamenative.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.gamenative.linux.LinuxSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the process alive for as long as a Linux session is running, and says so in the shade.
 *
 * A session is not a service's worth of logic -- [LinuxSessions] owns the sessions and this owns
 * nothing -- but it is a claim on the device: an X server, a window manager and an application,
 * all children of this process. Android is entitled to reclaim a process with no component
 * running, and a user is entitled to know why their battery is going somewhere.
 *
 * The type is `specialUse` rather than the `dataSync` every other service here uses. `dataSync`
 * is time-limited -- six hours, then `onTimeout` and a stop -- which is correct for a download
 * and wrong for a session the user asked to keep. Nothing narrower fits: this is not media, not
 * a transfer, and not location.
 */
class LinuxSessionService : Service() {

    private val notifications by lazy { NotificationHelper(applicationContext) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Before any other work, as SteamService does: the contract is that a service started
        // into the foreground posts its notification within a few seconds or the app is killed.
        startForegroundWithType(notifications.createLinuxSessionNotification(summary()))
        notifications.markActive(NotificationHelper.NOTIFICATION_ID_LINUX)

        watchSessions()

        // Not sticky. Restarting this service after the process died would post a notification
        // for sessions that died with it, since the sessions live in the process, not here.
        return START_NOT_STICKY
    }

    /**
     * Follows the session list so the notification stays true, and stops the service once the
     * last session has gone.
     */
    private fun watchSessions() = scope.launch {
        // The current value is already on the notification posted above.
        LinuxSessions.sessions.drop(1).collect { sessions ->
            if (sessions.isEmpty()) {
                Timber.i("[LinuxSessionService]: no sessions left, stopping")
                stopSelf()
            } else {
                notifications.notifyLinuxSessions(summary())
            }
        }
    }

    private fun summary(): String {
        val sessions = LinuxSessions.sessions.value
        return when (sessions.size) {
            0 -> getString(app.gamenative.R.string.linux_session_none)
            1 -> sessions.first().label
            else -> getString(app.gamenative.R.string.linux_session_count, sessions.size)
        }
    }

    private fun startForegroundWithType(notification: android.app.Notification) {
        val type = when {
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            // specialUse did not exist before 34, and a type must still be one the manifest
            // declares, so the legacy flavours fall back to what they already ship with.
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }

        if (type == 0) {
            startForeground(NotificationHelper.NOTIFICATION_ID_LINUX, notification)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_LINUX, notification, type)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NotificationHelper.NOTIFICATION_ID_LINUX)
        super.onDestroy()
    }

    /**
     * Not bound. The sessions are reachable directly from [LinuxSessions] within this process, and
     * a binder would only make every caller wait for a connection to reach state it already has.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        /**
         * Starts the service if it is not already up.
         *
         * Must be called while something of ours is on screen, which is where the sessions are
         * started from: starting a foreground service from the background is refused.
         */
        fun ensureRunning(context: Context) {
            val intent = Intent(context.applicationContext, LinuxSessionService::class.java)
            runCatching { context.applicationContext.startForegroundService(intent) }
                .onFailure { Timber.w(it, "[LinuxSessionService]: could not start") }
        }

        /** Stops the service when nothing is left for it to hold. */
        fun stopIfIdle(context: Context) {
            if (LinuxSessions.count() > 0) return
            val intent = Intent(context.applicationContext, LinuxSessionService::class.java)
            runCatching { context.applicationContext.stopService(intent) }
                .onFailure { Timber.w(it, "[LinuxSessionService]: could not stop") }
        }
    }
}
