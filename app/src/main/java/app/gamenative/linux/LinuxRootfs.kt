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
    // 4: installed without PRoot's --link2symlink, which had left .l2s symlinks pointing at
    // host paths the guest cannot resolve; an install made under it has a broken perl.
    private const val LAYOUT_VERSION = 4

    /**
     * What the graphical session is built from. All of it comes from apt, which is the
     * point of running a glibc userland: none of it has to be cross-compiled for bionic.
     *
     * - Xtigervnc is the X server and the RFB server in one process, so damage arrives as
     *   the server generates it rather than through a polling screen-scraper, and the
     *   screen can be resized at runtime -- neither of which Xvfb plus x11vnc can do.
     * - openbox is what makes a resize reach the application: X clients do not resize
     *   because the screen did, they resize when a window manager configures them.
     * - xsettingsd carries DPI changes into running GTK and Qt apps, which otherwise read
     *   it once at startup.
     */
    private val DISPLAY_PACKAGES = listOf(
        "tigervnc-standalone-server",
        "openbox",
        "xsettingsd",
    )

    /**
     * What apt needs to know it is running unattended.
     *
     * Without a frontend that never asks questions, a package whose postinst consults
     * debconf blocks forever: there is no terminal behind this, so the prompt is invisible
     * and unanswerable.
     */
    private val APT_ENV = mapOf(
        "DEBIAN_FRONTEND" to "noninteractive",
        "DEBCONF_NONINTERACTIVE_SEEN" to "true",
    )

    /** Proof the packages above landed, checked instead of parsing apt's output. */
    private val DISPLAY_BINARIES = listOf(
        "usr/bin/Xtigervnc",
        "usr/bin/openbox",
        "usr/bin/xsettingsd",
    )

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

    /** Whether the graphical session can be started, as opposed to just a shell. */
    fun hasDisplaySession(context: Context): Boolean =
        DISPLAY_BINARIES.all { File(rootfsDir(context), it).exists() }

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

                // Freed before apt runs: the packages below need the space more than a
                // tarball that is easy to fetch again.
                tarball.delete()

                installDisplaySession(context, rootfs, onProgress)

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

    /**
     * Installs and configures the graphical session with apt.
     *
     * Done here, while the user is already waiting on a progress bar, rather than on the
     * first launch of a Linux app: it needs the network either way, and the alternative is
     * a surprise download at the moment someone taps an icon. The version stamp covers it,
     * so "installed" stays a single question.
     */
    private fun installDisplaySession(context: Context, rootfs: File, onProgress: (Progress) -> Unit) {
        onProgress(Progress("Fetching package lists", -1f))
        // The base image ships no package lists at all, so this is not optional.
        val update = LinuxProgramLauncher.runWithOutput(
            context,
            "apt-get update",
            extraEnv = APT_ENV,
            timeoutSeconds = 600,
        )
        Timber.i("[LinuxRootfs]: apt-get update:\n%s", update.takeLast(2000))

        onProgress(Progress("Installing the graphical session", -1f))
        val install = LinuxProgramLauncher.runWithOutput(
            context,
            // Use-Pty=0 because there is no terminal here: with it on, dpkg tries to
            // allocate one and the install stops dead, waiting on a prompt nobody can see.
            "apt-get install -y -o Dpkg::Use-Pty=0 " + DISPLAY_PACKAGES.joinToString(" "),
            extraEnv = APT_ENV,
            timeoutSeconds = 1800,
        )
        Timber.i("[LinuxRootfs]: apt-get install:\n%s", install.takeLast(4000))

        // The .debs are no use once unpacked, and this reclaims a hundred megabytes or so
        // of the app's storage.
        LinuxProgramLauncher.runWithOutput(context, "apt-get clean", extraEnv = APT_ENV, timeoutSeconds = 120)

        val missing = DISPLAY_BINARIES.filterNot { File(rootfs, it).exists() }
        if (missing.isNotEmpty()) {
            throw IOException("Graphical session install incomplete, missing: ${missing.joinToString()}")
        }

        configureWindowManager(rootfs)
        writeXsettings(rootfs, context.resources.displayMetrics.densityDpi)
    }

    /**
     * Openbox, told to fill the screen and draw nothing of its own.
     *
     * Maximized so that a window follows the desktop when Android resizes it, and
     * undecorated because a title bar inside an Android window is a second set of controls
     * for the same window. Apps that draw their own decorations still show theirs.
     */
    private fun configureWindowManager(rootfs: File) {
        val stock = File(rootfs, "etc/xdg/openbox/rc.xml")
        val target = File(rootfs, "root/.config/openbox/rc.xml").apply { parentFile?.mkdirs() }
        if (!stock.isFile) {
            Timber.w("[LinuxRootfs]: no stock openbox rc.xml; leaving defaults")
            return
        }

        val rule = """
            <applications>
              <application class="*">
                <maximized>yes</maximized>
                <decor>no</decor>
              </application>
        """.trimIndent() + "\n"

        val text = stock.readText()
        // Amending the shipped file rather than writing one: rc.xml carries keybindings and
        // theme defaults that openbox needs, and a hand-written minimal one loses them.
        target.writeText(
            if (text.contains("<applications>")) {
                text.replaceFirst("<applications>", rule)
            } else {
                Timber.w("[LinuxRootfs]: openbox rc.xml has no <applications> section")
                text
            },
        )
    }

    /**
     * Default XSETTINGS, so text is the right physical size on a dense screen.
     *
     * X clients assume 96 DPI and would draw at about half size on this hardware. Android's
     * densityDpi is a bucketed approximation of the panel's real density, which is close
     * enough for type; the session rewrites this file and signals xsettingsd if the density
     * ever changes under it.
     */
    private fun writeXsettings(rootfs: File, densityDpi: Int) {
        val dpi = densityDpi.coerceIn(96, 400)
        File(rootfs, "root/.xsettingsd").writeText(
            """
            Xft/DPI ${dpi * 1024}
            Xft/Antialias 1
            Xft/Hinting 1
            Xft/HintStyle "hintslight"
            Xft/RGBA "rgb"
            Gdk/WindowScalingFactor 1
            """.trimIndent() + "\n",
        )
        Timber.i("[LinuxRootfs]: xsettingsd configured for %d dpi", dpi)
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
     * Post-unpack tweaks that make the userland usable: name resolution, letting apt run as
     * root instead of dropping to the _apt user (which it cannot do under PRoot), and
     * skipping translated package descriptions, which are 400MB of this device's storage
     * for text nothing here ever displays.
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
            Acquire::Languages "none";
            """.trimIndent() + "\n",
        )

        // Directories PRoot binds over or the guest expects to exist.
        for (dir in listOf("dev", "proc", "sys", "tmp", "root", "run")) {
            File(rootfs, dir).mkdirs()
        }
        chmod(File(rootfs, "tmp"), 511) // 0777
    }
}
