package app.gamenative.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import app.gamenative.PrefManager
import app.gamenative.enums.AppTheme
import app.gamenative.linux.LinuxXServerView
import app.gamenative.ui.screen.linux.LinuxDesktopScreen
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.AppUiScale
import app.gamenative.ui.util.ProvideAppUiScale
import timber.log.Timber

/**
 * One Linux application, in a window of its own.
 *
 * Separate from MainActivity so that Android, not us, decides how each application is sized:
 * with a task per application, a freeform window keeps its own bounds, a drawer launch reopens
 * the window it had rather than the last one anything used, and two Linux apps can sit side by
 * side. The session behind it is [app.gamenative.linux.LinuxSessions]', so closing this window
 * does not necessarily end the application -- that is what the lifetime mode decides.
 *
 * `documentLaunchMode="intoExisting"` in the manifest, plus a per-application [android.net.Uri]
 * as the intent's data, is what makes the task-per-application split happen: Android treats
 * distinct data as distinct documents, and relaunching the same one brings its window forward.
 */
class LinuxSessionActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        // Edge-to-edge: freeform captionBar / status / nav are applied in Compose via
        // [pluviaSafeDrawingPadding] so the X surface sizes to the true client area.
        // (decorFits alone does not clear SmartDock-DFC's captionBar overlay.)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(state)

        val argv = intent.getStringExtra(EXTRA_ARGV)
        val entryId = intent.data?.lastPathSegment
        val label = intent.getStringExtra(EXTRA_LABEL)

        if (argv.isNullOrBlank()) {
            // Nothing to run. Reachable only from a malformed intent, and there is no window
            // worth showing for it.
            Timber.w("[LinuxSessionActivity]: no command in %s", intent)
            finish()
            return
        }

        Timber.i("[LinuxSessionActivity]: opening %s (%s)", label ?: entryId ?: "linux app", argv)
        applySessionCaption(label, entryId)

        AppUiScale.syncFromPrefs()
        setContent {
            val theme = PrefManager.appTheme
            ProvideAppUiScale {
                PluviaTheme(
                    isDark = when (theme) {
                        AppTheme.AUTO -> isSystemInDarkTheme()
                        AppTheme.DAY -> false
                        AppTheme.NIGHT, AppTheme.AMOLED -> true
                    },
                    isAmoled = theme == AppTheme.AMOLED,
                    style = PrefManager.appThemePalette,
                ) {
                    LinuxDesktopScreen(
                        argv = argv,
                        entryId = entryId,
                        label = label,
                        // The session's own window: when the last thing in it exits there is nothing
                        // left to show, so the window goes too.
                        onExit = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applySessionCaption(
            intent.getStringExtra(EXTRA_LABEL),
            intent.data?.lastPathSegment,
        )
    }

    /**
     * Freeform / SmartDock caption uses the task description label (and [title]), not only the
     * static manifest label — so each Linux document shows the app name instead of GameNativeX64.
     */
    private fun applySessionCaption(label: String?, entryId: String?) {
        val caption = label?.takeIf { it.isNotBlank() }
            ?: entryId?.takeIf { it.isNotBlank() && !it.startsWith("argv-") }
            ?: getString(app.gamenative.R.string.linux_session_notification_title)
        title = caption
        window.setTitle(caption)
        setTaskDescription(
            ActivityManager.TaskDescription.Builder()
                .setLabel(caption)
                .build(),
        )
    }

    override fun onResume() {
        super.onResume()
        // Desktop mode sometimes reapplies the application label after first layout; refresh.
        applySessionCaption(
            intent.getStringExtra(EXTRA_LABEL),
            intent.data?.lastPathSegment,
        )
    }

    /**
     * Compose's hierarchy does not always deliver mouse-wheel / touchpad ACTION_SCROLL to a
     * nested [LinuxXServerView]. Forwarding from the activity keeps Android's scroll gestures
     * reaching the session the same way they reach the Wine touchpad view.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val focus = currentFocus
        if (focus is LinuxXServerView && focus.onGenericMotionEvent(event)) return true
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        if (content != null && dispatchGenericMotionToLinuxView(content, event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun dispatchGenericMotionToLinuxView(view: android.view.View, event: MotionEvent): Boolean {
        if (view is LinuxXServerView) return view.onGenericMotionEvent(event)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (dispatchGenericMotionToLinuxView(view.getChildAt(i), event)) return true
            }
        }
        return false
    }

    companion object {

        private const val EXTRA_ARGV = "linux_argv"
        private const val EXTRA_LABEL = "linux_label"

        /**
         * An intent opening [argv]'s own window.
         *
         * [entryId] identifies the application, and becomes both the session key and the document
         * Android files the task under. Without one -- a stub built before entry ids were carried
         * -- the command stands in, so the window is still per-application rather than shared.
         */
        fun intent(context: Context, argv: String, entryId: String?, label: String?): Intent {
            val document = entryId?.takeIf { it.isNotBlank() } ?: "argv-${argv.hashCode()}"

            return Intent(context, LinuxSessionActivity::class.java).apply {
                // Data, not an extra: Android matches documents on it, and two applications
                // sharing one Uri would share one window.
                data = "gamenative://linux/$document".toUri()
                putExtra(EXTRA_ARGV, argv)
                putExtra(EXTRA_LABEL, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
        }
    }
}
