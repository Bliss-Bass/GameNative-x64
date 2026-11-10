package app.gamenative.linux

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import app.gamenative.MainActivity
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.LinuxSessionActivity
import app.gamenative.ui.screen.PluviaScreen
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The intents that open Linux screens and sessions, and how MainActivity and the navigation graph
 * act on them. Kept out of MainActivity and PluviaMain, which upstream also changes.
 */
object LinuxIntents {
    /** Opens the Linux desktop, with an optional [EXTRA_LINUX_ARGV] command to run in it. */
    const val ACTION_LINUX_DESKTOP = "app.gamenative.action.LINUX_DESKTOP"
    const val EXTRA_LINUX_ARGV = "linux_argv"

    /**
     * Which desktop entry the command came from, when the caller knows.
     *
     * What a session is keyed on, so launching an application that is already running finds
     * it instead of starting a second copy, and what Android files the window's task under.
     * Optional: a stub built before this was carried supplies only a command.
     */
    const val EXTRA_LINUX_ENTRY_ID = "linux_entry_id"

    /** The application's name, for the window and the running-sessions notification. */
    const val EXTRA_LINUX_LABEL = "linux_label"

    /** Opens the Linux terminal, which is also where the userland is installed from. */
    const val ACTION_LINUX_TERMINAL = "app.gamenative.action.LINUX_TERMINAL"

    /** Opens the Linux Distros screen. */
    const val ACTION_LINUX_DISTROS = "app.gamenative.action.LINUX_DISTROS"

    /** Opens the Linux Apps screen. */
    const val ACTION_LINUX_APPS = "app.gamenative.action.LINUX_APPS"

    /** For [ACTION_LINUX_TERMINAL]: the installed distro to open in, instead of the default. */
    const val EXTRA_LINUX_DISTRO = "linux_distro"

    enum class LinuxScreen { DESKTOP, TERMINAL, DISTROS, APPS }

    /** A Linux screen asked for by intent, held until the UI can act on it. */
    data class PendingLinuxRequest(val screen: LinuxScreen, val argv: String?, val distroId: String? = null)

    @Volatile
    private var pendingLinuxRequest: PendingLinuxRequest? = null

    fun setPendingLinuxRequest(request: PendingLinuxRequest) {
        synchronized(this) { pendingLinuxRequest = request }
    }

    fun consumePendingLinuxRequest(): PendingLinuxRequest? = synchronized(this) {
        pendingLinuxRequest.also { pendingLinuxRequest = null }
    }

    /** Acts on a Linux intent delivered to [activity]. False when [intent] is not one. */
    fun <A> handle(activity: A, intent: Intent): Boolean where A : Activity, A : LifecycleOwner {
        if (intent.action != ACTION_LINUX_DESKTOP && intent.action != ACTION_LINUX_TERMINAL &&
            intent.action != ACTION_LINUX_DISTROS && intent.action != ACTION_LINUX_APPS
        ) {
            return false
        }
        // Not a game launch, so these skip IntentLaunchManager entirely. The desktop
        // action is also the entry point a per-app launcher shortcut uses.
        val screen = when (intent.action) {
            ACTION_LINUX_TERMINAL -> LinuxScreen.TERMINAL
            ACTION_LINUX_DISTROS -> LinuxScreen.DISTROS
            ACTION_LINUX_APPS -> LinuxScreen.APPS
            else -> LinuxScreen.DESKTOP
        }
        val terminal = screen != LinuxScreen.DESKTOP
        val distroId = intent.getStringExtra(EXTRA_LINUX_DISTRO)
        activity.intent = Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        val argv = intent.getStringExtra(EXTRA_LINUX_ARGV)
        val entryId = intent.getStringExtra(EXTRA_LINUX_ENTRY_ID)
        val label = intent.getStringExtra(EXTRA_LINUX_LABEL)
        Timber.i("[IntentLaunch]: Linux %s requested (argv=%s, distro=%s)", screen.name.lowercase(), argv, distroId)

        // An application gets a window of its own, so Android sizes and remembers it per
        // application rather than reusing whatever bounds this activity happens to have. The
        // in-app route below stays as the fallback: an older stub or an unusual device that
        // cannot start the session activity should still open the app, in here, rather than
        // fail. A bare desktop request is a screen of this app and stays put.
        if (!terminal && !argv.isNullOrBlank()) {
            val opened = runCatching {
                LinuxSessionActivity.open(activity, argv, entryId, label)
            }.onFailure { Timber.w(it, "[IntentLaunch]: no session window, falling back in-app") }

            if (opened.isSuccess) {
                // Keep Main alive for drawer / Steam / FGS, but do not leave a second freeform
                // window titled GameNativeX64 sitting beside the Linux session.
                activity.moveTaskToBack(true)
                return true
            }
        }

        // Always recorded, and the event only says one is waiting. The alternative --
        // carrying the request on the event and recording it only for a cold start -- looks
        // like it covers both cases and does not: the event bus does not replay, and whether
        // anything is collecting yet does not follow from this being a new intent. A new
        // intent can arrive while the activity is still being created, which is what a
        // launcher shortcut does when its own task is already in recents, and the request
        // was then dropped. Recording it first means the request survives either way, and is
        // picked up by whichever of the two runs.
        setPendingLinuxRequest(PendingLinuxRequest(screen, argv, distroId))
        activity.lifecycleScope.launch { PluviaApp.events.emit(AndroidEvent.LinuxRequestPending) }
        return true
    }

    /**
     * Opens whichever Linux screen was asked for by intent, if one was.
     *
     * Called both when the library first composes and when the event says a request arrived,
     * because an intent can land on either side of the UI being ready. Consuming the request is
     * what keeps the two from acting on it twice.
     */
    fun navigateToPendingRequest(nav: NavHostController) {
        val request = consumePendingLinuxRequest() ?: return
        Timber.i("[PluviaMain]: Opening Linux %s", request.screen.name.lowercase())
        when (request.screen) {
            LinuxScreen.TERMINAL -> {
                nav.navigate(PluviaScreen.Terminal.route(request.distroId))
                return
            }
            LinuxScreen.DISTROS -> {
                nav.navigate(PluviaScreen.LinuxDistros.route)
                return
            }
            LinuxScreen.APPS -> {
                nav.navigate(PluviaScreen.LinuxApps.route)
                return
            }
            LinuxScreen.DESKTOP -> Unit
        }

        // A session launched from an app-drawer entry has only the graph's start destination beneath
        // it, so back leaves the app the user was just in and lands on the library -- or, before a
        // Steam login, on the sign-in screen, which has nothing to do with what they opened. The list
        // the entry was made from is the screen "back" means here, so put it underneath first.
        //
        // Only for a launch that names a program. A bare desktop request is the desktop itself rather
        // than something reached from the list, and the library is its parent.
        if (request.argv != null && nav.currentDestination?.route != PluviaScreen.LinuxApps.route) {
            nav.navigate(PluviaScreen.LinuxApps.route)
        }
        nav.navigate(PluviaScreen.LinuxDesktop.route(request.argv))
    }
}
