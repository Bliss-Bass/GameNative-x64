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

    const val DEFAULT_DISTRO = LinuxDistroCatalog.UBUNTU
    const val ROOT_USER = "root"

    private const val PREFS = "linux_vt_consoles"

    /** [shell] null means the distro's own default; [user] other than root logs in with `su -l`. */
    data class Assignment(
        val distroId: String = DEFAULT_DISTRO,
        val shell: String? = null,
        val user: String = ROOT_USER,
    )

    /**
     * The assignment for [vt], or null when that console is off. A console nobody assigned a distro
     * runs the default one ([LinuxDistros.defaultId]): the user's choice, else the first installed.
     */
    fun get(context: Context, vt: Int): Assignment? {
        val prefs = prefs(context)
        if (prefs.getBoolean(key(vt, "off"), false)) return null
        return Assignment(
            // An experimental distro picked before it was marked so follows the default instead.
            distroId = assignedDistro(context, vt)?.takeIf { LinuxDistroCatalog.byId(it)?.supported == true }
                ?: LinuxDistros.defaultId(context),
            shell = prefs.getString(key(vt, "shell"), null),
            user = prefs.getString(key(vt, "user"), null) ?: ROOT_USER,
        )
    }

    /** The distro the user picked for [vt], or null when it follows the default. */
    fun assignedDistro(context: Context, vt: Int): String? = prefs(context).getString(key(vt, "distro"), null)

    /** Forgets everything set for [vt], so it follows the default distro with its default shell. */
    fun clear(context: Context, vt: Int) {
        prefs(context).edit().apply {
            for (field in listOf("off", "distro", "shell", "user")) remove(key(vt, field))
        }.apply()
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
     * The shell [assignment] runs in [rootfs]: its own choice, else bash, else the distro's first
     * login shell. Paths are guest paths; they are checked against the host copy of the rootfs.
     */
    fun shellFor(rootfs: File, assignment: Assignment): String =
        assignment.shell ?: LinuxDistros.shells(rootfs).firstOrNull() ?: "/bin/sh"

    /**
     * Accounts a console can log in as in [rootfs]: root plus every ordinary user (uid 1000 and up)
     * with a real shell.
     */
    fun users(rootfs: File): List<String> {
        val passwd = File(rootfs, "etc/passwd").takeIf { it.isFile }?.readLines().orEmpty()
        val regular = passwd.mapNotNull { line ->
            val fields = line.split(':')
            val uid = fields.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            val shell = fields.getOrNull(6).orEmpty()
            fields[0].takeIf { uid in 1000 until 65534 && !shell.endsWith("nologin") && !shell.endsWith("false") }
        }
        return listOf(ROOT_USER) + regular
    }

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
