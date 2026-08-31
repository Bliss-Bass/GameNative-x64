package app.gamenative.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.net.toUri
import app.gamenative.PrefManager
import app.gamenative.enums.AppTheme
import app.gamenative.linux.LinuxSessions
import app.gamenative.ui.screen.linux.LinuxDesktopScreen
import app.gamenative.ui.theme.PluviaTheme
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

        setContent {
            val theme = PrefManager.appTheme
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
