package app.gamenative.linux

import android.content.Context
import android.graphics.Bitmap
import app.gamenative.stubs.StubRegistry
import app.gamenative.stubs.Stubs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * What a Linux application's all-apps entry is made of.
 *
 * The mechanics of building, installing and removing an entry belong to [Stubs] and are shared
 * with games; what is here is only what makes an entry a Linux one -- the command to run, the
 * icon read out of the userland, and how to tell that a desktop entry has changed since.
 */
object LinuxAppStubs {

    /** The command the trampoline hands back to us, read by the stub's LaunchActivity. */
    private const val META_ARGV = "app.gamenative.stub.ARGV"

    fun packageNameFor(app: LinuxAppScanner.LinuxApp): String = Stubs.packageNameFor(app.entryId)

    /** Whether a stub can be built at all, which needs a userland as well as the trampoline. */
    fun isSupported(context: Context): Boolean = LinuxRootfs.isSupported() && Stubs.isSupported(context)

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
        val packageName = packageNameFor(app)
        val versionCode = (StubRegistry.linux.of(context, app.entryId)?.versionCode ?: 0) + 1

        return runCatching {
            Stubs.build(
                context = context,
                packageName = packageName,
                label = app.name,
                versionCode = versionCode,
                metadata = mapOf(META_ARGV to app.launchArgv),
                iconPng = iconPng(context, app),
            ).getOrThrow()
        }
            .mapCatching { apk -> Stubs.install(context, apk).getOrThrow() }
            .onSuccess {
                StubRegistry.linux.record(
                    context,
                    StubRegistry.Record(
                        entryId = app.entryId,
                        packageName = packageName,
                        versionCode = versionCode,
                        fingerprint = contentFingerprint(context, app),
                    ),
                )
            }
    }

    /** Whether a stub for [app] is currently installed. */
    suspend fun isInstalled(context: Context, app: LinuxAppScanner.LinuxApp): Boolean =
        packageNameFor(app) in Stubs.installed(context, listOf(packageNameFor(app)))

    /**
     * Takes back the entry recorded for [entryId].
     *
     * The record is dropped only once the stub is known to be gone, so that a refused prompt does
     * not leave a package behind that nothing remembers.
     */
    suspend fun remove(context: Context, entryId: String, packageName: String): Result<Unit> =
        Stubs.remove(context, packageName).map { gone ->
            if (gone) StubRegistry.linux.forget(context, entryId)
        }

    /** [app]'s icon as a PNG, which a stub cannot do without. */
    private fun iconPng(context: Context, app: LinuxAppScanner.LinuxApp): ByteArray {
        val bitmap = LinuxAppIcon.load(context, app.iconPath) ?: throw IOException("no icon for ${app.name}")
        return ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
    }
}
