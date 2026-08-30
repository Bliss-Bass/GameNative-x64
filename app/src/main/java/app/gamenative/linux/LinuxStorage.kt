package app.gamenative.linux

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.system.Os
import java.io.File
import timber.log.Timber

/**
 * Android's shared storage, as the userland sees it.
 *
 * Bound in rather than copied through: a Linux app opens a path, and anything that went via
 * MediaStore or a document picker would mean teaching every guest program about Android.
 *
 * The bind is only half of it. A guest process runs as this app's uid, so the kernel applies
 * the same scoped-storage rules it applies to us, and without all-files access a bound mount
 * point would list nothing and open nothing. [isGranted] is what decides whether to bind at
 * all, and [requestAccess] is how the user grants it.
 */
object LinuxStorage {

    /** Where every Android volume is gathered inside the guest. */
    const val GUEST_ROOT = "/mnt/storage"

    /** Built-in shared storage -- Download, Pictures and the rest live under here. */
    const val GUEST_PRIMARY = "$GUEST_ROOT/internal"

    /**
     * Android's directory names, and the guest home entry that points at each.
     *
     * Android's names are kept on the far side of the link rather than translated, so a path
     * shown in a Linux file dialog is the same path Android's own file manager shows. The
     * near side uses the names a Linux user expects: nothing on this system calls a video
     * directory "Movies".
     */
    private val HOME_LINKS = mapOf(
        "Downloads" to "Download",
        "Documents" to "Documents",
        "Pictures" to "Pictures",
        "Music" to "Music",
        "Videos" to "Movies",
    )

    /** Whether the guest can actually read what gets bound. */
    fun isGranted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        // Before scoped storage the ordinary read permission reaches the whole volume.
        else -> context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Settings' all-files access page for this app.
     *
     * There is no runtime dialog for this permission: it is a special access, and the only
     * way to hold it is a trip to Settings. A ROM build can pre-grant it instead.
     */
    fun requestAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            ),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
        Timber.w("[LinuxStorage]: no Settings activity took the all-files access request")
    }

    /**
     * The `--bind` arguments for every volume the guest can reach, host side first.
     *
     * Recomputed per process rather than cached: a volume can be mounted, and access can be
     * granted, while the userland is installed and idle. Existing guest processes keep the
     * view they started with, which is why the session has to be restarted after a grant.
     */
    fun binds(context: Context, rootfs: File): List<String> {
        configure(rootfs)

        // The permission, not the directory, is what decides this. A path under /storage
        // reports itself readable to any app -- the scoped-storage rules only bite on the
        // way through, so a bind made on that evidence would be a mount point that lists
        // nothing and opens nothing.
        if (!isGranted(context)) {
            Timber.i("[LinuxStorage]: no all-files access, leaving Android storage unbound")
            return emptyList()
        }

        return volumes(context).map { (guestPath, hostDir) ->
            // PRoot will not invent a mount point, so the guest side has to exist first.
            File(rootfs, guestPath.trimStart('/')).mkdirs()
            Timber.i("[LinuxStorage]: binding %s at %s", hostDir, guestPath)
            "${hostDir.absolutePath}:$guestPath"
        }
    }

    /** Guest path to host directory, for primary storage and any removable volume. */
    private fun volumes(context: Context): Map<String, File> = buildMap {
        put(GUEST_PRIMARY, Environment.getExternalStorageDirectory())

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@buildMap
        val manager = context.getSystemService(StorageManager::class.java) ?: return@buildMap
        manager.storageVolumes
            .filterNot { it.isPrimary }
            .forEach { volume ->
                val dir = volume.directory ?: return@forEach
                val name = slug(volume.getDescription(context) ?: dir.name)
                // Two cards described identically would otherwise land on one mount point.
                var guest = "$GUEST_ROOT/$name"
                var attempt = 2
                while (containsKey(guest)) guest = "$GUEST_ROOT/$name-${attempt++}"
                put(guest, dir)
            }
    }

    /** A volume description as a path component: lowercase, no spaces, nothing exotic. */
    private fun slug(description: String): String = description
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .ifEmpty { "removable" }

    /**
     * Points the guest's home and its XDG directories at Android's.
     *
     * Done on the way to every guest process rather than at install time, because it is the
     * one path a terminal, a session and a launcher entry all take -- and because a userland
     * installed before any of this existed then repairs itself on its next launch. Cheap
     * enough to repeat: each link is a readlink, and the config file is only rewritten when
     * it would change.
     *
     * Written whether or not access is granted: these are links and a config file, they cost
     * nothing while the permission is missing, and writing them here means a grant needs no
     * more than a new session.
     */
    fun configure(rootfs: File) {
        val home = File(rootfs, LinuxProgramLauncher.GUEST_HOME.trimStart('/'))
        HOME_LINKS.forEach { (linkName, androidName) ->
            link(File(home, linkName), "$GUEST_PRIMARY/$androidName")
        }
        link(File(home, "Storage"), GUEST_ROOT)
        writeUserDirs(home)
    }

    /**
     * The file every GTK and Qt file dialog reads to decide what its sidebar shows.
     *
     * Absolute guest paths rather than $HOME-relative ones: a dialog that followed the home
     * symlink would show the user "/root/Downloads" for a directory Android calls something
     * else entirely, and the two names should not drift.
     */
    private fun writeUserDirs(home: File) {
        val config = File(home, ".config").apply { mkdirs() }
        val entries = mapOf(
            "XDG_DESKTOP_DIR" to "\$HOME/Desktop",
            "XDG_DOWNLOAD_DIR" to "$GUEST_PRIMARY/Download",
            "XDG_DOCUMENTS_DIR" to "$GUEST_PRIMARY/Documents",
            "XDG_MUSIC_DIR" to "$GUEST_PRIMARY/Music",
            "XDG_PICTURES_DIR" to "$GUEST_PRIMARY/Pictures",
            "XDG_VIDEOS_DIR" to "$GUEST_PRIMARY/Movies",
        )
        val text = entries.entries.joinToString("\n", postfix = "\n") { (key, value) -> "$key=\"$value\"" }
        write(File(config, "user-dirs.dirs"), text)
        // Stops xdg-user-dirs-update from rewriting the above with its own guesses.
        write(File(config, "user-dirs.locale"), "C\n")
    }

    private fun write(file: File, text: String) {
        if (runCatching { file.readText() }.getOrNull() == text) return
        file.writeText(text)
    }

    /** Replaces [link] with a symlink to [target], unless something real is in the way. */
    private fun link(link: File, target: String) {
        val existing = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
        if (existing == target) return
        if (existing == null && link.exists()) {
            Timber.w("[LinuxStorage]: leaving %s alone, it is not a symlink", link.name)
            return
        }
        link.delete()
        runCatching { Os.symlink(target, link.absolutePath) }
            .onFailure { Timber.w("[LinuxStorage]: could not link %s: %s", link.name, it.message) }
    }
}
