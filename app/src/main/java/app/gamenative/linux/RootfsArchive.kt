package app.gamenative.linux

import android.system.ErrnoException
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import timber.log.Timber

/**
 * Fetches, verifies and unpacks a distro rootfs tarball, whichever distro it is.
 *
 * Not a general-purpose extractor -- [com.winlator.core.TarCompressorUtils] flattens every mode
 * to 0771 and cannot express hard links, which a distro rootfs needs.
 */
internal object RootfsArchive {

    fun download(url: String, dest: File, label: String, onProgress: (LinuxRootfs.Progress) -> Unit) {
        val client = OkHttpClient()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
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
                                LinuxRootfs.Progress(label, if (total > 0) written.toFloat() / total else -1f),
                            )
                        }
                    }
                }
            }
        }
    }

    fun sha256(file: File): String {
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

    /** Downloads [url] to [tarball] unless a copy matching [sha256] is already there. */
    fun fetchVerified(
        url: String,
        sha256: String,
        tarball: File,
        label: String,
        onProgress: (LinuxRootfs.Progress) -> Unit,
    ) {
        if (tarball.isFile && sha256(tarball) == sha256) return
        download(url, tarball, label, onProgress)
        val actual = sha256(tarball)
        if (actual != sha256) {
            tarball.delete()
            throw IOException("Rootfs checksum mismatch: expected $sha256, got $actual")
        }
    }

    /**
     * Unpacks [tarball], gzip or xz by its magic bytes, into [rootfs].
     *
     * [stripComponents] drops leading path elements, as tar's --strip does: proot-distro's
     * tarballs wrap the rootfs in one top-level directory. [ownerAccess] adds owner read and
     * write to every file. Everything here is owned by the app's uid, and PRoot's fake root
     * does not bypass host permissions, so a file shipped 0000 (Fedora's /etc/shadow) would
     * otherwise be unreadable even to the guest's root.
     */
    fun extract(tarball: File, rootfs: File, stripComponents: Int = 0, ownerAccess: Boolean = false) {
        TarArchiveInputStream(decompress(tarball.inputStream().buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry as TarArchiveEntry? ?: break
                val target = resolve(rootfs, entry.name, stripComponents) ?: continue

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
                        val source = resolve(rootfs, entry.linkName, stripComponents)
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
                        chmod(target, if (ownerAccess) entry.mode or 0x180 else entry.mode) // u+rw
                        // Kept, not left at unpack time: package managers send it as
                        // If-Modified-Since, and pacman then keeps the image's months-old
                        // package databases because the mirror answers "not modified".
                        target.setLastModified(entry.modTime.time)
                    }
                }
            }
        }
    }

    private fun decompress(input: BufferedInputStream): InputStream {
        input.mark(6)
        val magic = ByteArray(6)
        val read = input.read(magic)
        input.reset()
        val xz = read == 6 && magic.contentEquals(byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0))
        return if (xz) XZCompressorInputStream(input) else GzipCompressorInputStream(input)
    }

    /** Guards against entries escaping the rootfs via absolute paths or "..". */
    fun resolve(rootfs: File, name: String, stripComponents: Int = 0): File? {
        val cleaned = name.removePrefix("./").trimStart('/')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." }) {
            Timber.w("[LinuxRootfs]: skipping entry outside rootfs: %s", name)
            return null
        }
        if (parts.size <= stripComponents) return null
        return File(rootfs, parts.drop(stripComponents).joinToString("/"))
    }

    fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.absolutePath, mode and 0xFFF) }
    }

    /**
     * Deletes [dir] and everything in it, as [File.deleteRecursively] would if it could.
     *
     * Guest packages leave directories without owner write (Arch's `update-ca-trust` makes its
     * cadir 0555), and nothing in those can be unlinked until that is put back. Symlinks are
     * removed, never followed: a rootfs can link to the user's storage. True when [dir] is gone.
     */
    fun deleteTree(dir: File): Boolean {
        val root = dir.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    chmod(path.toFile(), 0x1C0) // u+rwx, so its entries can be listed and unlinked
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(path)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(path: Path, error: IOException): FileVisitResult {
                    runCatching { Files.deleteIfExists(path) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                    Files.deleteIfExists(path)
                    return FileVisitResult.CONTINUE
                }
            })
        }.onFailure { Timber.w(it, "[RootfsArchive]: could not delete all of %s", dir) }
        return !Files.exists(root, LinkOption.NOFOLLOW_LINKS)
    }

    /**
     * What every distro needs before PRoot can run it: name resolution, the app's own groups,
     * and the directories PRoot binds over.
     */
    fun prepareBase(rootfs: File) {
        File(rootfs, "etc").mkdirs()

        // Public resolvers rather than the system's: Android exposes its DNS servers only
        // through ConnectivityManager, and they change with the active network, whereas
        // this file is read once per guest process.
        val resolv = File(rootfs, "etc/resolv.conf")
        // Some images ship it as a symlink to systemd-resolved's stub, which never runs here.
        if (isSymlink(resolv)) resolv.delete()
        resolv.writeText(
            """
            nameserver 8.8.8.8
            nameserver 1.1.1.1
            """.trimIndent() + "\n",
        )

        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.isFile || hosts.length() == 0L) {
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

        // Directories PRoot binds over or the guest expects to exist.
        for (dir in listOf("dev", "proc", "sys", "tmp", "root", "run")) {
            File(rootfs, dir).mkdirs()
        }
        chmod(File(rootfs, "tmp"), 511) // 0777
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode) }.getOrDefault(false)

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
}
