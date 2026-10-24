package app.gamenative.linux

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * What a distro needs before its applications can open windows on Android X: settings for
 * scale, a session bus, the Pulse client, fonts the core protocol can draw, and an SVG loader.
 *
 * Ubuntu gets all of this, and more, from [LinuxRootfs.ensureDisplaySession]. Every other
 * integrated distro is brought up to the same point here, the first time one of its apps opens.
 */
object LinuxGraphics {

    private val lock = Mutex()

    fun packages(packageManager: LinuxPackageManager): List<String> = when (packageManager) {
        LinuxPackageManager.APT -> listOf(
            "ca-certificates", "dbus-x11", "libpulse0", "librsvg2-common", "xsettingsd", "xterm",
            "x11-xserver-utils", "xfonts-base", "fonts-dejavu-core",
        )
        LinuxPackageManager.PACMAN -> listOf(
            "ca-certificates", "dbus", "libpulse", "librsvg", "xsettingsd", "xterm",
            "xorg-xsetroot", "xorg-fonts-misc", "ttf-dejavu",
        )
        LinuxPackageManager.DNF -> listOf(
            "ca-certificates", "dbus-x11", "pulseaudio-libs", "librsvg2", "xsettingsd", "xterm",
            "xsetroot", "xorg-x11-fonts-misc", "dejavu-sans-fonts",
        )
        LinuxPackageManager.APK -> listOf(
            "ca-certificates", "dbus-x11", "libpulse", "librsvg", "xsettingsd", "xterm",
            "xsetroot", "font-misc-misc", "font-dejavu",
        )
    }

    /** Proof the packages landed, checked instead of parsing the package manager's output. */
    private val BINARIES = listOf("usr/bin/xterm", "usr/bin/xsetroot", "usr/bin/xsettingsd")

    private val PULSE_LIBRARIES = listOf(
        "usr/lib/x86_64-linux-gnu/libpulse.so.0",
        "usr/lib64/libpulse.so.0",
        "usr/lib/libpulse.so.0",
    )

    fun isReady(context: Context, distroId: String): Boolean {
        if (distroId == LinuxDistroCatalog.UBUNTU) return LinuxRootfs.hasDisplaySession(context)
        val rootfs = LinuxDistros.installedRootfs(context, distroId) ?: return false
        return isReady(rootfs)
    }

    private fun isReady(rootfs: File): Boolean =
        BINARIES.all { File(rootfs, it).exists() } && PULSE_LIBRARIES.any { File(rootfs, it).exists() }

    /** Installs what [distroId] is missing for app windows, and nothing if it is complete. */
    suspend fun ensure(
        context: Context,
        distroId: String,
        onProgress: (LinuxRootfs.Progress) -> Unit = {},
    ): Result<Unit> {
        if (distroId == LinuxDistroCatalog.UBUNTU) return LinuxRootfs.ensureDisplaySession(context, onProgress)
        return withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val distro = LinuxDistroCatalog.byId(distroId) ?: throw IOException("Unknown distro $distroId")
                    if (!distro.supported) throw IOException("${distro.displayName} is experimental")
                    val rootfs = LinuxDistros.installedRootfs(context, distroId)
                        ?: throw IOException("${distro.displayName} is not installed")
                    if (isReady(rootfs)) return@runCatching

                    val label = "Setting up app windows in ${distro.displayName}"
                    onProgress(LinuxRootfs.Progress(label, -1f))
                    val pm = distro.packageManager
                    pm.refresh?.let { LinuxDistros.runPackageManager(context, rootfs, pm, it) }
                    val output = LinuxDistros.runPackageManager(context, rootfs, pm, pm.install + packages(pm))
                    if (!isReady(rootfs)) {
                        Timber.w("[LinuxGraphics]: %s output:\n%s", label, output.takeLast(4_000))
                        throw IOException("$label failed: ${LinuxDesktopSessions.lastLine(output)}")
                    }
                    Timber.i("[LinuxGraphics]: app windows ready in %s", distroId)
                }.onFailure { Timber.e(it, "[LinuxGraphics]: setup in %s failed", distroId) }
            }
        }
    }
}
