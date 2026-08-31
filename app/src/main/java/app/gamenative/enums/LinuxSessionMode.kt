package app.gamenative.enums

import app.gamenative.R

/**
 * How long a Linux session lives once its window is closed.
 *
 * The default matches what the app has always done, because it is also what costs nothing: a
 * session is an X server, a window manager and the application itself, and holding all of that
 * for something the user has finished with is only right if they said so.
 *
 * Holding one is what makes several applications at once useful -- switching between two windows
 * should not mean starting one of them again -- and it is a decision only the user can make,
 * since it trades battery for the state inside the app.
 */
enum class LinuxSessionMode(
    val key: String,
    /** Whether closing the last window showing a session leaves it running. */
    val holdsWhenClosed: Boolean,
    val titleRes: Int,
    val summaryRes: Int,
) {
    CLOSE_WITH_APP(
        key = "close_with_app",
        holdsWhenClosed = false,
        titleRes = R.string.linux_session_mode_close,
        summaryRes = R.string.linux_session_mode_close_summary,
    ),
    KEEP_RUNNING(
        key = "keep_running",
        holdsWhenClosed = true,
        titleRes = R.string.linux_session_mode_keep,
        summaryRes = R.string.linux_session_mode_keep_summary,
    ),
    ;

    companion object {
        val DEFAULT = CLOSE_WITH_APP

        fun fromKey(key: String?): LinuxSessionMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
