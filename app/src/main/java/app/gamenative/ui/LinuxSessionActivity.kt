package app.gamenative.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.net.toUri
import android.graphics.Color.TRANSPARENT
import app.gamenative.PrefManager
import app.gamenative.enums.AppTheme
import app.gamenative.linux.LinuxSessions
import app.gamenative.linux.rfb.RfbView
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
 * side. The session behind it is [LinuxSessions]', so closing this window does not necessarily
 * end the application -- that is what the lifetime mode decides.
 *
 * `documentLaunchMode="intoExisting"` in the manifest, plus a per-application [Uri] as the
 * intent's data, is what makes the task-per-application split happen: Android treats distinct
 * data as distinct documents, and relaunching the same one brings its window forward.
 */
class LinuxSessionActivity : ComponentActivity() {

    override fun onCreate(state: Bundle?) {
        // Match MainActivity: edge-to-edge so freeform captionBar / SmartDock navigationBars
        // reach Compose (LinuxDesktopScreen pads with WindowInsets.safeDrawing).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(TRANSPARENT),
        )
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

    /**
     * Compose's hierarchy does not always deliver mouse-wheel / touchpad ACTION_SCROLL to a
     * nested [app.gamenative.linux.rfb.RfbView]. Forwarding from the activity keeps Android's
     * scroll gestures reaching the RFB session the same way they reach the Wine touchpad view.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val focus = currentFocus
        if (focus is RfbView && focus.onGenericMotionEvent(event)) return true
        // Walk children: SurfaceView focus can be flaky under Compose AndroidView.
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        if (content != null && dispatchGenericMotionToRfb(content, event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private fun dispatchGenericMotionToRfb(view: android.view.View, event: MotionEvent): Boolean {
        if (view is RfbView) return view.onGenericMotionEvent(event)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (dispatchGenericMotionToRfb(view.getChildAt(i), event)) return true
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
