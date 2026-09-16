package app.gamenative.linux

import android.content.Context
import java.io.File
import kotlin.math.abs
import timber.log.Timber

/**
 * Finds the applications installed in the Linux userland.
 *
 * Reads the same `.desktop` files a Linux desktop menu is built from, which is what makes
 * "install it with apt and it shows up" work without a package list of our own: anything
 * with a menu entry is an application, and anything without one is a library, a service or
 * a helper the user did not ask to see.
 *
 * Deliberately not a full freedesktop implementation. There is no XDG_DATA_DIRS to honour
 * (the rootfs layout is ours), no locale matching beyond the plain keys, and no support for
 * D-Bus activation or actions.
 */
object LinuxAppScanner {

    /**
     * Where menu entries live, in ascending order of precedence: a user-installed entry
     * shadows a packaged one of the same id, as it would on a desktop.
     */
    private val APPLICATION_DIRS = listOf(
        "usr/share/applications",
        "usr/local/share/applications",
        "root/.local/share/applications",
    )

    /** Icon theme directories, largest first: a scaled-down icon beats a scaled-up one. */
    private val ICON_DIRS = listOf(
        "usr/share/icons/hicolor/512x512/apps",
        "usr/share/icons/hicolor/256x256/apps",
        "usr/share/icons/hicolor/128x128/apps",
        "usr/share/icons/hicolor/96x96/apps",
        "usr/share/icons/hicolor/64x64/apps",
        "usr/share/icons/hicolor/48x48/apps",
        "usr/share/icons/hicolor/scalable/apps",
        "usr/share/icons/Adwaita/512x512/apps",
        "usr/share/icons/Adwaita/256x256/apps",
        "usr/share/pixmaps",
    )

    private val ICON_EXTENSIONS = listOf("png", "svg", "xpm")

    /**
     * The field codes an Exec line may carry. They pass files and URLs to an application
     * launched from a file manager; there is nothing to substitute here, and leaving them
     * in would have the program treat "%U" as a filename.
     */
    private val FIELD_CODES = Regex("""\s*%[fFuUdDnNickvm]""")

    data class LinuxApp(
        /** Stable across scans, so a pinned shortcut keeps working. */
        val id: Int,
        /**
         * The desktop entry's file name without its extension, which is what identifies an
         * application on a Linux system and survives reinstalls and upgrades. Used where
         * something readable and stable is needed, such as a generated package name.
         */
        val entryId: String,
        /**
         * Guest-absolute path of the entry this came from. Linux desktop components take a
         * `.desktop` path rather than a command, and this is what we hand them.
         */
        val desktopFile: String,
        val name: String,
        /** Command to run, field codes already removed. */
        val exec: String,
        /** Absolute guest path to an icon, or null if none was found. */
        val iconPath: String? = null,
        /** Whether the entry asks to be run inside a terminal. */
        val terminal: Boolean = false,
        val categories: List<String> = emptyList(),
        val comment: String? = null,
    ) {
        /**
         * What to actually run. A console program asked to run in a terminal gets one,
         * since on its own it would draw nothing and exit immediately.
         */
        val launchArgv: String
            get() = if (terminal) "xterm -e $exec" else exec
    }

    /** Applications in the userland, by name. Empty if it is not installed. */
    fun scan(context: Context): List<LinuxApp> {
        val rootfs = LinuxRootfs.rootfsDir(context)
        if (!rootfs.isDirectory) return emptyList()

        // Keyed by desktop file id so a later directory shadows an earlier one, which is
        // what precedence means here.
        val found = linkedMapOf<String, LinuxApp>()
        for (dir in APPLICATION_DIRS) {
            val files = File(rootfs, dir).listFiles { file -> file.extension == "desktop" } ?: continue
            for (file in files) {
                // Snap's own helpers are not apps the user asked to launch, and Ubuntu's
                // snap-stub browser packages ship NoDisplay entries that only confuse.
                if (file.name.startsWith("snap-") || file.name.startsWith("io.snapcraft.")) {
                    continue
                }
                val app = runCatching { parse(file.readText(), rootfs) }
                    .onFailure { Timber.w(it, "[LinuxAppScanner]: could not read %s", file.name) }
                            .getOrNull() ?: continue
                        found[file.name] = app.copy(
                            id = idFor(file.name),
                            entryId = file.nameWithoutExtension,
                            desktopFile = "/$dir/${file.name}",
                        )
            }
        }

        Timber.i("[LinuxAppScanner]: found %d applications", found.size)
        return found.values.sortedBy { it.name.lowercase() }
    }

    fun find(context: Context, id: Int): LinuxApp? = scan(context).firstOrNull { it.id == id }

    /**
     * Reads one desktop entry, or returns null if it is not something to show.
     *
     * Only the `[Desktop Entry]` group is read: the trailing `[Desktop Action ...]` groups
     * describe extra menu items on a launcher, and their own Name and Exec keys would
     * otherwise overwrite the entry's.
     */
    private fun parse(text: String, rootfs: File): LinuxApp? {
        val values = mutableMapOf<String, String>()
        var inEntry = false

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                inEntry = line == "[Desktop Entry]"
                continue
            }
            if (!inEntry) continue
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            // Localised keys ("Name[de]") are ignored rather than parsed: there is one
            // locale here, and it is the untagged one.
            values.putIfAbsent(line.take(separator).trim(), line.drop(separator + 1).trim())
        }

        if (values["Type"] != "Application") return null
        if (values["NoDisplay"].isTrue() || values["Hidden"].isTrue()) return null

        val name = values["Name"]?.takeIf { it.isNotBlank() } ?: return null
        val exec = values["Exec"]?.let(::cleanExec)?.takeIf { it.isNotBlank() } ?: return null

        // TryExec names the binary to test for, and its absence means the entry is stale --
        // a package removed without purging, typically.
        values["TryExec"]?.let { tryExec ->
            if (!isPresent(rootfs, tryExec)) return null
        }

        return LinuxApp(
            // All assigned by the caller, which is what knows the file.
            id = 0,
            entryId = "",
            desktopFile = "",
            name = name,
            exec = exec,
            iconPath = values["Icon"]?.let { resolveIcon(rootfs, it) },
            terminal = values["Terminal"].isTrue(),
            categories = values["Categories"]?.split(';')?.filter { it.isNotBlank() } ?: emptyList(),
            comment = values["Comment"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun String?.isTrue(): Boolean = this?.trim().equals("true", ignoreCase = true)

    /** Strips field codes, and the env wrapper some entries use to set variables. */
    private fun cleanExec(exec: String): String = FIELD_CODES.replace(exec, "").trim()

    /** Whether [command] resolves to something executable in the guest. */
    private fun isPresent(rootfs: File, command: String): Boolean {
        if (command.startsWith("/")) return File(rootfs, command.removePrefix("/")).canExecute()
        return listOf("usr/local/bin", "usr/bin", "bin", "usr/sbin", "sbin").any {
            File(rootfs, "$it/$command").canExecute()
        }
    }

    /**
     * Finds the icon named by an entry, as a path inside the guest.
     *
     * An Icon key is usually a bare name to be looked up in the icon theme, but absolute
     * paths are allowed and do occur.
     */
    private fun resolveIcon(rootfs: File, icon: String): String? {
        if (icon.isBlank()) return null

        if (icon.startsWith("/")) {
            return icon.takeIf { File(rootfs, it.removePrefix("/")).isFile }
        }

        for (dir in ICON_DIRS) {
            for (extension in ICON_EXTENSIONS) {
                val candidate = "$dir/$icon.$extension"
                if (File(rootfs, candidate).isFile) return "/$candidate"
            }
        }
        return resolveSizeSuffixedIcon(rootfs, icon)
    }

    /**
     * Finds an icon whose file name carries its size, as pixmaps often do: xterm ships
     * `mini.xterm_48x48.xpm` and asks for `mini.xterm`, so an exact-name search finds
     * nothing. Picks the largest, since these are bitmaps and scaling up looks worse.
     */
    private fun resolveSizeSuffixedIcon(rootfs: File, icon: String): String? {
        val dir = "usr/share/pixmaps"
        val sized = Regex("""^${Regex.escape(icon)}_(\d+)x\d+\.(?:${ICON_EXTENSIONS.joinToString("|")})$""")

        return File(rootfs, dir).listFiles()
            ?.mapNotNull { file ->
                sized.find(file.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to file.name }
            }
            ?.maxByOrNull { it.first }
            ?.let { (_, name) -> "/$dir/$name" }
    }

    /**
     * A stable id derived from the desktop file name.
     *
     * Derived rather than stored: the name is what identifies an application on a Linux
     * system, it survives reinstalls and upgrades, and a shortcut pinned to it should too.
     * Kept positive and non-zero so it can be treated as a valid id everywhere.
     */
    private fun idFor(desktopFileName: String): Int =
        abs(desktopFileName.hashCode()).takeIf { it != 0 } ?: 1
}
