package app.gamenative.linux.services

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import timber.log.Timber

/**
 * Who may drive the job engine: the Linux Services addon, and only as shipped in the system image.
 *
 * The engine runs arbitrary commands as this app, so an open binder would hand that to every app on
 * the device. A permission cannot close it: this is a user app, and a permission it declares binds
 * nothing a privileged caller must hold. What a user app can check is the caller's package, that
 * the package is part of the system image, and that it is signed with the platform key -- which no
 * installable app can fake.
 */
object LinuxJobCallers {

    const val ADDON_PACKAGE = "org.ax86.linuxservices"

    fun isAllowed(context: Context, uid: Int): Boolean {
        if (uid == Process.myUid()) return true
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid).orEmpty()
        val allowed = packages.any { pkg -> pkg == ADDON_PACKAGE && isPlatformSystemApp(pm, pkg) }
        if (!allowed) Timber.w("[LinuxJobCallers]: refused uid %d (%s)", uid, packages.joinToString())
        return allowed
    }

    private fun isPlatformSystemApp(pm: PackageManager, pkg: String): Boolean {
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return false
        if (info.flags and ApplicationInfo.FLAG_SYSTEM == 0) return false
        return pm.checkSignatures("android", pkg) == PackageManager.SIGNATURE_MATCH
    }
}
