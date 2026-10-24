package app.gamenative.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import app.gamenative.PrefManager
import app.gamenative.enums.AppTheme
import app.gamenative.linux.LinuxAppIds
import app.gamenative.linux.LinuxAppStubs
import app.gamenative.linux.LinuxSessions
import app.gamenative.linux.LinuxXServerView
import app.gamenative.stubs.StubIcons
import app.gamenative.stubs.Stubs
import app.gamenative.ui.screen.linux.LinuxDesktopScreen
import app.gamenative.ui.screen.linux.isLinuxMenuKey
import app.gamenative.ui.screen.linux.toggleLinuxMenu
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.AppUiScale
import app.gamenative.ui.util.ProvideAppUiScale
import kotlinx.coroutines.launch
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
 * [FLAG_ACTIVITY_NEW_DOCUMENT][Intent.FLAG_ACTIVITY_NEW_DOCUMENT] plus a per-application
 * [android.net.Uri] as the intent's data is what makes the task-per-application split happen:
 * Android treats distinct data as distinct documents, and relaunching the same one brings its
 * window forward. An application with an all-apps stub opens through [open] instead, in the
 * stub's task, so the frame and taskbar show the application rather than GameNative.
 */
class LinuxSessionActivity : ComponentActivity() {

    /** Whether the side menu is open; see [dispatchKeyEvent]. */
    private val menuOpen = mutableStateOf(false)

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
                        menuOpen = menuOpen,
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
        val argv = intent.getStringExtra(EXTRA_ARGV)
        val entryId = intent.data?.lastPathSegment
        applySessionCaption(intent.getStringExtra(EXTRA_LABEL), entryId)
        val key = LinuxSessions.keyFor(entryId, argv)
        lifecycleScope.launch { LinuxSessions.deliverToRunning(key, argv) }
    }

    /**
     * Freeform / SmartDock caption uses the task description label + icon (and [title]), not only
     * the static manifest label — so each Linux document shows XTerm / UXTerm (and their stub
     * letter tiles) instead of GameNativeX64.
     */
    private fun applySessionCaption(label: String?, entryId: String?) {
        val stubPkg = entryId
            ?.takeIf { it.isNotBlank() && !it.startsWith("argv-") }
            ?.let { LinuxAppIds.stubPackage(it) }
        val stubInfo = stubPkg?.let { pkg ->
            runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        }
        val caption = label?.takeIf { it.isNotBlank() }
            ?: stubInfo?.let { packageManager.getApplicationLabel(it).toString() }?.takeIf { it.isNotBlank() }
            ?: entryId?.takeIf { it.isNotBlank() && !it.startsWith("argv-") }
            ?: getString(app.gamenative.R.string.linux_session_notification_title)
        val icon = stubInfo?.let { info ->
            runCatching { drawableToBitmap(packageManager.getApplicationIcon(info)) }.getOrNull()
        } ?: StubIcons.lettered(this, caption)

        title = caption
        window.setTitle(caption)
        // Builder.setIcon takes a resource id on current SDK stubs; the label+bitmap
        // constructor is what freeform captions need for stub letter tiles.
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(caption, icon))
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
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
     * Compose can hold activity focus away from [LinuxXServerView]; forward keys the same way
     * we forward scroll so the guest gets keyboard input.
     *
     * Except the menu's keys: Back and Shift+Esc open it (as in a game), and while it is open
     * keys drive the menu. Releases still reach the guest then, so a modifier held when the menu
     * opened does not stay down in X.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isLinuxMenuKey(event, menuOpen.value)) {
            toggleLinuxMenu(menuOpen, event)
            return true
        }
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        if (menuOpen.value) {
            if (event.action == KeyEvent.ACTION_UP && content != null) dispatchKeyToLinuxView(content, event)
            return super.dispatchKeyEvent(event)
        }

        val focus = currentFocus
        if (focus is LinuxXServerView && focus.dispatchKeyEvent(event)) return true
        if (content != null && dispatchKeyToLinuxView(content, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun dispatchKeyToLinuxView(view: android.view.View, event: KeyEvent): Boolean {
        if (view is LinuxXServerView) return view.dispatchKeyEvent(event)
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                if (dispatchKeyToLinuxView(view.getChildAt(i), event)) return true
            }
        }
        return false
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
         * Opens [argv]'s window: through the application's all-apps stub when it has one that
         * roots the window's task, so the frame shows the application, and directly otherwise.
         */
        fun open(context: Context, argv: String, entryId: String?, label: String?) {
            val stub = entryId?.let { stubLaunchIntent(context, it) }
            if (stub != null && runCatching { context.startActivity(stub) }.isSuccess) return
            context.startActivity(
                intent(context, argv, entryId, label).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }

        /**
         * The launch the drawer would make for [entryId]'s stub, so an open window is brought
         * forward rather than a second one started. Null for a stub built before stubs rooted
         * the task: it would hand the launch straight back to MainActivity.
         */
        private fun stubLaunchIntent(context: Context, entryId: String): Intent? {
            val component = ComponentName(LinuxAppIds.stubPackage(entryId), Stubs.TRAMPOLINE_CLASS)
            val format = runCatching {
                context.packageManager.getActivityInfo(component, PackageManager.GET_META_DATA)
                    .metaData?.getString(LinuxAppStubs.META_FORMAT)?.toIntOrNull()
            }.getOrNull() ?: 0
            if (format < LinuxAppStubs.ROOTED_FORMAT) return null
            return Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

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
