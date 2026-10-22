package app.gamenative.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the Linux consoles (Ctrl+Alt+F4 to F6) up after boot and after an update, so they work
 * without opening the app first. Does nothing on devices that do not provide them.
 */
class LinuxVtBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                LinuxVtService.startIfSupported(context)
        }
    }
}
