package app.gamenative.linux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.gamenative.stubs.IStubInstaller
import app.gamenative.stubs.IStubInstallerCallback
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * The ROM's stub installer, when the ROM has one.
 *
 * A system service holding INSTALL_PACKAGES, which is what lets a Linux application's launcher
 * entry appear without the user first allowing installs from GameNative, then confirming, then
 * getting past a Play Protect scan. Absent on a plain Android device, where those three prompts
 * are the only way through and [LinuxAppStubs] asks for them instead.
 *
 * It will only install a package that is a launcher entry and nothing else, so a failure here is
 * worth reporting rather than papering over: it means the stub we built is not the shape the
 * installer expects.
 */
object StubInstallerClient {

    private const val ACTION = "app.gamenative.stubs.StubInstaller"
    private const val PACKAGE = "app.gamenative.stubs"

    /** Long enough for a package install, short enough not to hang the caller for good. */
    private const val TIMEOUT_MS = 60_000L

    /** Whether this device has the installer. */
    fun isAvailable(context: Context): Boolean = intent(context) != null

    /**
     * Installs [apk] through the installer.
     *
     * Returns null when the installer is not there, so the caller can tell "no ROM support" from
     * "the ROM refused", which are different situations with different remedies.
     */
    suspend fun install(context: Context, apk: File): Result<Unit>? {
        val intent = intent(context) ?: return null

        return connect(context, intent) { installer ->
            awaitResult { callback ->
                ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    installer.install(fd, callback)
                }
            }
        }
    }

    /** Removes a stub through the installer, or null when it is not there. */
    suspend fun uninstall(context: Context, packageName: String): Result<Unit>? {
        val intent = intent(context) ?: return null

        return connect(context, intent) { installer ->
            awaitResult { callback -> installer.uninstall(packageName, callback) }
        }
    }

    /** The stubs the installer reports as installed, or null when it is not there. */
    suspend fun installedStubs(context: Context): List<String>? {
        val intent = intent(context) ?: return null

        return connect(context, intent) { installer ->
            runCatching { installer.installedStubs() }
                .onFailure { Timber.e(it, "[StubInstaller]: could not list stubs") }
                .getOrNull()
        }
    }

    /** The intent that reaches the installer, or null when nothing serves it. */
    private fun intent(context: Context): Intent? {
        val intent = Intent(ACTION).setPackage(PACKAGE)
        return intent.takeIf {
            context.packageManager.resolveService(it, 0) != null
        }
    }

    /**
     * Binds for the duration of [block].
     *
     * Bound per request rather than kept: adding a launcher entry is something a person does
     * occasionally, and holding a binding to a system service between those moments only keeps
     * its process alive for nothing.
     */
    private suspend fun <T> connect(
        context: Context,
        intent: Intent,
        block: suspend (IStubInstaller) -> T,
    ): T? {
        var connection: ServiceConnection? = null
        try {
            val binder = withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val serviceConnection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            if (continuation.isActive) continuation.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit

                        override fun onNullBinding(name: ComponentName?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                    connection = serviceConnection

                    if (!context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                        connection = null
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }

            if (binder == null) {
                Timber.w("[StubInstaller]: could not bind the installer")
                return null
            }
            return block(IStubInstaller.Stub.asInterface(binder))
        } finally {
            connection?.let { runCatching { context.unbindService(it) } }
        }
    }

    /** Runs [request] and waits for the single callback it will produce. */
    private suspend fun awaitResult(request: (IStubInstallerCallback) -> Unit): Result<Unit> =
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : IStubInstallerCallback.Stub() {
                    override fun onResult(packageName: String?, status: Int, message: String?) {
                        if (!continuation.isActive) return
                        continuation.resume(
                            if (status == PackageInstaller.STATUS_SUCCESS) {
                                Result.success(Unit)
                            } else {
                                Result.failure(
                                    IllegalStateException(
                                        "the installer refused $packageName ($status): $message",
                                    ),
                                )
                            },
                        )
                    }
                }

                runCatching { request(callback) }
                    .onFailure { if (continuation.isActive) continuation.resume(Result.failure(it)) }
            }
        } ?: Result.failure(IllegalStateException("the installer did not answer"))
}
