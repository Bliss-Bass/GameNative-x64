package app.gamenative.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import app.gamenative.linux.services.LinuxJobCallers
import app.gamenative.stubs.StubInstallerClient
import java.io.File

/**
 * What the Bass OS platform around this app provides.
 *
 * The same APK runs on Bass builds, where system counterparts ship alongside it, and as an ordinary
 * app anywhere else. Options that need those counterparts are shown only when they are present,
 * decided here at runtime rather than by build flavor.
 */
object PlatformCapabilities {

    /** Granted only to GameNative's release key, by the Bass stub installer that defines it. */
    const val PERMISSION_LINUX_VT = "bass.permission.LINUX_VT"

    private const val PROP_LINUX_VT = "ro.bass.linux_vt"

    /** The first console GameNative's Linux shells use (Ctrl+Alt+F4). */
    private const val FIRST_LINUX_VT_DEVICE = "/dev/tty4"

    data class Snapshot(
        /** Ctrl+Alt+F4-F6 Linux consoles: the platform pieces and access to the ttys. */
        val linuxVt: Boolean,
        /** The silent installer for drawer stubs. */
        val stubInstaller: Boolean,
        /** The Linux Services addon, which drives background jobs. */
        val linuxServices: Boolean,
    )

    @Volatile
    private var cached: Snapshot? = null

    /** The last snapshot, taken on first use. Call [refresh] on resume to pick up changes. */
    fun get(context: Context): Snapshot = cached ?: refresh(context)

    fun refresh(context: Context): Snapshot {
        val app = context.applicationContext
        val console = File(FIRST_LINUX_VT_DEVICE)
        return Snapshot(
            linuxVt = linuxVt(
                permissionGranted = app.checkSelfPermission(PERMISSION_LINUX_VT) == PackageManager.PERMISSION_GRANTED,
                buildProperty = systemProperty(PROP_LINUX_VT),
                consoleUsable = console.canRead() && console.canWrite(),
            ),
            stubInstaller = StubInstallerClient.isAvailable(app),
            linuxServices = isSystemPackage(app.packageManager, LinuxJobCallers.ADDON_PACKAGE),
        ).also { cached = it }
    }

    /**
     * All three must hold: the property says this build has the consoles, the permission says the
     * platform trusts this build of the app, and the tty being openable says both actually reached
     * the device (ueventd group, SELinux label).
     */
    internal fun linuxVt(permissionGranted: Boolean, buildProperty: String, consoleUsable: Boolean): Boolean =
        permissionGranted && buildProperty == "1" && consoleUsable

    private fun isSystemPackage(pm: PackageManager, pkg: String): Boolean {
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        return info.flags and ApplicationInfo.FLAG_SYSTEM != 0
    }

    private fun systemProperty(key: String): String = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, key, "") as? String
    }.getOrNull().orEmpty()
}
