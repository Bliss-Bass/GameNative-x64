package app.gamenative.linux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import timber.log.Timber

/**
 * What the installer reports the outcome of a stub install to.
 *
 * A session does not complete on its own when the caller is unprivileged: the installer asks for
 * the user's confirmation and hands back the intent that shows it, which has to be started from
 * here. With INSTALL_PACKAGES held -- as the ROM's helper will -- that step never arrives and the
 * session goes straight to a result.
 */
class StubInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Timber.e("[StubInstall]: asked for confirmation without an intent to show")
                    return
                }
                // Started from a receiver, so it needs its own task.
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Timber.i("[StubInstall]: installed %s", packageName)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // The user declined, which needs no message of ours.
                Timber.i("[StubInstall]: install of %s was cancelled", packageName)
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Timber.e("[StubInstall]: install of %s failed (%d): %s", packageName, status, message)
                SnackbarManager.show(context.getString(R.string.linux_apps_drawer_install_failed))
            }
        }
    }
}
