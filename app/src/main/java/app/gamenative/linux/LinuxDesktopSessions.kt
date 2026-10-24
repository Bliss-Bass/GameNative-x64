package app.gamenative.linux

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The desktops the "Linux desktop" window can run, and which one it runs.
 *
 * Openbox is the default because it is small and is already part of Ubuntu's graphical session;
 * the rest are full desktops someone may already know, installed on request into whichever distro
 * they pick. Those run over Xtigervnc: the Android X server lacks the extensions a compositing
 * window manager probes for, and openbox already crashes there.
 *
 * None of them may start systemd or a compositor: there is no init under PRoot, and a compositor
 * has no GPU path through RFB. Each session is adapted for that before it starts ([prepare]).
 */
object LinuxDesktopSessions {

    enum class Session(
        val id: String,
        val displayName: String,
        /** Guest path proving it is installed. */
        val binary: String,
        /** The session command, run under dbus-launch; null for openbox, which GameNative assembles itself. */
        val command: String?,
    ) {
        OPENBOX("openbox", "Openbox", "usr/bin/openbox", null),
        KDE("kde", "KDE Plasma", "usr/bin/startplasma-x11", "/usr/bin/startplasma-x11"),
        XFCE("xfce", "Xfce", "usr/bin/startxfce4", "/usr/bin/startxfce4"),
        LXQT("lxqt", "LXQt", "usr/bin/startlxqt", "/usr/bin/startlxqt"),
        ;

        companion object {
            fun byId(id: String?): Session? = entries.firstOrNull { it.id == id }
        }
    }

    /** What the session window opens: [distroId]'s rootfs running [session]. */
    data class Target(val distroId: String, val session: Session) {
        /** Whether this is the original desktop, which keeps the user's display backend choice. */
        val isBuiltIn: Boolean get() = distroId == LinuxDistroCatalog.UBUNTU && session == Session.OPENBOX
    }

    private const val PREFS = "linux_desktop_sessions"
    private const val KEY_DISTRO = "distro"

    /** X server binaries by name: Debian's packaging renames TigerVNC's Xvnc. */
    val X_SERVERS = listOf("usr/bin/Xtigervnc", "usr/bin/Xvnc")

    private val lock = Mutex()

    /**
     * Packages every desktop needs besides itself in a distro other than Ubuntu: the X server,
     * a session bus, xsetroot, and the fonts nothing else pulls in with recommends off. Alpine
     * also lacks zone data, without which the panel clock ignores TZ and shows UTC.
     */
    private fun basePackages(packageManager: LinuxPackageManager): List<String> = when (packageManager) {
        LinuxPackageManager.APT ->
            listOf("tigervnc-standalone-server", "dbus-x11", "x11-xserver-utils", "xfonts-base", "fonts-dejavu-core")
        LinuxPackageManager.PACMAN -> listOf("tigervnc", "dbus", "xorg-xsetroot", "xorg-fonts-misc", "ttf-dejavu")
        LinuxPackageManager.DNF ->
            listOf("tigervnc-server-minimal", "dbus-x11", "xsetroot", "xorg-x11-fonts-misc", "dejavu-sans-fonts")
        LinuxPackageManager.APK -> listOf("tigervnc", "dbus-x11", "xsetroot", "font-misc-misc", "font-dejavu", "tzdata")
    }

    /** The session's own packages, which are what removing it takes away. */
    fun packages(session: Session, packageManager: LinuxPackageManager): List<String> = when (session) {
        Session.OPENBOX -> listOf("openbox", "tint2", "xterm")
        Session.KDE -> when (packageManager) {
            LinuxPackageManager.APT, LinuxPackageManager.PACMAN -> listOf("plasma-desktop", "kwin-x11", "konsole")
            LinuxPackageManager.DNF -> listOf("plasma-workspace-x11", "kwin-x11", "konsole")
            LinuxPackageManager.APK -> listOf("plasma-desktop", "kwin", "konsole")
        }
        Session.XFCE -> when (packageManager) {
            LinuxPackageManager.APT, LinuxPackageManager.APK -> listOf("xfce4", "xfce4-terminal")
            LinuxPackageManager.PACMAN -> listOf("xfce4")
            LinuxPackageManager.DNF ->
                listOf("xfce4-session", "xfwm4", "xfce4-panel", "xfdesktop", "xfce4-settings", "xfce4-terminal", "Thunar")
        }
        Session.LXQT -> when (packageManager) {
            LinuxPackageManager.APT -> listOf("lxqt-core", "openbox", "qterminal")
            LinuxPackageManager.PACMAN -> listOf("lxqt", "openbox")
            LinuxPackageManager.DNF ->
                listOf("lxqt-session", "lxqt-panel", "lxqt-config", "pcmanfm-qt", "qterminal", "openbox")
            LinuxPackageManager.APK -> listOf("lxqt-desktop", "openbox", "qterminal")
        }
    }

    /**
     * The distro the desktop window opens in: the user's choice while it is installed and supported,
     * else the default distro.
     */
    fun desktopDistro(context: Context): String =
        prefs(context).getString(KEY_DISTRO, null)
            ?.takeIf { LinuxDistros.isIntegrated(context, it) }
            ?: LinuxDistros.defaultId(context)

    fun setDesktopDistro(context: Context, distroId: String) {
        prefs(context).edit().putString(KEY_DISTRO, distroId).apply()
    }

    /** [distroId]'s session: the user's choice while it is installed, else Openbox. */
    fun selected(context: Context, distroId: String): Session =
        Session.byId(prefs(context).getString(sessionKey(distroId), null))
            ?.takeIf { isInstalled(context, distroId, it) }
            ?: Session.OPENBOX

    fun select(context: Context, distroId: String, session: Session) {
        prefs(context).edit().putString(sessionKey(distroId), session.id).apply()
    }

    /** Drops [distroId]'s desktop choices once it is removed, so a reinstall starts from the defaults. */
    fun forget(context: Context, distroId: String) {
        val prefs = prefs(context)
        prefs.edit().apply {
            remove(sessionKey(distroId))
            if (prefs.getString(KEY_DISTRO, null) == distroId) remove(KEY_DISTRO)
        }.apply()
    }

    fun target(context: Context): Target {
        val distroId = desktopDistro(context)
        return Target(distroId, selected(context, distroId))
    }

    fun isInstalled(context: Context, distroId: String, session: Session): Boolean {
        if (distroId == LinuxDistroCatalog.UBUNTU && session == Session.OPENBOX) {
            return LinuxRootfs.hasDisplaySession(context)
        }
        val rootfs = LinuxDistros.installedRootfs(context, distroId) ?: return false
        return File(rootfs, session.binary).exists() && X_SERVERS.any { File(rootfs, it).exists() }
    }

    /** Installs [session] into [distroId], with the X server and session bus it needs. */
    suspend fun install(
        context: Context,
        distroId: String,
        session: Session,
        onProgress: (LinuxRootfs.Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                val distro = LinuxDistroCatalog.byId(distroId) ?: throw IOException("Unknown distro $distroId")
                val rootfs = LinuxDistros.installedRootfs(context, distroId)
                    ?: throw IOException("${distro.displayName} is not installed")
                val pm = distro.packageManager
                // Ubuntu's graphical session already brought the X server and bus in.
                val wanted = packages(session, pm) +
                    if (distroId == LinuxDistroCatalog.UBUNTU) emptyList() else basePackages(pm)

                val label = "Installing ${session.displayName} in ${distro.displayName}"
                onProgress(LinuxRootfs.Progress(label, -1f))
                pm.refresh?.let { runPackageManager(context, rootfs, pm, it) }
                val output = runPackageManager(context, rootfs, pm, pm.install + wanted)
                // Judged by what landed: the launcher folds the exit status into the output.
                if (!isInstalled(context, distroId, session)) {
                    Timber.w("[LinuxDesktopSessions]: %s output:\n%s", label, output.takeLast(4_000))
                    throw IOException("$label failed: ${lastLine(output)}")
                }
                prepare(rootfs, session)
                Timber.i("[LinuxDesktopSessions]: %s installed in %s", session.id, distroId)
            }.onFailure { Timber.e(it, "[LinuxDesktopSessions]: %s install in %s failed", session.id, distroId) }
        }
    }

    /**
     * Removes [session]'s own packages from [distroId]. Openbox stays in Ubuntu, where it is part
     * of the graphical session the app windows need.
     */
    suspend fun remove(context: Context, distroId: String, session: Session): Result<Unit> = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                require(!(distroId == LinuxDistroCatalog.UBUNTU && session == Session.OPENBOX)) {
                    "Openbox is part of Ubuntu's graphical session"
                }
                val distro = LinuxDistroCatalog.byId(distroId) ?: throw IOException("Unknown distro $distroId")
                val rootfs = LinuxDistros.installedRootfs(context, distroId)
                    ?: throw IOException("${distro.displayName} is not installed")
                val output = runPackageManager(
                    context, rootfs, distro.packageManager,
                    distro.packageManager.remove + packages(session, distro.packageManager),
                )
                if (File(rootfs, session.binary).exists()) {
                    Timber.w("[LinuxDesktopSessions]: removing %s:\n%s", session.id, output.takeLast(4_000))
                    throw IOException("Removing ${session.displayName} failed: ${lastLine(output)}")
                }
                if (prefs(context).getString(sessionKey(distroId), null) == session.id) {
                    prefs(context).edit().remove(sessionKey(distroId)).apply()
                }
            }.onFailure { Timber.e(it, "[LinuxDesktopSessions]: %s removal from %s failed", session.id, distroId) }
        }
    }

    private fun runPackageManager(
        context: Context,
        rootfs: File,
        packageManager: LinuxPackageManager,
        argv: List<String>,
    ): String {
        val env = if (packageManager == LinuxPackageManager.APT) {
            mapOf("DEBIAN_FRONTEND" to "noninteractive", "DEBCONF_NONINTERACTIVE_SEEN" to "true")
        } else {
            emptyMap()
        }
        return LinuxProgramLauncher.runWithOutput(
            context,
            argv.joinToString(" "),
            extraEnv = env,
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            rootfs = rootfs,
        )
    }

    private fun lastLine(output: String): String = output.lines().lastOrNull { it.isNotBlank() }.orEmpty()

    /**
     * Turns off what cannot work under PRoot, before every start: a compositor (no GPU path over
     * RFB) and Plasma's systemd boot (no init). Written only when the user has no setting of their
     * own, so a choice made inside the desktop sticks.
     */
    fun prepare(rootfs: File, session: Session) {
        fun writeIfAbsent(path: String, text: String) {
            val file = File(rootfs, path)
            if (file.exists()) return
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
        when (session) {
            Session.OPENBOX -> LinuxDesktopConfig.writeWindowManagerConfigs(rootfs)
            Session.KDE -> {
                writeIfAbsent("root/.config/startkderc", "[General]\nsystemdBoot=false\n")
                writeIfAbsent("root/.config/kwinrc", "[Compositing]\nEnabled=false\n")
            }
            Session.XFCE -> writeIfAbsent(
                "root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <channel name="xfwm4" version="1.0">
                  <property name="general" type="empty">
                    <property name="use_compositing" type="bool" value="false"/>
                  </property>
                </channel>
                """.trimIndent() + "\n",
            )
            Session.LXQT -> writeIfAbsent("root/.config/lxqt/session.conf", "[General]\nwindow_manager=openbox\n")
        }
    }

    /** The session command line and environment, for a desktop other than openbox. */
    fun launch(session: Session): Pair<String, Map<String, String>> {
        val command = checkNotNull(session.command) { "openbox is started by the display session" }
        val desktop = when (session) {
            Session.KDE -> "KDE"
            Session.XFCE -> "XFCE"
            Session.LXQT -> "LXQt"
            Session.OPENBOX -> "Openbox"
        }
        return "/usr/bin/dbus-launch --exit-with-session $command" to mapOf(
            "XDG_SESSION_TYPE" to "x11",
            "XDG_CURRENT_DESKTOP" to desktop,
            "KWIN_COMPOSE" to "N",
            "DESKTOP_SESSION" to session.id,
        )
    }

    /** Long: a full desktop is a few hundred packages over whatever network the tablet has. */
    private const val INSTALL_TIMEOUT_SECONDS = 3 * 60 * 60

    private fun sessionKey(distroId: String) = "session.$distroId"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
