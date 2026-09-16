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
     * Bump when Mozilla/nosnap apt policy files change. Applied without re-unpacking the
     * rootfs: [ensureDisplaySession] rewrites the files and, when the stamp advances, runs
     * apt once to drop snap stubs and prefer Mozilla's firefox deb.
     */
    private const val APT_POLICY_VERSION = 2

    private const val MOZILLA_KEY_URL = "https://packages.mozilla.org/apt/repo-signing-key.gpg"
    private const val MOZILLA_KEY_PATH = "etc/apt/keyrings/packages.mozilla.org.asc"
    private const val MOZILLA_SOURCES_PATH = "etc/apt/sources.list.d/mozilla.sources"
    private const val MOZILLA_PIN_PATH = "etc/apt/preferences.d/mozilla"
    private const val NOSNAP_PIN_PATH = "etc/apt/preferences.d/nosnap"

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
     * - xterm is what openbox's root menu means by a terminal, and without it the desktop
     *   opens onto a background with no way to start anything from inside it.
     * - tint2 is the desktop session's panel. Openbox has no panel of its own, and a root
     *   menu alone means a long press for everything on a screen with no right button.
     * - x11-xserver-utils is here for xsetroot, which paints the root window: the X server's
     *   own default is a black-and-white weave from the 1980s.
     * - wmctrl and x11-utils (for xprop) are what make an application fill its Android window
     *   even when openbox's own rule cannot. See [LinuxDesktopConfig.FIT_WINDOWS].
     * - The font packages are not optional here: Recommends are off, so nothing else pulls
     *   them in, and both a bitmap font for xterm and a scalable one for everything that
     *   draws through Xft have to be present or clients fail to start on a missing font.
     * - ca-certificates is required before any HTTPS APT source (Mozilla) can be fetched:
     *   ubuntu-base ships none, and apt-get update against packages.mozilla.org fails without it.
     * - dbus-x11 provides dbus-launch. Firefox warns (and a11y fails) without a session bus
     *   helper; Recommends are off so nothing else pulls it in.
     * - libpulse0 is the Cubeb/Pulse client. The server is the host AAudio daemon ([LinuxPulse]);
     *   without the client library Firefox never reaches it and YouTube stalls with no playback.
     */
    private val DISPLAY_PACKAGES = listOf(
        "ca-certificates",
        "dbus-x11",
        "libpulse0",
        "tigervnc-standalone-server",
        "openbox",
        "xsettingsd",
        "xterm",
        "tint2",
        "x11-xserver-utils",
        "x11-utils",
        "wmctrl",
        "xfonts-base",
        "fonts-dejavu-core",
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

    /** Debconf answers, written at install time and fed to apt before it unpacks anything. */
    private const val PRESEED = "root/.gamenative-preseed"

    /** Proof the packages above landed, checked instead of parsing apt's output. */
    private val DISPLAY_BINARIES = listOf(
        "usr/bin/Xtigervnc",
        "usr/bin/openbox",
        "usr/bin/xsettingsd",
        "usr/bin/xterm",
        "usr/bin/tint2",
        "usr/bin/xsetroot",
        "usr/bin/wmctrl",
        "usr/bin/xprop",
        "usr/bin/dbus-launch",
        "usr/lib/x86_64-linux-gnu/libpulse.so.0",
        "usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
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

    private fun aptPolicyStampFile(context: Context): File =
        File(rootDir(context), ".apt_policy_version")

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

    /**
     * Installs whatever the graphical session is still missing, and nothing if it is
     * complete.
     *
     * This is what keeps [DISPLAY_PACKAGES] extensible: adding a package would otherwise
     * mean bumping [LAYOUT_VERSION] and making everyone re-download and re-unpack a rootfs
     * that is already correct, just to run one apt command against it.
     */
    suspend fun ensureDisplaySession(
        context: Context,
        onProgress: (Progress) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isInstalled(context)) throw IOException("The Linux userland is not installed")
            val rootfs = rootfsDir(context)
            if (hasDisplaySession(context)) {
                // Rewritten on every start rather than only after an install: it is a few
                // small files, and it is how a change to them reaches a userland that is
                // otherwise complete.
                configureSession(context, rootfs)
                ensureAptPolicy(context, rootfs, onProgress)
            } else {
                Timber.i("[LinuxRootfs]: completing the graphical session install")
                installDisplaySession(context, rootfs, onProgress)
            }
        }
    }

    /**
     * Removes the userland, and everything published from it.
     *
     * The entries and shortcuts go first: once the rootfs is gone there are no labels or icons
     * left to identify what they stood for, and each one would be a launcher entry for a program
     * that no longer exists.
     */
    suspend fun uninstall(context: Context) {
        LinuxAppReconciler.retireAll(context)

        stampFile(context).delete()
        aptPolicyStampFile(context).delete()
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
        // Ubuntu HTTP archives first: mozilla.sources is HTTPS and needs ca-certificates,
        // which ubuntu-base does not ship. Nosnap pins can land immediately.
        writeNosnapPins(rootfs)
        File(rootfs, MOZILLA_SOURCES_PATH).delete()

        onProgress(Progress("Fetching package lists", -1f))
        // The base image ships no package lists at all, so this is not optional.
        val update = LinuxProgramLauncher.runWithOutput(
            context,
            "apt-get update",
            extraEnv = APT_ENV,
            timeoutSeconds = 600,
        )
        Timber.i("[LinuxRootfs]: apt-get update:\n%s", update.takeLast(2000))

        // Answers the questions the packages below would otherwise ask, or in man-db's case
        // act on. Read from a file rather than piped in, since these commands run without a
        // shell.
        val preseed = LinuxProgramLauncher.runWithOutput(
            context,
            "debconf-set-selections /$PRESEED",
            extraEnv = APT_ENV,
            timeoutSeconds = 120,
        )
        if (preseed.isNotBlank()) Timber.w("[LinuxRootfs]: debconf-set-selections: %s", preseed)

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

        // ca-certificates is in DISPLAY_PACKAGES; Mozilla HTTPS is safe from here.
        writeMozillaAptSource(rootfs)
        ensureMozillaKey(rootfs)
        onProgress(Progress("Fetching Mozilla package lists", -1f))
        val mozillaUpdate = LinuxProgramLauncher.runWithOutput(
            context,
            "apt-get update",
            extraEnv = APT_ENV,
            timeoutSeconds = 600,
        )
        Timber.i("[LinuxRootfs]: mozilla apt-get update:\n%s", mozillaUpdate.takeLast(2000))

        applyAptPolicyPackages(context, rootfs, onProgress)
        aptPolicyStampFile(context).writeText(APT_POLICY_VERSION.toString())

        // The .debs are no use once unpacked, and this reclaims a hundred megabytes or so
        // of the app's storage.
        LinuxProgramLauncher.runWithOutput(context, "apt-get clean", extraEnv = APT_ENV, timeoutSeconds = 120)

        val missing = DISPLAY_BINARIES.filterNot { File(rootfs, it).exists() }
        if (missing.isNotEmpty()) {
            throw IOException("Graphical session install incomplete, missing: ${missing.joinToString()}")
        }

        configureSession(context, rootfs)
    }

    /** The session's configuration files, all of which are safe to rewrite. */
    private fun configureSession(context: Context, rootfs: File) {
        LinuxDesktopConfig.writeWindowManagerConfigs(rootfs)
        writeXsettings(rootfs, context.resources.displayMetrics.densityDpi)
        writeXdefaults(rootfs)
    }

    /**
     * X resources for the toolkit that predates XSETTINGS.
     *
     * Read from here without running xrdb: Xt falls back to ~/.Xdefaults when the root
     * window carries no resource manager property, and nothing in this session sets one.
     *
     * xterm defaults to a bitmap font, which ignores DPI entirely and so stays pixel-tiny
     * on a dense panel however the server is configured. Naming a scalable font moves it
     * onto Xft, where the server's DPI decides the size, and a point size is then
     * meaningful rather than a guess.
     */
    private fun writeXdefaults(rootfs: File) {
        File(rootfs, "root/.Xdefaults").writeText(
            """
            XTerm*faceName: DejaVu Sans Mono
            XTerm*faceSize: 11
            XTerm*background: black
            XTerm*foreground: white
            XTerm*scrollBar: false
            XTerm*saveLines: 4096
            """.trimIndent() + "\n",
        )
    }

    /**
     * Default XSETTINGS, so text is the right physical size on a dense screen.
     *
     * X clients assume 96 DPI and would draw at about half size on this hardware. Android's
     * densityDpi is not that number, though -- it is measured against a different baseline --
     * so [LinuxDisplayScale] converts it rather than passing it through. The session rewrites
     * this file and signals xsettingsd if the density or the user's scale changes under it.
     */
    private fun writeXsettings(rootfs: File, densityDpi: Int) {
        val dpi = LinuxDisplayScale.xdpi(densityDpi)
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

        // man-db's postinst rebuilds the page index as the "man" user, via setpriv, which
        // cannot drop groups under PRoot's fake root and so fails on every install. The
        // index is useless here anyway -- there is no pager and no one reading man pages in
        // a session that exists to run a GUI app.
        File(rootfs, PRESEED).writeText("man-db man-db/auto-update boolean false\n")

        File(rootfs, "etc/apt/apt.conf.d").mkdirs()
        File(rootfs, "etc/apt/apt.conf.d/99gamenative").writeText(
            """
            APT::Sandbox::User "root";
            APT::Install-Recommends "false";
            Acquire::Languages "none";
            """.trimIndent() + "\n",
        )

        // Static apt policy (nosnap). Mozilla's HTTPS source is added after
        // ca-certificates is installed — see [installDisplaySession] / [ensureAptPolicy].
        writeNosnapPins(rootfs)

        // Directories PRoot binds over or the guest expects to exist.
        for (dir in listOf("dev", "proc", "sys", "tmp", "root", "run")) {
            File(rootfs, dir).mkdirs()
        }
        chmod(File(rootfs, "tmp"), 511) // 0777
    }

    /**
     * Holds Ubuntu's snap transitional `firefox` / `chromium-browser` and snapd itself
     * so they cannot win over a later Mozilla install.
     */
    private fun writeNosnapPins(rootfs: File) {
        File(rootfs, "etc/apt/preferences.d").mkdirs()
        File(rootfs, NOSNAP_PIN_PATH).writeText(
            """
            Package: snapd
            Pin: release a=*
            Pin-Priority: -10

            Package: firefox
            Pin: release o=Ubuntu
            Pin-Priority: -10

            Package: chromium-browser
            Pin: release o=Ubuntu
            Pin-Priority: -10
            """.trimIndent() + "\n",
        )
    }

    /** Mozilla APT source + pin. Call only after ca-certificates is installed in the guest. */
    private fun writeMozillaAptSource(rootfs: File) {
        File(rootfs, "etc/apt/keyrings").mkdirs()
        File(rootfs, "etc/apt/sources.list.d").mkdirs()
        File(rootfs, "etc/apt/preferences.d").mkdirs()

        File(rootfs, MOZILLA_SOURCES_PATH).writeText(
            """
            Types: deb
            URIs: https://packages.mozilla.org/apt
            Suites: mozilla
            Components: main
            Architectures: amd64
            Signed-By: /$MOZILLA_KEY_PATH
            """.trimIndent() + "\n",
        )

        File(rootfs, MOZILLA_PIN_PATH).writeText(
            """
            Package: *
            Pin: origin packages.mozilla.org
            Pin-Priority: 1001
            """.trimIndent() + "\n",
        )
    }

    /**
     * Ensures Mozilla/nosnap apt policy is on disk and, when the policy stamp advances,
     * refreshes apt and replaces snap transitional browsers with Mozilla's firefox deb.
     *
     * Safe to call on every session start: rewriting the files is cheap, and the apt work
     * only runs when [APT_POLICY_VERSION] changes.
     */
    private fun ensureAptPolicy(
        context: Context,
        rootfs: File,
        onProgress: (Progress) -> Unit = {},
    ) {
        writeNosnapPins(rootfs)

        val stamp = aptPolicyStampFile(context)
        val installed = stamp.takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (installed == APT_POLICY_VERSION.toString() &&
            File(rootfs, "etc/ssl/certs/ca-certificates.crt").isFile &&
            File(rootfs, MOZILLA_SOURCES_PATH).isFile
        ) {
            return
        }

        // Drop Mozilla temporarily so apt-get update can use Ubuntu HTTP without CA certs.
        File(rootfs, MOZILLA_SOURCES_PATH).delete()

        onProgress(Progress("Configuring package sources", -1f))
        val update = LinuxProgramLauncher.runWithOutput(
            context,
            "apt-get update",
            extraEnv = APT_ENV,
            timeoutSeconds = 600,
        )
        Timber.i("[LinuxRootfs]: apt policy update:\n%s", update.takeLast(2000))

        onProgress(Progress("Installing CA certificates", -1f))
        val certs = LinuxProgramLauncher.runWithOutput(
            context,
            "apt-get install -y -o Dpkg::Use-Pty=0 ca-certificates",
            extraEnv = APT_ENV,
            timeoutSeconds = 600,
        )
        Timber.i("[LinuxRootfs]: ca-certificates:\n%s", certs.takeLast(2000))

        writeMozillaAptSource(rootfs)
        ensureMozillaKey(rootfs)
        onProgress(Progress("Fetching Mozilla package lists", -1f))
        val mozillaUpdate = LinuxProgramLauncher.runWithOutput(
            context,
            "apt-get update",
            extraEnv = APT_ENV,
            timeoutSeconds = 600,
        )
        Timber.i("[LinuxRootfs]: mozilla apt-get update:\n%s", mozillaUpdate.takeLast(2000))

        applyAptPolicyPackages(context, rootfs, onProgress)
        LinuxProgramLauncher.runWithOutput(context, "apt-get clean", extraEnv = APT_ENV, timeoutSeconds = 120)
        stamp.writeText(APT_POLICY_VERSION.toString())
    }

    /**
     * Purges snapd / Ubuntu snap-stub browsers and installs Mozilla Firefox so the Apps
     * list gets a real `.desktop` entry.
     */
    private fun applyAptPolicyPackages(
        context: Context,
        rootfs: File,
        onProgress: (Progress) -> Unit,
    ) {
        onProgress(Progress("Removing snap packages", -1f))
        val purge = LinuxProgramLauncher.runWithOutput(
            context,
            "apt-get remove -y --purge -o Dpkg::Use-Pty=0 snapd firefox chromium-browser",
            extraEnv = APT_ENV,
            timeoutSeconds = 600,
        )
        Timber.i("[LinuxRootfs]: snap purge:\n%s", purge.takeLast(2000))
        for (leftover in listOf("snap", "var/snap", "var/lib/snapd")) {
            File(rootfs, leftover).deleteRecursively()
        }

        onProgress(Progress("Installing Firefox from Mozilla", -1f))
        val firefox = LinuxProgramLauncher.runWithOutput(
            context,
            "apt-get install -y -o Dpkg::Use-Pty=0 firefox",
            extraEnv = APT_ENV,
            timeoutSeconds = 1800,
        )
        Timber.i("[LinuxRootfs]: mozilla firefox:\n%s", firefox.takeLast(2000))
    }

    /** Fetches Mozilla's APT signing key into the rootfs when missing or empty. */
    private fun ensureMozillaKey(rootfs: File) {
        val key = File(rootfs, MOZILLA_KEY_PATH)
        if (key.isFile && key.length() > 0L) return
        key.parentFile?.mkdirs()
        val client = OkHttpClient()
        val response = client.newCall(Request.Builder().url(MOZILLA_KEY_URL).build()).execute()
        response.use {
            if (!it.isSuccessful) {
                throw IOException("Mozilla APT key download failed: HTTP ${it.code}")
            }
            key.outputStream().use { out -> it.body.byteStream().copyTo(out) }
        }
        chmod(key, 420) // 0644
        Timber.i("[LinuxRootfs]: wrote Mozilla APT key (%d bytes)", key.length())
    }
}
