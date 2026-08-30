package app.gamenative.linux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import app.gamenative.stubapk.ApkSigner
import app.gamenative.stubapk.StubApk
import app.gamenative.stubapk.StubSigningKey
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Gives a Linux application an entry in Android's all-apps list.
 *
 * Android shows only installed packages there, so each application that wants an entry needs one
 * of its own: a few kilobyte package holding an icon, a label and a class that hands the command
 * straight back to us. This is what a pinned shortcut cannot do -- a shortcut lives on the home
 * screen, at the launcher's discretion, and never appears in the drawer or in search.
 *
 * The stub is generated and signed here rather than by the privileged helper that installs it,
 * so that a GameNative running without our ROM can still offer this through the ordinary install
 * prompt.
 */
object LinuxAppStubs {

    /** Where the trampoline dex is packaged, built by the stubTrampolineDex task. */
    private const val TRAMPOLINE_ASSET = "stub/trampoline.dex"

    private const val TRAMPOLINE_CLASS = "app.gamenative.stub.LaunchActivity"
    private const val META_ARGV = "app.gamenative.stub.ARGV"

    private const val PACKAGE_PREFIX = "app.gamenative.stub."

    /**
     * The package name a stub for [app] gets.
     *
     * Derived from the desktop entry rather than allocated, so regenerating a stub updates the
     * installed one instead of adding a second, and so the reverse lookup needs no bookkeeping.
     */
    fun packageNameFor(app: LinuxAppScanner.LinuxApp): String =
        PACKAGE_PREFIX + app.entryId.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    /** Whether a stub can be built at all, which needs the packaged trampoline. */
    fun isSupported(context: Context): Boolean =
        LinuxRootfs.isSupported() && runCatching {
            context.assets.open(TRAMPOLINE_ASSET).close()
        }.isSuccess

    /**
     * A summary of everything about [app] that ends up inside its stub, so that a later scan can
     * tell whether the installed one is still right.
     *
     * The icon is summarised by its file rather than its contents: decoding every icon on every
     * pass to hash it would cost more than the change is worth, and a package that replaces an
     * icon replaces the file.
     */
    fun contentFingerprint(context: Context, app: LinuxAppScanner.LinuxApp): String {
        val icon = app.iconPath?.let { File(LinuxRootfs.rootfsDir(context), it.removePrefix("/")) }

        val summary = listOf(
            app.name,
            app.launchArgv,
            app.iconPath.orEmpty(),
            icon?.length()?.toString().orEmpty(),
            icon?.lastModified()?.toString().orEmpty(),
        ).joinToString("\u0000")

        return MessageDigest.getInstance("SHA-256")
            .digest(summary.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Gives [app] an entry in the all-apps list, and remembers having done so.
     *
     * Also the path a changed entry takes: the version code rises above the recorded one, which is
     * what lets the install replace the stub already there.
     */
    suspend fun add(context: Context, app: LinuxAppScanner.LinuxApp): Result<Unit> {
        val previous = LinuxAppStubRegistry.of(context, app.entryId)
        val versionCode = (previous?.versionCode ?: 0) + 1

        return build(context, app, versionCode)
            .mapCatching { apk -> install(context, apk).getOrThrow() }
            .onSuccess {
                LinuxAppStubRegistry.record(
                    context,
                    LinuxAppStubRegistry.Record(
                        entryId = app.entryId,
                        packageName = packageNameFor(app),
                        versionCode = versionCode,
                        fingerprint = contentFingerprint(context, app),
                    ),
                )
            }
    }

    /**
     * Builds and signs a stub for [app], returning the APK.
     *
     * [versionCode] has to rise for an install over an existing stub to be accepted, which is
     * how a changed label or icon reaches the launcher.
     */
    suspend fun build(
        context: Context,
        app: LinuxAppScanner.LinuxApp,
        versionCode: Int = 1,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dex = context.assets.open(TRAMPOLINE_ASSET).use { it.readBytes() }
            val icon = LinuxAppIcon.load(context, app.iconPath)
                ?.let { bitmap ->
                    java.io.ByteArrayOutputStream().also {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }.toByteArray()
                }
                ?: throw IOException("no icon for ${app.name}")

            val unsigned = StubApk.build(
                StubApk.Spec(
                    packageName = packageNameFor(app),
                    label = app.name,
                    versionCode = versionCode,
                    activityClass = TRAMPOLINE_CLASS,
                    metadata = mapOf(META_ARGV to app.launchArgv),
                    iconPng = icon,
                    dex = dex,
                ),
            )

            val key = StubSigningKey.get()
            val signed = ApkSigner.sign(unsigned, key.privateKey, key.certificate)

            val out = File(context.cacheDir, "stubs").apply { mkdirs() }
                .resolve("${packageNameFor(app)}.apk")
            out.writeBytes(signed)
            Timber.i("[LinuxAppStubs]: built %s (%d bytes)", out.name, signed.size)
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
                Timber.i("[LinuxAppStubs]: %s installed despite the reported failure", apk.name)
                return Result.success(Unit)
            }
            return result
        }

        Timber.i("[LinuxAppStubs]: no ROM installer, asking the user instead")
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
            Timber.i("[LinuxAppStubs]: committed install session %d for %s", sessionId, apk.name)
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

    /** Whether a stub for [app] is currently installed. */
    suspend fun isInstalled(context: Context, app: LinuxAppScanner.LinuxApp): Boolean =
        packageNameFor(app) in installed(context, listOf(packageNameFor(app)))

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
     * Takes back the entry recorded for [entryId].
     *
     * The record is dropped only once the stub is known to be gone, so that a refused prompt does
     * not leave a package behind that nothing remembers.
     */
    suspend fun remove(context: Context, entryId: String, packageName: String): Result<Unit> {
        StubInstallerClient.uninstall(context, packageName)?.let { result ->
            return result.onSuccess { LinuxAppStubRegistry.forget(context, entryId) }
        }

        requestUninstall(context, packageName)
        return Result.success(Unit)
    }

    /** Packages the user has already been asked about, so a rescan does not ask again. */
    private val asked = mutableSetOf<String>()

    /**
     * Asks the user to remove [packageName], at most once while we are running.
     *
     * Without the ROM's installer there is no way to remove a package quietly, and a removal the
     * user declines would otherwise come back on every rescan.
     */
    @Synchronized
    private fun requestUninstall(context: Context, packageName: String) {
        if (!asked.add(packageName)) return

        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = android.net.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
