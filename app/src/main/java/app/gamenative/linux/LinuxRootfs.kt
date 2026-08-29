package app.gamenative.linux

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import timber.log.Timber

/**
 * Downloads and unpacks the Linux userland that PRoot runs.
 *
 * The tarball is fetched on first use rather than bundled: it is ~30MB compressed and
 * ~"a few hundred" MB expanded, and the APK already carries a Vulkan payload.
 *
 * Not a general-purpose extractor -- [TarCompressorUtils] flattens every mode to 0771
 * and cannot express hard links, which a distro rootfs needs.
 */
object LinuxRootfs {

    /** Bump when the tarball or the post-unpack tweaks below change; forces a re-install. */
    private const val LAYOUT_VERSION = 2

    private const val URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz"
    private const val SHA256 = "6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9"

    /** Roughly what the expanded rootfs needs, plus room for a few packages. */
    const val REQUIRED_BYTES = 1_200L * 1024 * 1024

    class UnsupportedAbiException : IOException("The Linux userland requires an x86_64 device")

    data class Progress(val message: String, val fraction: Float)

    fun rootDir(context: Context): File = File(context.filesDir, "linux")

    fun rootfsDir(context: Context): File = File(rootDir(context), "rootfs")

    private fun stampFile(context: Context): File = File(rootDir(context), ".rootfs_version")

    fun isSupported(): Boolean = Build.SUPPORTED_ABIS.contains("x86_64")

    fun isInstalled(context: Context): Boolean =
        stampFile(context).takeIf { it.isFile }?.readText()?.trim() == LAYOUT_VERSION.toString() &&
            File(rootfsDir(context), "bin/bash").exists()

    /**
     * Fetches and unpacks the rootfs, replacing any existing one. Safe to re-run: the
     * stamp is written last, so an interrupted install is retried rather than trusted.
     */
    suspend fun install(context: Context, onProgress: (Progress) -> Unit = {}): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!isSupported()) throw UnsupportedAbiException()

                val root = rootDir(context).apply { mkdirs() }
                val rootfs = rootfsDir(context)
                val tarball = File(context.cacheDir, "ubuntu-base.tar.gz")

                if (!(tarball.isFile && sha256(tarball) == SHA256)) {
                    download(tarball, onProgress)
                    val actual = sha256(tarball)
                    if (actual != SHA256) {
                        tarball.delete()
                        throw IOException("Rootfs checksum mismatch: expected $SHA256, got $actual")
                    }
                }

                onProgress(Progress("Unpacking Linux userland", -1f))
                stampFile(context).delete()
                if (rootfs.exists()) rootfs.deleteRecursively()
                rootfs.mkdirs()
                extract(tarball, rootfs)

                prepare(rootfs)

                // Keep the download only if space is plentiful; it is easy to re-fetch.
                tarball.delete()

                stampFile(context).writeText(LAYOUT_VERSION.toString())
                Timber.i("[LinuxRootfs]: installed to %s", rootfs)
            }
        }

    fun uninstall(context: Context) {
        stampFile(context).delete()
        rootfsDir(context).deleteRecursively()
    }

    private fun download(dest: File, onProgress: (Progress) -> Unit) {
        val client = OkHttpClient()
        val response = client.newCall(Request.Builder().url(URL).build()).execute()
        response.use {
            if (!it.isSuccessful) throw IOException("Rootfs download failed: HTTP ${it.code}")
            val body = it.body
            val total = body.contentLength()
            var written = 0L
            var lastReported = 0L

            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        // Report per megabyte; the UI does not need finer granularity and
                        // the callback may hop threads.
                        if (written - lastReported >= 1024 * 1024) {
                            lastReported = written
                            onProgress(
                                Progress(
                                    "Downloading Linux userland",
                                    if (total > 0) written.toFloat() / total else -1f,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extract(tarball: File, rootfs: File) {
        TarArchiveInputStream(GzipCompressorInputStream(tarball.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry as TarArchiveEntry? ?: break
                val target = resolve(rootfs, entry.name) ?: continue

                when {
                    entry.isDirectory -> target.mkdirs()

                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        target.delete()
                        // Link targets are kept verbatim, including absolute ones: they are
                        // resolved inside the guest, where "/" is the rootfs.
                        runCatching { Os.symlink(entry.linkName, target.absolutePath) }
                            .onFailure { Timber.w("[LinuxRootfs]: symlink %s failed: %s", entry.name, it) }
                    }

                    entry.isLink -> {
                        val source = resolve(rootfs, entry.linkName)
                        target.parentFile?.mkdirs()
                        target.delete()
                        if (source != null && source.exists()) {
                            // Fall back to a copy: hard links across some filesystems fail,
                            // and a duplicate is correct if wasteful.
                            try {
                                Os.link(source.absolutePath, target.absolutePath)
                            } catch (e: ErrnoException) {
                                Timber.w("[LinuxRootfs]: hardlink %s -> %s failed (%s), copying", entry.name, entry.linkName, e.message)
                                source.copyTo(target, overwrite = true)
                            }
                        }
                    }

                    // Device nodes and FIFOs cannot be created without privileges; /dev is
                    // bind-mounted from Android instead.
                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> Unit

                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { tar.copyTo(it) }
                        chmod(target, entry.mode)
                    }
                }
            }
        }
    }

    /** Guards against entries escaping the rootfs via absolute paths or "..". */
    private fun resolve(rootfs: File, name: String): File? {
        val cleaned = name.removePrefix("./").trimStart('/')
        if (cleaned.isEmpty()) return null
        if (cleaned.split('/').any { it == ".." }) {
            Timber.w("[LinuxRootfs]: skipping entry outside rootfs: %s", name)
            return null
        }
        return File(rootfs, cleaned)
    }

    /** This process's gids: the real one plus the supplementary groups Android grants. */
    private fun androidGids(): List<Int> {
        val supplementary = runCatching {
            File("/proc/self/status").readLines()
                .first { it.startsWith("Groups:") }
                .removePrefix("Groups:")
                .trim()
                .split(Regex("\\s+"))
                .mapNotNull(String::toIntOrNull)
        }.getOrDefault(emptyList())
        return (listOf(Os.getgid()) + supplementary).distinct()
    }

    private fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.absolutePath, mode and 0xFFF) }
    }

    /**
     * Post-unpack tweaks that make the userland usable: name resolution, and letting apt
     * run as root instead of dropping to the _apt user, which it cannot do under PRoot.
     */
    private fun prepare(rootfs: File) {
        File(rootfs, "etc").mkdirs()

        // Public resolvers rather than the system's: Android exposes its DNS servers only
        // through ConnectivityManager, and they change with the active network, whereas
        // this file is read once per guest process.
        File(rootfs, "etc/resolv.conf").writeText(
            """
            nameserver 8.8.8.8
            nameserver 1.1.1.1
            """.trimIndent() + "\n",
        )

        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.isFile) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }

        // PRoot fakes uid 0 but passes the app's supplementary groups through, and a login
        // shell prints "cannot find name for group ID" for each one it cannot resolve.
        // They are the app's own gids, so they are only knowable here, at install time.
        val group = File(rootfs, "etc/group")
        if (group.isFile) {
            val known = group.readLines().mapNotNull { it.split(':').getOrNull(2) }.toSet()
            val added = androidGids().filterNot { it.toString() in known }
            if (added.isNotEmpty()) {
                group.appendText(added.joinToString("") { "android_$it:x:$it:\n" })
            }
        }

        File(rootfs, "etc/apt/apt.conf.d").mkdirs()
        File(rootfs, "etc/apt/apt.conf.d/99gamenative").writeText(
            """
            APT::Sandbox::User "root";
            APT::Install-Recommends "false";
            """.trimIndent() + "\n",
        )

        // Directories PRoot binds over or the guest expects to exist.
        for (dir in listOf("dev", "proc", "sys", "tmp", "root", "run")) {
            File(rootfs, dir).mkdirs()
        }
        chmod(File(rootfs, "tmp"), 511) // 0777
    }
}
