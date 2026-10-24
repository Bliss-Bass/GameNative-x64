package app.gamenative.linux

import android.content.Context
import android.os.Environment
import android.system.Os
import java.io.File
import kotlin.math.abs
import timber.log.Timber

/**
 * Finds the applications installed in the Linux userland.
 *
 * Reads the same `.desktop` files a Linux desktop menu is built from, which is what makes
 * "install it with apt and it shows up" work without a package list of our own: anything
 * with a menu entry is an application, and anything without one is a library, a service or
 * a helper the user did not ask to see. `.AppImage` files dropped in the guest home or in
 * Android's Download and Documents folders are listed too. FUSE is not available under
 * PRoot, so those launch with `--appimage-extract-and-run`.
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

    /** Real directories walked for AppImages. Symlinks (the storage shortcuts) are not followed. */
    private val APPIMAGE_ROOTFS_DIRS = listOf(
        "root",
        "opt",
        "usr/local",
    )

    private val APPIMAGE_STORAGE_DIRS = listOf("Download", "Documents")

    private const val APPIMAGE_SCAN_DEPTH = 4
    private const val APPIMAGE_EXTRACT_FLAG = "--appimage-extract-and-run"

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
         * application on a Linux system and survives reinstalls and upgrades, prefixed with its
         * distro for every distro but Ubuntu (see [LinuxAppIds]). Used where something readable
         * and stable is needed, such as a generated package name or a session key.
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
        /** The distro whose rootfs the entry is in, and which the app runs in. */
        val distroId: String = LinuxDistroCatalog.UBUNTU,
    ) {
        /**
         * What to actually run. A console program asked to run in a terminal gets one,
         * since on its own it would draw nothing and exit immediately.
         */
        val launchArgv: String
            get() = if (terminal) "xterm -e $exec" else exec
    }

    /**
     * Applications in every supported distro that is installed, by name. Ubuntu's are read
     * whenever its rootfs exists; experimental distros are never scanned.
     */
    fun scan(context: Context): List<LinuxApp> {
        val distros = listOf(LinuxDistroCatalog.UBUNTU) +
            LinuxDistros.integrated(context).map { it.id }.filter { it != LinuxDistroCatalog.UBUNTU }
        return distros.flatMap { scan(context, it) }.sortedBy { it.name.lowercase() }
    }

    /** Applications in [distroId], by name. Empty if it is not installed. */
    fun scan(context: Context, distroId: String): List<LinuxApp> {
        val rootfs = if (distroId == LinuxDistroCatalog.UBUNTU) {
            LinuxRootfs.rootfsDir(context)
        } else {
            LinuxDistros.installedRootfs(context, distroId)?.takeIf { LinuxDistros.isIntegrated(context, distroId) }
        } ?: return emptyList()
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
                // Written below for the panel launcher. Scanning them again would list each
                // AppImage twice.
                if (file.name.startsWith("gn-appimage-")) continue
                val app = runCatching { parse(file.readText(), rootfs) }
                    .onFailure { Timber.w(it, "[LinuxAppScanner]: could not read %s", file.name) }
                            .getOrNull() ?: continue
                        found[file.name] = app.copy(
                            id = idFor(LinuxAppIds.qualify(distroId, file.name)),
                            entryId = LinuxAppIds.qualify(distroId, file.nameWithoutExtension),
                            desktopFile = "/$dir/${file.name}",
                            distroId = distroId,
                        )
            }
        }

        // AppImages are glibc programs unpacked and run in place; they stay with Ubuntu, where
        // they always ran, rather than appearing once per distro.
        if (distroId == LinuxDistroCatalog.UBUNTU) scanAppImages(context, rootfs, found)

        Timber.i("[LinuxAppScanner]: found %d applications in %s", found.size, distroId)
        return found.values.sortedBy { it.name.lowercase() }
    }

    /**
     * AppImages have no menu entry until they are run. FUSE cannot mount them under PRoot,
     * so the command asks the runtime to unpack and execute instead.
     */
    private fun scanAppImages(context: Context, rootfs: File, found: MutableMap<String, LinuxApp>) {
        for (dir in APPIMAGE_ROOTFS_DIRS) {
            eachAppImage(File(rootfs, dir), 0) { file ->
                val guestPath = "/" + file.relativeTo(rootfs).invariantSeparatorsPath
                addAppImage(rootfs, found, guestPath, file.name)
            }
        }
        val storageGranted = runCatching { LinuxStorage.isGranted(context) }.getOrDefault(false)
        if (!storageGranted) return
        val primary = Environment.getExternalStorageDirectory()
        for (folder in APPIMAGE_STORAGE_DIRS) {
            eachAppImage(File(primary, folder), 0) { file ->
                val relative = file.relativeTo(File(primary, folder)).invariantSeparatorsPath
                val guestPath = "${LinuxStorage.GUEST_PRIMARY}/$folder/$relative"
                addAppImage(rootfs, found, guestPath, file.name)
            }
        }
    }

    private fun addAppImage(
        rootfs: File,
        found: MutableMap<String, LinuxApp>,
        guestPath: String,
        fileName: String,
    ) {
        val desktopName = "gn-appimage-${abs(guestPath.hashCode()).toString(16)}.desktop"
        val desktopGuest = "/root/.local/share/applications/$desktopName"
        val exec = "${escapeGuestWord(guestPath)} $APPIMAGE_EXTRACT_FLAG"
        val name = fileName.substringBeforeLast('.').ifBlank { fileName }
        writeAppImageDesktop(rootfs, desktopName, name, guestPath)
        found[desktopName] = LinuxApp(
            id = idFor(guestPath),
            entryId = desktopName.removeSuffix(".desktop"),
            desktopFile = desktopGuest,
            name = name,
            exec = exec,
            comment = "AppImage",
            categories = listOf("AppImage"),
        )
    }

    private fun writeAppImageDesktop(rootfs: File, desktopName: String, name: String, guestPath: String) {
        val dir = File(rootfs, "root/.local/share/applications").apply { mkdirs() }
        val text = """
            [Desktop Entry]
            Type=Application
            Name=$name
            Exec=${escapeDesktopExec(guestPath)} $APPIMAGE_EXTRACT_FLAG
            Comment=AppImage
            Categories=AppImage;
        """.trimIndent() + "\n"
        val file = File(dir, desktopName)
        if (runCatching { file.readText() }.getOrNull() == text) return
        file.writeText(text)
    }

    /** ProcessHelper treats a backslash-space as one word. */
    private fun escapeGuestWord(path: String): String = path.replace(" ", "\\ ")

    private fun escapeDesktopExec(path: String): String =
        "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun eachAppImage(dir: File, depth: Int, visitor: (File) -> Unit) {
        if (depth > APPIMAGE_SCAN_DEPTH || !dir.isDirectory || isSymlink(dir)) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (isSymlink(child)) continue
            if (child.isDirectory) {
                eachAppImage(child, depth + 1, visitor)
            } else if (child.isFile && child.name.endsWith(".appimage", ignoreCase = true)) {
                visitor(child)
            }
        }
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { Os.readlink(file.absolutePath) }.getOrNull() != null

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
        if (command.startsWith("/")) return inGuest(rootfs, command).canExecute()
        return listOf("usr/local/bin", "usr/bin", "bin", "usr/sbin", "sbin").any {
            inGuest(rootfs, "/$it/$command").canExecute()
        }
    }

    /**
     * The host file for guest path [path], following symlinks inside [rootfs]. The host would
     * resolve an absolute link against Android's root instead: Chrome's
     * `/usr/bin/google-chrome-stable -> /opt/google/chrome/google-chrome` then looks missing.
     */
    internal fun inGuest(rootfs: File, path: String): File {
        var guest = path
        repeat(MAX_SYMLINKS) {
            val host = File(rootfs, guest.removePrefix("/"))
            val link = host.toPath()
            if (!java.nio.file.Files.isSymbolicLink(link)) return host
            val target = runCatching { java.nio.file.Files.readSymbolicLink(link).toString() }.getOrNull() ?: return host
            guest = if (target.startsWith("/")) target else "${guest.substringBeforeLast('/')}/$target"
            guest = java.nio.file.Paths.get(guest).normalize().toString()
        }
        return File(rootfs, guest.removePrefix("/"))
    }

    private const val MAX_SYMLINKS = 40

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
