package app.gamenative.ui.screen

import android.net.Uri

/**
 * Destinations for top level screens, excluding home screen destinations.
 */
sealed class PluviaScreen(val route: String) {
    data object LoginUser : PluviaScreen("login")
    data object Home : PluviaScreen("home")
    data object XServer : PluviaScreen("xserver")
    data object Settings : PluviaScreen("settings")
    data object Terminal : PluviaScreen("terminal")
    data object LinuxDesktop : PluviaScreen("linux-desktop?argv={argv}") {
        /** [argv] is the guest command to run in the session, or null for a bare desktop. */
        fun route(argv: String? = null) =
            if (argv == null) "linux-desktop" else "linux-desktop?argv=${Uri.encode(argv)}"
        const val ARG_ARGV = "argv"
    }
    data object Chat : PluviaScreen("chat/{id}") {
        fun route(id: Long) = "chat/$id"
        const val ARG_ID = "id"
    }
}
