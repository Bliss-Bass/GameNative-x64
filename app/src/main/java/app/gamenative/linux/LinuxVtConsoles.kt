package app.gamenative.linux

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * What runs on each of GameNative's Linux consoles (Ctrl+Alt+F4 to F6, Bass OS only).
 *
 * Every console is independent: several may use the same distro, each with its own shell, and any
 * may be off. [LinuxVtService][app.gamenative.service.LinuxVtService] reads this when a console is
 * first shown; changing it takes effect the next time that console's shell starts.
 */
object LinuxVtConsoles {

    /** The VT numbers GameNative owns. F1-F3 are Android consoles, F7 is Android itself. */
    val CONSOLES: IntRange = 4..6

    const val DEFAULT_DISTRO = "ubuntu"
    const val ROOT_USER = "root"

    private const val PREFS = "linux_vt_consoles"

    /** [shell] null means the distro's own default; [user] other than root logs in with `su -l`. */
    data class Assignment(
        val distroId: String = DEFAULT_DISTRO,
        val shell: String? = null,
        val user: String = ROOT_USER,
    )

    /** The assignment for [vt], or null when that console is off. */
    fun get(context: Context, vt: Int): Assignment? {
        val prefs = prefs(context)
        if (prefs.getBoolean(key(vt, "off"), false)) return null
        return Assignment(
            distroId = prefs.getString(key(vt, "distro"), null) ?: DEFAULT_DISTRO,
            shell = prefs.getString(key(vt, "shell"), null),
            user = prefs.getString(key(vt, "user"), null) ?: ROOT_USER,
        )
    }

    /** Sets [vt] to [assignment], or turns it off for null. */
    fun set(context: Context, vt: Int, assignment: Assignment?) {
        require(vt in CONSOLES) { "tty$vt is not a GameNative console" }
        prefs(context).edit().apply {
            putBoolean(key(vt, "off"), assignment == null)
            if (assignment != null) {
                putString(key(vt, "distro"), assignment.distroId)
                putString(key(vt, "shell"), assignment.shell)
                putString(key(vt, "user"), assignment.user)
            }
        }.apply()
    }

    /** Gives every console the same [assignment], the common case for someone with one distro. */
    fun setAll(context: Context, assignment: Assignment) = CONSOLES.forEach { set(context, it, assignment) }

    /**
     * The shell [assignment] runs in [rootfs]: its own choice, else bash, else POSIX sh. Paths are
     * guest paths; they are checked against the host copy of the rootfs.
     */
    fun shellFor(rootfs: File, assignment: Assignment): String =
        assignment.shell ?: listOf("/bin/bash", "/usr/bin/bash", "/bin/sh")
            .firstOrNull { File(rootfs, it.removePrefix("/")).exists() }
            ?: "/bin/sh"

    /**
     * The guest command for a console: clear it, say what it is, then start a login shell. The
     * banner and shell travel as environment so no quoting has to survive the trip through PRoot.
     */
    fun guestCommand(assignment: Assignment): List<String> {
        val login = if (assignment.user == ROOT_USER) {
            "exec \"\$GN_SHELL\" -l"
        } else {
            "exec su -l -s \"\$GN_SHELL\" \"\$GN_USER\""
        }
        return listOf("/bin/sh", "-c", "printf '\\033c%s\\n\\n' \"\$GN_BANNER\"; $login")
    }

    fun guestEnv(vt: Int, distroName: String, shell: String, assignment: Assignment): Map<String, String> = mapOf(
        "TERM" to "linux",
        "GN_SHELL" to shell,
        "GN_USER" to assignment.user,
        "GN_BANNER" to "$distroName on tty$vt. Ctrl+Alt+F7 returns to Android.",
    )

    private fun key(vt: Int, field: String) = "vt$vt.$field"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
