package app.gamenative.ui.screen.linux

import android.content.Context
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.gamenative.ui.LinuxSessionActivity
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.screen.terminal.TerminalScreen
import timber.log.Timber

/** The Linux screens' routes, kept out of PluviaMain's graph, which upstream also changes. */
fun NavGraphBuilder.linuxScreens(navController: NavHostController, context: Context) {
    /** Linux applications **/
    composable(route = PluviaScreen.LinuxApps.route) {
        LinuxAppsScreen(
            onBack = { navController.navigateUp() },
            onOpenTerminal = { navController.navigate(PluviaScreen.Terminal.route()) },
            // Its own window rather than a screen in this one, so Android sizes and
            // remembers each application separately and two can be open at once. The
            // in-app route below remains for the bare desktop, and as the fallback if
            // the window cannot be opened at all.
            onLaunch = { app ->
                val opened = runCatching {
                    LinuxSessionActivity.open(context, app.launchArgv, app.entryId, app.name)
                }.onFailure { Timber.w(it, "[PluviaMain]: no session window for %s", app.name) }

                if (opened.isSuccess) {
                    // Hide the Linux Apps / Main freeform window so only the session
                    // document is visible; Main stays in recents for reopen.
                    (context as? android.app.Activity)?.moveTaskToBack(true)
                } else {
                    navController.navigate(PluviaScreen.LinuxDesktop.route(app.launchArgv))
                }
            },
        )
    }

    /** Linux distros, desktop sessions and consoles **/
    composable(route = PluviaScreen.LinuxDistros.route) {
        LinuxDistrosScreen(onBack = { navController.navigateUp() })
    }

    /** Linux terminal **/
    composable(
        route = PluviaScreen.Terminal.route,
        arguments = listOf(
            navArgument(PluviaScreen.Terminal.ARG_DISTRO) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        // Guarded because the shell dies when this screen leaves composition,
        // and the resulting callback would otherwise pop whatever replaced it.
        TerminalScreen(
            initialDistroId = entry.arguments?.getString(PluviaScreen.Terminal.ARG_DISTRO),
            onBack = { navController.navigateUpFrom(entry) },
        )
    }

    /** Linux graphical session **/
    composable(
        route = PluviaScreen.LinuxDesktop.route,
        arguments = listOf(
            navArgument(PluviaScreen.LinuxDesktop.ARG_ARGV) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        LinuxDesktopScreen(
            argv = entry.arguments?.getString(PluviaScreen.LinuxDesktop.ARG_ARGV),
            onExit = { navController.navigateUpFrom(entry) },
        )
    }
}

/**
 * Goes back only if [entry] is still the screen on top.
 *
 * A screen that tears something down when it leaves composition reports that teardown
 * through its exit callback, which arrives after whatever replaced it is already showing.
 * An unguarded [navigateUp] would then pop the new screen instead.
 */
private fun NavHostController.navigateUpFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) {
        navigateUp()
    } else {
        Timber.d("[PluviaMain]: ignoring exit from %s, no longer on top", entry.destination.route)
    }
}
