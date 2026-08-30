package app.gamenative.stubs

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import app.gamenative.stubapk.ApkSigner
import app.gamenative.stubapk.StubApk
import app.gamenative.stubapk.StubSigningKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Building, installing and removing the packages that give something an entry in Android's
 * all-apps list.
 *
 * Android shows only installed packages there, so anything that wants an entry needs one of its
 * own: a few kilobyte package holding an icon, a label and a class that hands the launch straight
 * back to us. This is what a pinned shortcut cannot do -- a shortcut lives on the home screen, at
 * the launcher's discretion, and never appears in the drawer or in search.
 *
 * What a stub stands for is not this object's concern: a Linux application and an installed game
 * differ only in the meta-data they carry and the icon they are built from. Stubs are generated
 * and signed here rather than by the privileged helper that installs them, so a GameNative running
 * without our ROM can still offer this through the ordinary install prompt.
 */
object Stubs {

    /** The namespace every stub's package name falls in, which the ROM's installer enforces too. */
    const val PACKAGE_PREFIX = "app.gamenative.stub."

    /** The only activity a stub declares, and the one the installer will accept. */
    private const val TRAMPOLINE_CLASS = "app.gamenative.stub.LaunchActivity"

    /** Where the trampoline dex is packaged, built by the stubTrampolineDex task. */
    private const val TRAMPOLINE_ASSET = "stub/trampoline.dex"

    /** Whether a stub can be built at all, which needs the packaged trampoline. */
    fun isSupported(context: Context): Boolean = runCatching {
        context.assets.open(TRAMPOLINE_ASSET).close()
    }.isSuccess

    /**
     * A package name in our namespace for [entryId].
     *
     * Derived from what the stub stands for rather than allocated, so rebuilding a stub updates
     * the installed one instead of adding a second, and the reverse lookup needs no bookkeeping.
     */
    fun packageNameFor(entryId: String): String =
        PACKAGE_PREFIX + entryId.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    /**
     * Builds and signs a stub, returning the APK.
     *
     * [versionCode] has to rise for an install over an existing stub to be accepted, which is how
     * a changed label or icon reaches the launcher.
     */
    suspend fun build(
        context: Context,
        packageName: String,
        label: String,
        versionCode: Int,
        metadata: Map<String, String>,
        iconPng: ByteArray,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dex = context.assets.open(TRAMPOLINE_ASSET).use { it.readBytes() }

            val unsigned = StubApk.build(
                StubApk.Spec(
                    packageName = packageName,
                    label = label,
                    versionCode = versionCode,
                    activityClass = TRAMPOLINE_CLASS,
                    metadata = metadata,
                    iconPng = iconPng,
                    dex = dex,
                ),
            )

            val key = StubSigningKey.get()
            val signed = ApkSigner.sign(unsigned, key.privateKey, key.certificate)

            val out = File(context.cacheDir, "stubs").apply { mkdirs() }.resolve("$packageName.apk")
            out.writeBytes(signed)
            Timber.i("[Stubs]: built %s (%d bytes)", out.name, signed.size)
            out
        }
    }

    /**
     * Installs [apk], through the ROM's installer where there is one.
     *
     * On our ROM that completes silently. Elsewhere it falls back to asking, which costs the user
     * the unknown-sources setting, a confirmation and, with Play services present, a Play Protect
     * scan -- unavoidable for an app that is not part of the system image.
     */
    suspend fun install(context: Context, apk: File): Result<Unit> {
        StubInstallerClient.install(context, apk)?.let { result ->
            // An install can outlast our wait for its result, so before believing a failure, ask
            // what is actually on the device. Asked of the installer rather than the package
            // manager, which hides packages we have not declared an interest in.
            if (result.isFailure &&
                StubInstallerClient.installedStubs(context)?.contains(apk.nameWithoutExtension) == true
            ) {
                Timber.i("[Stubs]: %s installed despite the reported failure", apk.name)
                return Result.success(Unit)
            }
            return result
        }

        Timber.i("[Stubs]: no ROM installer, asking the user instead")
        return requestInstall(context, apk)
    }

    /**
     * Asks the user to install [apk].
     *
     * Through a PackageInstaller session rather than by handing the file to a viewer: an intent
     * carrying an APK offers the user a choice of anything that claims the type, which on this
     * device means a file manager or a terminal before the installer. A session names the
     * installer directly.
     */
    private suspend fun requestInstall(context: Context, apk: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(apk.nameWithoutExtension) }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(statusReceiver(context, sessionId).intentSender)
            }
            Timber.i("[Stubs]: committed install session %d for %s", sessionId, apk.name)
        }
    }

    /**
     * Where the installer reports back to.
     *
     * Mutable, because the session id is what tells one install from another, and immutable
     * pending intents to the same component would collapse into one.
     */
    private fun statusReceiver(context: Context, sessionId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            // Names the class, not just an action: the receiver declares no filter, so there is
            // nothing for an action to resolve against.
            Intent(context, StubInstallReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Which of [packageNames] are on the device.
     *
     * Asked of the ROM's installer where there is one, because it owns what it installed and
     * Android hides those packages from us. Where there is not, we installed them ourselves and
     * the package manager will answer.
     */
    suspend fun installed(context: Context, packageNames: Collection<String>): Set<String> {
        StubInstallerClient.installedStubs(context)?.let { stubs ->
            return packageNames.intersect(stubs.toSet())
        }

        return packageNames.filterTo(mutableSetOf()) {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }
    }

    /**
     * Removes [packageName], reporting whether it is certainly gone.
     *
     * False means the user was asked and the answer is unknown, which is the caller's cue to keep
     * its record: dropping it on a refused prompt would leave a package behind that nothing
     * remembers, and so nothing would ever clean up.
     */
    suspend fun remove(context: Context, packageName: String): Result<Boolean> {
        StubInstallerClient.uninstall(context, packageName)?.let { result ->
            return result.map { true }
        }

        requestUninstall(context, packageName)
        return Result.success(false)
    }

    /** Packages the user has already been asked about, so a rescan does not ask again. */
    private val asked = mutableSetOf<String>()

    /**
     * Asks the user to remove [packageName], at most once while we are running.
     *
     * Without the ROM's installer there is no way to remove a package quietly, and a removal the
     * user declines would otherwise come back on every reconcile.
     */
    @Synchronized
    private fun requestUninstall(context: Context, packageName: String) {
        if (!asked.add(packageName)) return

        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
