package app.gamenative.linux

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import timber.log.Timber

/**
 * Builds a [TerminalSession] whose shell is bash inside the PRoot rootfs.
 *
 * The pty and the fork live in libtermux.so, so PRoot is exec'd as the session's program
 * with the guest shell as its tail arguments; from the emulator's point of view it is an
 * ordinary interactive shell.
 */
object LinuxShellSession {

    /** Rows of scrollback kept in memory. Matches Termux's own default. */
    private const val TRANSCRIPT_ROWS = 2000

    fun create(context: Context, client: TerminalSessionClient): TerminalSession {
        val argv = LinuxProgramLauncher.buildArgv(
            context = context,
            // Login shell: without -l bash skips the profile, and PATH and locale come out
            // wrong for anything the user then installs.
            guestArgv = listOf("/bin/bash", "-l"),
            // No X session here. A GUI app launched from this shell would find no display,
            // which is honest -- the Linux tab is what starts one.
            displaySocketDir = null,
        )
        Timber.i("[LinuxShellSession]: starting %s", argv.joinToString(" "))

        return TerminalSession(
            argv.first(),
            // The pty's cwd is on the Android side; --cwd above sets the guest's.
            LinuxRootfs.rootfsDir(context).absolutePath,
            argv.drop(1).toTypedArray(),
            LinuxProgramLauncher.prootEnv(context).toStringArray(),
            TRANSCRIPT_ROWS,
            client,
        )
    }
}
