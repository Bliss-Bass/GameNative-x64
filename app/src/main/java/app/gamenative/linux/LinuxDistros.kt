package app.gamenative.linux

import android.content.Context
import android.content.SharedPreferences
import app.gamenative.service.LinuxVtService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Which Linux userlands are installed, where each one lives, and installing or removing them.
 *
 * Each distro has its own rootfs under `files/linux/distros/<id>/rootfs`, so they can sit side
 * by side and be removed one at a time. Ubuntu is installed by [LinuxRootfs], which also sets up
 * its graphical session; every other distro is unpacked and prepared here.
 */
object LinuxDistros {

    private const val PREFS = "linux_distros"
    private const val KEY_DEFAULT = "default"

    /** Where Ubuntu lived before there was more than one distro. */
    private const val LEGACY_ROOTFS = "rootfs"

    private val installLock = Mutex()

    fun distrosDir(context: Context): File = File(LinuxRootfs.rootDir(context), "distros")

    fun rootfsDir(context: Context, id: String): File = File(distrosDir(context), "$id/rootfs")

    /** Holds the tarball checksum the rootfs came from, written last so a partial unpack is not trusted. */
    private fun stampFile(context: Context, id: String): File = File(distrosDir(context), "$id/.installed")

    fun isInstalled(context: Context, id: String): Boolean {
        if (id == LinuxDistroCatalog.UBUNTU) return LinuxRootfs.isInstalled(context)
        return stampFile(context, id).isFile && File(rootfsDir(context, id), "etc").isDirectory
    }

    fun installed(context: Context): List<LinuxDistro> =
        LinuxDistroCatalog.all.filter { isInstalled(context, it.id) }

    /** [id]'s rootfs, or null when it is not installed. */
    fun installedRootfs(context: Context, id: String): File? =
        rootfsDir(context, id).takeIf { isInstalled(context, id) }

    /**
     * The login shells [rootfs] has, as guest paths: what its /etc/shells lists and is actually
     * there, with bash first when present. /etc/shells lists each shell under /bin and /usr/bin
     * on merged-/usr distros; only the first of a pair is kept.
     */
    fun shells(rootfs: File): List<String> {
        val listed = File(rootfs, "etc/shells").takeIf { it.isFile }?.readLines().orEmpty()
            .map { it.trim() }
            .filter { it.startsWith("/") && !it.endsWith("nologin") && !it.endsWith("false") }
        val present = (listed + listOf("/bin/bash", "/usr/bin/bash", "/bin/sh"))
            .filter { File(rootfs, it.removePrefix("/")).exists() }
            .distinctBy { it.substringAfterLast('/') }
        return present.sortedBy { if (it.endsWith("/bash")) 0 else 1 }
    }

    /**
     * The distro new terminals open in and unassigned consoles run: the user's choice while it is
     * installed, else the first installed distro in catalog order, else Ubuntu (nothing installed).
     */
    fun defaultId(context: Context): String =
        prefs(context).getString(KEY_DEFAULT, null)
            ?.takeIf { LinuxDistroCatalog.byId(it) != null && isInstalled(context, it) }
            ?: installed(context).firstOrNull()?.id
            ?: LinuxDistroCatalog.UBUNTU

    fun setDefault(context: Context, id: String) {
        require(LinuxDistroCatalog.byId(id) != null) { "Unknown distro $id" }
        prefs(context).edit().putString(KEY_DEFAULT, id).apply()
    }

    suspend fun install(
        context: Context,
        id: String,
        onProgress: (LinuxRootfs.Progress) -> Unit = {},
    ): Result<Unit> {
        val distro = LinuxDistroCatalog.byId(id) ?: return Result.failure(IOException("Unknown distro $id"))
        if (id == LinuxDistroCatalog.UBUNTU) return LinuxRootfs.install(context, onProgress)
        return withContext(Dispatchers.IO) {
            installLock.withLock {
                runCatching { installLocked(context, distro, onProgress) }
                    .onFailure { Timber.e(it, "[LinuxDistros]: %s install failed", id) }
            }
        }
    }

    private fun installLocked(context: Context, distro: LinuxDistro, onProgress: (LinuxRootfs.Progress) -> Unit) {
        if (!LinuxRootfs.isSupported()) throw LinuxRootfs.UnsupportedAbiException()
        val url = checkNotNull(distro.tarballUrl)
        val sha256 = checkNotNull(distro.sha256)
        if (isInstalled(context, distro.id) && stampFile(context, distro.id).readText().trim() == sha256) return

        val free = context.filesDir.usableSpace
        val needed = distro.installedMb.toLong() * 1024 * 1024
        if (free < needed) {
            throw IOException("${distro.displayName} needs ${distro.installedMb} MB free, ${free / (1024 * 1024)} MB available")
        }

        val rootfs = rootfsDir(context, distro.id)
        val tarball = File(context.cacheDir, "distro-${distro.id}.tar.xz")
        RootfsArchive.fetchVerified(url, sha256, tarball, "Downloading ${distro.displayName}", onProgress)

        onProgress(LinuxRootfs.Progress("Unpacking ${distro.displayName}", -1f))
        stampFile(context, distro.id).delete()
        if (rootfs.exists()) rootfs.deleteRecursively()
        rootfs.mkdirs()
        RootfsArchive.extract(tarball, rootfs, distro.stripComponents, ownerAccess = true)
        tarball.delete()

        // A missing /etc means the strip count is wrong for this tarball, not a usable distro.
        if (!File(rootfs, "etc").isDirectory) {
            rootfs.deleteRecursively()
            throw IOException("${distro.displayName}'s archive has no /etc; not a rootfs")
        }
        RootfsArchive.prepareBase(rootfs)
        prepareShell(rootfs)
        preparePackageManager(rootfs, distro.packageManager)

        stampFile(context, distro.id).writeText(sha256)
        Timber.i("[LinuxDistros]: installed %s to %s", distro.id, rootfs)
        LinuxVtService.startIfSupported(context)
    }

    /**
     * Lets `su -l` keep the environment PRoot hands the guest, as proot-distro's own setup does:
     * Arch and Fedora's PAM stacks otherwise drop it, and a console logged in as another user
     * comes up without TERM or PATH.
     */
    private fun prepareShell(rootfs: File) {
        val line = "session  required  pam_env.so readenv=1"
        for (name in listOf("su", "su-l", "system-auth")) {
            val pam = File(rootfs, "etc/pam.d/$name")
            if (pam.isFile && pam.readLines().none { it.trim() == line }) pam.appendText("$line\n")
        }
    }

    /** What each package manager needs to work under PRoot at all. */
    private fun preparePackageManager(rootfs: File, packageManager: LinuxPackageManager) {
        when (packageManager) {
            LinuxPackageManager.APT -> {
                // apt drops to the _apt user to download, which PRoot's fake root cannot do.
                File(rootfs, "etc/apt/apt.conf.d").mkdirs()
                File(rootfs, "etc/apt/apt.conf.d/99gamenative").writeText(
                    """
                    APT::Sandbox::User "root";
                    Acquire::Languages "none";
                    """.trimIndent() + "\n",
                )
            }

            LinuxPackageManager.PACMAN -> {
                // The x86_64 image is pacstrapped, so its mirrorlist is the stock one with every
                // server commented out. CheckSpace reads the mount table, which under PRoot is
                // Android's and fails the check outright.
                val mirrors = File(rootfs, "etc/pacman.d/mirrorlist")
                if (mirrors.isFile && mirrors.readLines().none { it.trim().startsWith("Server") }) {
                    mirrors.appendText("Server = https://geo.mirror.pkgbuild.com/\$repo/os/\$arch\n")
                }
                val conf = File(rootfs, "etc/pacman.conf")
                if (conf.isFile) {
                    conf.writeText(conf.readText().replace(Regex("(?m)^CheckSpace"), "#CheckSpace"))
                }
            }

            LinuxPackageManager.DNF, LinuxPackageManager.APK -> Unit
        }
    }

    /**
     * Removes [id]'s rootfs. Consoles assigned to it become unassigned, so they follow the default
     * distro, and it stops being the default itself and the desktop's distro.
     */
    suspend fun uninstall(context: Context, id: String) {
        if (id == LinuxDistroCatalog.UBUNTU) {
            LinuxRootfs.uninstall(context)
        } else {
            withContext(Dispatchers.IO) {
                installLock.withLock {
                    stampFile(context, id).delete()
                    File(distrosDir(context), id).deleteRecursively()
                }
            }
        }
        if (prefs(context).getString(KEY_DEFAULT, null) == id) prefs(context).edit().remove(KEY_DEFAULT).apply()
        LinuxDesktopSessions.forget(context, id)
        for (vt in LinuxVtConsoles.CONSOLES) {
            if (LinuxVtConsoles.assignedDistro(context, vt) == id) LinuxVtConsoles.clear(context, vt)
        }
        Timber.i("[LinuxDistros]: removed %s", id)
    }

    /**
     * Moves an Ubuntu install from `files/linux/rootfs`, where it lived before there were other
     * distros, to its own directory. A rename on the same filesystem, so it is instant and either
     * happens completely or not at all; run before anything opens the rootfs.
     */
    fun migrateLegacyLayout(context: Context) {
        val legacy = File(LinuxRootfs.rootDir(context), LEGACY_ROOTFS)
        if (!legacy.isDirectory) return
        val target = rootfsDir(context, LinuxDistroCatalog.UBUNTU)
        if (target.exists()) {
            Timber.w("[LinuxDistros]: both %s and %s exist; keeping the new one", legacy, target)
            return
        }
        target.parentFile?.mkdirs()
        if (legacy.renameTo(target)) {
            Timber.i("[LinuxDistros]: moved Ubuntu to %s", target)
        } else {
            Timber.e("[LinuxDistros]: could not move %s to %s", legacy, target)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
