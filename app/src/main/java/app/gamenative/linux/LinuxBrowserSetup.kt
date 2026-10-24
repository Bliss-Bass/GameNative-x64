package app.gamenative.linux

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * Lets the browsers a user installs in Debian or Arch run there, as [LinuxRootfs] does for
 * Ubuntu at each session start.
 *
 * Chromium and Chrome exit at once as root unless told `--no-sandbox`, and every guest process
 * is root under PRoot; each distro's launcher has its own place for flags. Debian also gets
 * Google's Chrome repository, so `apt install google-chrome-stable` works there as on Ubuntu.
 * Safe to run often: files are rewritten, and the network is used only when a source is missing.
 */
object LinuxBrowserSetup {

    /** Read by Arch's /usr/bin/chromium launcher, one flag per line. */
    private const val ARCH_CHROMIUM_FLAGS = "root/.config/chromium-flags.conf"

    fun configure(context: Context, distroId: String) {
        if (distroId == LinuxDistroCatalog.UBUNTU) return
        val distro = LinuxDistroCatalog.byId(distroId)?.takeIf { it.supported } ?: return
        val rootfs = LinuxDistros.installedRootfs(context, distroId) ?: return
        runCatching {
            when (distro.packageManager) {
                LinuxPackageManager.APT -> configureApt(context, rootfs)
                LinuxPackageManager.PACMAN -> configurePacman(rootfs)
                LinuxPackageManager.DNF, LinuxPackageManager.APK -> Unit
            }
        }.onFailure { Timber.w(it, "[LinuxBrowserSetup]: could not set up browsers in %s", distroId) }
    }

    private fun configureApt(context: Context, rootfs: File) {
        LinuxRootfs.ensureChromiumFlags(rootfs)
        if (LinuxRootfs.ensureGoogleChromeSource(rootfs) == LinuxRootfs.SourceState.ADDED) {
            val update = LinuxDistros.runPackageManager(context, rootfs, LinuxPackageManager.APT, listOf("apt-get", "update"))
            Timber.i("[LinuxBrowserSetup]: added Google's Chrome source:\n%s", update.takeLast(1_000))
        }
        LinuxRootfs.registerChromeDiversion(context, rootfs)
        LinuxRootfs.ensureChromeLaunchWrapper(rootfs)
    }

    private fun configurePacman(rootfs: File) {
        val flags = File(rootfs, ARCH_CHROMIUM_FLAGS)
        flags.parentFile?.mkdirs()
        flags.writeText(
            "# Written by GameNative. See LinuxBrowserSetup.\n" +
                LinuxRootfs.CHROMIUM_FAMILY_FLAGS.split(' ').joinToString("\n", postfix = "\n"),
        )
    }
}
