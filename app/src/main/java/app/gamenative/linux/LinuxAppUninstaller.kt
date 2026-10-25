package app.gamenative.linux

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Uninstalls Linux apps the way their distro would: by removing the package that owns each one's
 * desktop entry. An entry no package owns (one the user wrote, or an AppImage's) is a file, and
 * goes as one, with the AppImage itself.
 *
 * Planned first so the user confirms the packages by name: one package can own several entries,
 * and removing it takes them all.
 */
object LinuxAppUninstaller {

    /** What uninstalling some apps will remove. */
    data class Plan(
        /** Packages to remove, by distro. */
        val packages: Map<String, List<String>>,
        /** Host files to delete, by the app they belong to: unowned entries and AppImages. */
        val files: List<File>,
        /** Apps whose entry nothing claims, which are left alone. */
        val unknown: List<LinuxAppScanner.LinuxApp>,
    ) {
        val isEmpty: Boolean get() = packages.values.all { it.isEmpty() } && files.isEmpty()
    }

    suspend fun plan(context: Context, apps: List<LinuxAppScanner.LinuxApp>): Plan = withContext(Dispatchers.IO) {
        val packages = linkedMapOf<String, MutableSet<String>>()
        val files = mutableListOf<File>()
        val unknown = mutableListOf<LinuxAppScanner.LinuxApp>()
        for (app in apps) {
            val rootfs = LinuxDistros.installedRootfs(context, app.distroId)
            val distro = LinuxDistroCatalog.byId(app.distroId)
            if (rootfs == null || distro == null) {
                unknown += app
                continue
            }
            val appImage = appImagePath(app)
            if (appImage != null) {
                files += File(rootfs, app.desktopFile.removePrefix("/"))
                // Only an AppImage inside the rootfs; one in shared storage is the user's file.
                if (!appImage.startsWith(LinuxStorage.GUEST_PRIMARY)) files += File(rootfs, appImage.removePrefix("/"))
                continue
            }
            val owner = owningPackage(context, rootfs, distro.packageManager, app.desktopFile)
            when {
                owner != null -> packages.getOrPut(app.distroId) { linkedSetOf() } += owner
                app.desktopFile.startsWith("/root/") || app.desktopFile.startsWith("/usr/local/") ->
                    files += File(rootfs, app.desktopFile.removePrefix("/"))
                else -> unknown += app
            }
        }
        Plan(packages.mapValues { it.value.toList() }, files, unknown)
    }

    /** Removes what [plan] lists, then republishes the app list so stubs and shortcuts follow. */
    suspend fun uninstall(context: Context, plan: Plan): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            for ((distroId, names) in plan.packages) {
                if (names.isEmpty()) continue
                val distro = LinuxDistroCatalog.byId(distroId) ?: continue
                val rootfs = LinuxDistros.installedRootfs(context, distroId)
                    ?: throw IOException("${distro.displayName} is not installed")
                val pm = distro.packageManager
                val output = LinuxDistros.runPackageManager(context, rootfs, pm, pm.remove + names)
                val left = names.filter { isInstalled(context, rootfs, pm, it) }
                if (left.isNotEmpty()) {
                    Timber.w("[LinuxAppUninstaller]: removing %s from %s:\n%s", left, distroId, output.takeLast(4_000))
                    throw IOException("Could not remove ${left.joinToString()} from ${distro.displayName}: ${LinuxDesktopSessions.lastLine(output)}")
                }
                Timber.i("[LinuxAppUninstaller]: removed %s from %s", names, distroId)
            }
            plan.files.forEach { file ->
                if (file.exists() && !file.delete()) throw IOException("Could not delete ${file.name}")
            }
            LinuxAppReconciler.scanAndReconcile(context)
        }.onFailure { Timber.e(it, "[LinuxAppUninstaller]: uninstall failed") }
    }

    /** The guest path of the AppImage behind [app], or null when it is not one. */
    private fun appImagePath(app: LinuxAppScanner.LinuxApp): String? {
        if (!app.entryId.substringAfterLast(':').startsWith("gn-appimage-")) return null
        return LinuxAppScanner.unescapeExecWord(app.exec.substringBefore(" --appimage"))
    }

    private fun owningPackage(context: Context, rootfs: File, pm: LinuxPackageManager, guestPath: String): String? {
        val command = when (pm) {
            LinuxPackageManager.APT -> "dpkg-query -S $guestPath"
            LinuxPackageManager.PACMAN -> "pacman -Qoq $guestPath"
            LinuxPackageManager.DNF -> "rpm -qf --qf %{NAME} $guestPath"
            LinuxPackageManager.APK -> "apk info -Wq $guestPath"
        }
        val output = LinuxProgramLauncher.runWithOutput(context, command, timeoutSeconds = 60, rootfs = rootfs)
        return parseOwner(pm, output, guestPath)
    }

    /** The package [output] names as owning [guestPath], or null when none does. */
    internal fun parseOwner(pm: LinuxPackageManager, output: String, guestPath: String): String? {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return when (pm) {
            // "pkg: /path", or "pkg1, pkg2: /path" when shared; diversion lines say "diversion by".
            LinuxPackageManager.APT -> lines.firstOrNull { it.endsWith(": $guestPath") && !it.startsWith("diversion") }
                ?.substringBefore(": ")?.split(", ")?.firstOrNull()?.substringBefore(':')
            else -> lines.firstOrNull { PACKAGE_NAME.matches(it) }
        }?.takeIf { PACKAGE_NAME.matches(it) }
    }

    private fun isInstalled(context: Context, rootfs: File, pm: LinuxPackageManager, name: String): Boolean {
        val command = when (pm) {
            LinuxPackageManager.APT -> "dpkg-query -W -f=\${Status} $name"
            LinuxPackageManager.PACMAN -> "pacman -Q $name"
            LinuxPackageManager.DNF -> "rpm -q $name"
            LinuxPackageManager.APK -> "apk info -e $name"
        }
        val output = LinuxProgramLauncher.runWithOutput(context, command, timeoutSeconds = 60, rootfs = rootfs)
        return when (pm) {
            LinuxPackageManager.APT -> output.contains("install ok installed")
            LinuxPackageManager.PACMAN -> output.lines().any { it.startsWith("$name ") }
            LinuxPackageManager.DNF -> output.lines().any { it.startsWith("$name-") }
            LinuxPackageManager.APK -> output.lines().any { it.trim() == name }
        }
    }

    private val PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9+._@-]*")
}
