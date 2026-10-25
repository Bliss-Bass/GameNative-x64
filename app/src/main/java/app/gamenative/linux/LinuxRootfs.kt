package app.gamenative.linux

import android.content.Context
import android.os.Build
import app.gamenative.service.LinuxVtService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Downloads, unpacks and sets up the Ubuntu userland: the distro the graphical session,
 * Linux app stubs and app scanning run in. Other distros are shells only; see [LinuxDistros].
 *
 * The tarball is fetched on first use rather than bundled: it is ~30MB compressed and
 * ~"a few hundred" MB expanded, and the APK already carries a Vulkan payload.
 */
object LinuxRootfs {

    /** Bump when the tarball or the post-unpack tweaks below change; forces a re-install. */
    // 4: installed without PRoot's --link2symlink, which had left .l2s symlinks pointing at
    // host paths the guest cannot resolve; an install made under it has a broken perl.
    private const val LAYOUT_VERSION = 4

    /**
     * Bump when Mozilla/nosnap apt policy files change. Applied without re-unpacking the
     * rootfs: [ensureDisplaySession] rewrites the files and, when the stamp advances, runs
     * apt once to drop snap stubs, so a later `apt install firefox` gets Mozilla's deb.
     */
    private const val APT_POLICY_VERSION = 2

    private const val MOZILLA_KEY_URL = "https://packages.mozilla.org/apt/repo-signing-key.gpg"
    private const val MOZILLA_KEY_PATH = "etc/apt/keyrings/packages.mozilla.org.asc"
    private const val MOZILLA_SOURCES_PATH = "etc/apt/sources.list.d/mozilla.sources"
    private const val MOZILLA_PIN_PATH = "etc/apt/preferences.d/mozilla"
    private const val NOSNAP_PIN_PATH = "etc/apt/preferences.d/nosnap"

    /**
     * Bump when the default browser sources below change. Kept apart from
     * [APT_POLICY_VERSION], whose bump also re-runs the snap purge.
     */
    private const val BROWSER_SOURCES_VERSION = 2

    // Chromium as a real deb: Ubuntu only ships it as a snap stub, which cannot run here.
    private const val XTRADEB_KEY_URL =
        "https://keyserver.ubuntu.com/pks/lookup?op=get&options=mr&search=0x5301FA4FD93244FBC6F6149982BB6851C64F6880"
    private const val XTRADEB_KEY_PATH = "etc/apt/keyrings/xtradeb-apps.asc"
    // add-apt-repository's own file name, so adding the PPA by hand replaces this rather than
    // listing the same source twice.
    private const val XTRADEB_SOURCES_PATH = "etc/apt/sources.list.d/xtradeb-ubuntu-apps-noble.sources"
    private const val XTRADEB_PIN_PATH = "etc/apt/preferences.d/xtradeb"
    private const val XTRADEB_SOURCE_HOST = "ppa.launchpadcontent.net/xtradeb/apps"
    private const val GOOGLE_SOURCE_HOST = "dl.google.com/linux/chrome"

    private const val GOOGLE_KEY_URL = "https://dl.google.com/linux/linux_signing_key.pub"
    private const val GOOGLE_KEY_PATH = "etc/apt/keyrings/google-chrome.asc"
    // The name Chrome's own postinst writes, so it replaces this file instead of adding a
    // second entry for the same repository with a different Signed-By, which apt rejects.
    private const val GOOGLE_SOURCES_PATH = "etc/apt/sources.list.d/google-chrome.sources"

    // Sourced by Debian's /usr/bin/chromium launcher, which the PPA build ships.
    private const val CHROMIUM_FLAGS_PATH = "etc/chromium.d/gamenative"
    /** Each Chrome channel's launcher script, which the /usr/bin names link to. */
    private val CHROME_LAUNCHERS = listOf(
        "/opt/google/chrome/google-chrome",
        "/opt/google/chrome-beta/google-chrome-beta",
        "/opt/google/chrome-unstable/google-chrome-unstable",
    )
    // Ending a session stops the guest from outside, which Chromium records as a crash; without
    // the last flag every launch opens with a "Restore pages?" bubble.
    internal const val CHROMIUM_FAMILY_FLAGS = "--no-sandbox --test-type --hide-crash-restore-bubble"

    /**
     * What the graphical session is built from. All of it comes from apt, which is the
     * point of running a glibc userland: none of it has to be cross-compiled for bionic.
     *
     * - The default freeform path is Android/Winlator X (DRI3). Xtigervnc remains installed
     *   as the Settings opt-in RFB compatibility backend ([LinuxDisplaySession]).
     * - openbox remains available for the VNC path and a future shared desktop mode; APP
     *   sessions on Android X maximize guest windows from the host side instead of a guest WM.
     * - xsettingsd carries DPI changes into running GTK and Qt apps, which otherwise read
     *   it once at startup.
     * - xterm is what openbox's root menu means by a terminal, and without it the desktop
     *   opens onto a background with no way to start anything from inside it.
     * - tint2 is the desktop session's panel. Openbox has no panel of its own, and a root
     *   menu alone means a long press for everything on a screen with no right button.
     * - x11-xserver-utils is here for xsetroot, which paints the root window: the X server's
     *   own default is a black-and-white weave from the 1980s.
     * - wmctrl and x11-utils (for xprop) remain useful for desktop tooling and diagnostics.
     * - The font packages are not optional here: Recommends are off, so nothing else pulls
     *   them in, and both a bitmap font for xterm and a scalable one for everything that
     *   draws through Xft have to be present or clients fail to start on a missing font.
     * - ca-certificates is required before any HTTPS APT source (Mozilla) can be fetched:
     *   ubuntu-base ships none, and apt-get update against packages.mozilla.org fails without it.
     * - dbus-x11 provides dbus-launch. Firefox warns (and a11y fails) without a session bus
     *   helper; Recommends are off so nothing else pulls it in.
     * - libpulse0 is the Cubeb/Pulse client. The server is the host AAudio daemon ([LinuxPulse]);
     *   without the client library Firefox never reaches it and YouTube stalls with no playback.
     * - ffmpeg (libavcodec) supplies AAC (`audio/mp4a-latm`) and related patent codecs Firefox
     *   does not ship. Without it YouTube fatals with "no decoder found for audio/mp4a-latm".
     * - librsvg2-common is the SVG gdk-pixbuf loader. Recommends are off, so GTK apps that
     *   ship SVG assets (Pitivi's greeter emblems, for example) otherwise crash at import with
     *   "Couldn't recognize the image file format" and leave an empty black session.
     */
    private val DISPLAY_PACKAGES = listOf(
        "ca-certificates",
        "dbus-x11",
        "libpulse0",
        "ffmpeg",
        "librsvg2-common",
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
        "usr/bin/ffmpeg",
        // gdk-pixbuf module API dir has been 2.10.0 for years; librsvg2-common drops the SVG
        // loader here. Presence is how we know ensureDisplaySession pulled the package in.
        "usr/lib/x86_64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so",
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

    /** Ubuntu's rootfs: the distro the graphical session, stubs and app scanning run in. */
    fun rootfsDir(context: Context): File = LinuxDistros.rootfsDir(context, LinuxDistroCatalog.UBUNTU)

    private fun stampFile(context: Context): File = File(rootDir(context), ".rootfs_version")

    private fun aptPolicyStampFile(context: Context): File =
        File(rootDir(context), ".apt_policy_version")

    private fun browserSourcesStampFile(context: Context): File =
        File(rootDir(context), ".browser_sources_version")

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
            // The Terminal screen and the Linux Services addon can both start one. Each deletes
            // the rootfs before unpacking, so a second run would undo the first; one that waited
            // out another's install takes its result instead.
            val waited = !installLock.tryLock()
            if (waited) installLock.lock()
            try {
                if (waited && isInstalled(context)) return@withContext Result.success(Unit)
                installLocked(context, onProgress)
            } finally {
                installLock.unlock()
            }
        }

    private val installLock = Mutex()

    private suspend fun installLocked(context: Context, onProgress: (Progress) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!isSupported()) throw UnsupportedAbiException()

                LinuxDistros.migrateLegacyLayout(context)
                rootDir(context).mkdirs()
                val rootfs = rootfsDir(context)
                val tarball = File(context.cacheDir, "ubuntu-base.tar.gz")

                RootfsArchive.fetchVerified(URL, SHA256, tarball, "Downloading Linux userland", onProgress)

                onProgress(Progress("Unpacking Linux userland", -1f))
                stampFile(context).delete()
                if (!RootfsArchive.deleteTree(rootfs)) throw IOException("Could not clear the old Linux userland")
                rootfs.mkdirs()
                RootfsArchive.extract(tarball, rootfs)

                prepare(rootfs)

                // Freed before apt runs: the packages below need the space more than a
                // tarball that is easy to fetch again.
                tarball.delete()

                installDisplaySession(context, rootfs, onProgress)

                stampFile(context).writeText(LAYOUT_VERSION.toString())
                Timber.i("[LinuxRootfs]: installed to %s", rootfs)
                // The Ctrl+Alt+F4-F6 consoles had nothing to run until now (Bass OS only).
                LinuxVtService.startIfSupported(context)
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
        // One at a time: two windows opening together would otherwise run apt and dpkg-divert
        // side by side, and one of them loses dpkg's lock.
        sessionSetupLock.withLock { ensureDisplaySessionLocked(context, onProgress) }
    }

    private val sessionSetupLock = Mutex()

    private fun ensureDisplaySessionLocked(context: Context, onProgress: (Progress) -> Unit): Result<Unit> =
        runCatching {
            if (!isInstalled(context)) throw IOException("The Linux userland is not installed")
            val rootfs = rootfsDir(context)
            if (hasDisplaySession(context)) {
                // Rewritten on every start rather than only after an install: it is a few
                // small files, and it is how a change to them reaches a userland that is
                // otherwise complete.
                configureSession(context, rootfs)
                ensureAptPolicy(context, rootfs, onProgress)
                ensureBrowserSources(context, rootfs, onProgress)
            } else {
                Timber.i("[LinuxRootfs]: completing the graphical session install")
                installDisplaySession(context, rootfs, onProgress)
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
        LinuxAppReconciler.retireDistro(context, LinuxDistroCatalog.UBUNTU)

        stampFile(context).delete()
        aptPolicyStampFile(context).delete()
        browserSourcesStampFile(context).delete()
        RootfsArchive.deleteTree(rootfsDir(context))
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

        // An earlier attempt that died mid-unpack leaves dpkg "interrupted", and apt then
        // refuses everything until this has run. A no-op on a healthy database.
        val recover = LinuxProgramLauncher.runWithOutput(
            context,
            "dpkg --configure -a",
            extraEnv = APT_ENV,
            timeoutSeconds = 600,
        )
        if (recover.isNotBlank()) Timber.i("[LinuxRootfs]: dpkg --configure -a:\n%s", recover.takeLast(2000))

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
        ensureBrowserSources(context, rootfs, onProgress)

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
        writeFirefoxScale(rootfs, LinuxDisplayScale.xdpi(context.resources.displayMetrics.densityDpi))
        ensureFirefoxMediaCodecs(rootfs)
        ensureFirefoxSandboxNotice(rootfs)
        ensureOpenshotLaunchWrapper(rootfs)
        ensureFirefoxLaunchWrapper(rootfs)
        LinuxSchemeHandler.install(rootfs)
        ensureChromiumFlags(rootfs)
        ensureChromeLaunchWrapper(rootfs)
    }

    /**
     * Starts Chromium without its sandbox. The guest runs as PRoot's fake root, and Chromium
     * exits at once as root unless told `--no-sandbox`; its namespace sandbox could not work
     * under PRoot anyway (see `MOZ_DISABLE_CONTENT_SANDBOX` in [LinuxProgramLauncher]).
     * `--test-type` drops the "unsupported command-line flag" bar that would otherwise sit
     * over every window, as the Firefox sandbox notice is hidden in [ensureFirefoxSandboxNotice].
     * Written whether or not Chromium is installed yet: the launcher reads it on each start.
     */
    internal fun ensureChromiumFlags(rootfs: File) {
        val flags = File(rootfs, CHROMIUM_FLAGS_PATH)
        flags.parentFile?.mkdirs()
        flags.writeText(
            """|# Written by GameNative. See LinuxRootfs.ensureChromiumFlags.
            |export CHROMIUM_FLAGS="${'$'}CHROMIUM_FLAGS $CHROMIUM_FAMILY_FLAGS"
            |""".trimMargin(),
        )
    }

    /**
     * The same for Google Chrome, whose launcher takes no flags from the environment. Chrome's
     * own script is diverted to `.real` by [registerChromeDiversion]; this wrapper takes its
     * place. Written only once the diversion exists, or it would overwrite Chrome's script.
     */
    internal fun ensureChromeLaunchWrapper(rootfs: File) {
        for (launcher in CHROME_LAUNCHERS) {
            if (!isChromeDiverted(rootfs, launcher)) continue
            val wrapper = File(rootfs, launcher.removePrefix("/"))
            wrapper.parentFile?.mkdirs()
            wrapper.writeText(
                """|#!/bin/sh
                |# Written by GameNative. See LinuxRootfs.ensureChromeLaunchWrapper.
                |exec $launcher.real $CHROMIUM_FAMILY_FLAGS "${'$'}@"
                |""".trimMargin(),
            )
            wrapper.setExecutable(true, false)
        }
    }

    /** dpkg keeps diversions as (path, diverted-to, package) line triples. */
    private fun isChromeDiverted(rootfs: File, launcher: String): Boolean {
        val lines = File(rootfs, "var/lib/dpkg/diversions").takeIf { it.isFile }
            ?.readLines() ?: return false
        return lines.chunked(3).any { it.size >= 2 && it[0] == launcher && it[1] == "$launcher.real" }
    }

    internal fun registerChromeDiversion(context: Context, rootfs: File) {
        for (launcher in CHROME_LAUNCHERS) {
            if (isChromeDiverted(rootfs, launcher)) continue
            val divert = LinuxProgramLauncher.runWithOutput(
                context,
                "dpkg-divert --local --rename --divert $launcher.real --add $launcher",
                extraEnv = APT_ENV,
                timeoutSeconds = 60,
                rootfs = rootfs,
            )
            Timber.i("[LinuxRootfs]: chrome diversion: %s", divert.trim())
        }
        // --rename moved an installed Chrome's script aside; put the wrapper in its place now
        // rather than at the next session start.
        ensureChromeLaunchWrapper(rootfs)
    }

    /**
     * Adds the xtradeb PPA (Chromium) and Google's Chrome repository, so `apt install chromium`
     * or `apt install google-chrome-stable` works without setting either up by hand.
     *
     * A source already configured by the user or by Chrome's own postinst is left as it is.
     * Runs once per [BROWSER_SOURCES_VERSION], and again whenever a source has gone: purging
     * Chrome deletes `google-chrome.sources`, which would otherwise leave Chrome impossible to
     * reinstall. A failed key download leaves the stamp unwritten so the next session tries again.
     */
    private fun ensureBrowserSources(context: Context, rootfs: File, onProgress: (Progress) -> Unit) {
        val stamp = browserSourcesStampFile(context)
        val current = stamp.takeIf { it.isFile }?.readText()?.trim() == BROWSER_SOURCES_VERSION.toString()
        if (current && hasAptSource(rootfs, XTRADEB_SOURCE_HOST) && hasAptSource(rootfs, GOOGLE_SOURCE_HOST)) return
        // Both are HTTPS sources; ensureAptPolicy installs the CA bundle first.
        if (!File(rootfs, "etc/ssl/certs/ca-certificates.crt").isFile) return

        onProgress(Progress("Adding browser package sources", -1f))
        writeXtradebPin(rootfs)
        val xtradeb = ensureAptSource(
            rootfs,
            existing = XTRADEB_SOURCE_HOST,
            sourcesPath = XTRADEB_SOURCES_PATH,
            keyPath = XTRADEB_KEY_PATH,
            keyUrl = XTRADEB_KEY_URL,
            sources = """
                Types: deb
                URIs: https://ppa.launchpadcontent.net/xtradeb/apps/ubuntu/
                Suites: noble
                Components: main
                Signed-By: /$XTRADEB_KEY_PATH
                """.trimIndent() + "\n",
        )
        val chrome = ensureGoogleChromeSource(rootfs)
        registerChromeDiversion(context, rootfs)

        if (xtradeb == SourceState.ADDED || chrome == SourceState.ADDED) {
            onProgress(Progress("Fetching browser package lists", -1f))
            val update = LinuxProgramLauncher.runWithOutput(
                context,
                "apt-get update",
                extraEnv = APT_ENV,
                timeoutSeconds = 600,
            )
            Timber.i("[LinuxRootfs]: browser sources apt-get update:\n%s", update.takeLast(2000))
        }
        if (xtradeb != SourceState.FAILED && chrome != SourceState.FAILED) {
            stamp.writeText(BROWSER_SOURCES_VERSION.toString())
        }
    }

    internal enum class SourceState { PRESENT, ADDED, FAILED }

    /** Google's Chrome repository, in [rootfs]'s apt sources unless one already points there. */
    internal fun ensureGoogleChromeSource(rootfs: File): SourceState = ensureAptSource(
        rootfs,
        existing = GOOGLE_SOURCE_HOST,
        sourcesPath = GOOGLE_SOURCES_PATH,
        keyPath = GOOGLE_KEY_PATH,
        keyUrl = GOOGLE_KEY_URL,
        sources = """
            Types: deb
            URIs: https://dl.google.com/linux/chrome-stable/deb/
            Suites: stable
            Components: main
            Architectures: amd64
            Signed-By: /$GOOGLE_KEY_PATH
            """.trimIndent() + "\n",
    )

    /** Whether any apt source in [rootfs] points at [host]. */
    private fun hasAptSource(rootfs: File, host: String): Boolean {
        val configured = listOf(File(rootfs, "etc/apt/sources.list")) +
            (File(rootfs, "etc/apt/sources.list.d").listFiles()?.toList() ?: emptyList())
        return configured.any { it.isFile && runCatching { it.readText() }.getOrDefault("").contains(host) }
    }

    /** Writes [sources] unless any apt source already points at [existing]. */
    private fun ensureAptSource(
        rootfs: File,
        existing: String,
        sourcesPath: String,
        keyPath: String,
        keyUrl: String,
        sources: String,
    ): SourceState {
        if (hasAptSource(rootfs, existing)) return SourceState.PRESENT
        return runCatching {
            ensureAptKey(rootfs, keyPath, keyUrl)
            File(rootfs, sourcesPath).apply { parentFile?.mkdirs() }.writeText(sources)
            SourceState.ADDED
        }.getOrElse {
            Timber.w(it, "[LinuxRootfs]: could not add apt source %s", sourcesPath)
            SourceState.FAILED
        }
    }

    /**
     * Keeps the PPA below Ubuntu, so it only supplies packages Ubuntu does not have (Chromium)
     * and never replaces Ubuntu's own on upgrade.
     */
    private fun writeXtradebPin(rootfs: File) {
        File(rootfs, XTRADEB_PIN_PATH).apply { parentFile?.mkdirs() }.writeText(
            """
            Package: *
            Pin: release o=LP-PPA-xtradeb-apps
            Pin-Priority: 100
            """.trimIndent() + "\n",
        )
    }

    /**
     * Puts a `/usr/local/bin/firefox` shim ahead of the packaged `/usr/bin/firefox`.
     *
     * Firefox sizes its own toolbar and tab fonts from the GTK font DPI, which is the session's
     * Xft/DPI and so carries [LinuxDisplayScale.DESKTOP_TO_ANDROID_VISUAL]. That lift is for GTK
     * widgets; Firefox already gets Android's density through devPixelsPerPx, so with it the
     * browser chrome came out half again larger than its pages and than Android's own UI.
     * GDK_DPI_SCALE takes it back out for Firefox alone.
     */
    private fun ensureFirefoxLaunchWrapper(rootfs: File) {
        val real = File(rootfs, "usr/bin/firefox")
        val wrapper = File(rootfs, "usr/local/bin/firefox")
        if (!real.exists() && !java.nio.file.Files.isSymbolicLink(real.toPath())) {
            if (wrapper.exists()) wrapper.delete()
            return
        }
        val dpiScale = "%.3f".format(java.util.Locale.ROOT, 1f / LinuxDisplayScale.DESKTOP_TO_ANDROID_VISUAL)
        wrapper.parentFile?.mkdirs()
        wrapper.writeText(
            """|#!/bin/sh
            |# Written by GameNative. See ensureFirefoxLaunchWrapper.
            |export GDK_DPI_SCALE=$dpiScale
            |exec /usr/bin/firefox "${'$'}@"
            |""".trimMargin(),
        )
        wrapper.setExecutable(true, false)
    }

    /**
     * Hides Firefox's "The security sandbox is disabled" bar, which has no pref and never
     * clears. PRoot cannot host the sandbox (see `MOZ_DISABLE_CONTENT_SANDBOX` in
     * [LinuxProgramLauncher]); the guest is still confined by GameNative's Android sandbox.
     * An autoconfig script no-ops the function that adds the bar before the first window.
     */
    private fun ensureFirefoxSandboxNotice(rootfs: File) {
        val autoconfig = """
            pref("general.config.filename", "gamenative.cfg");
            pref("general.config.obscure_value", 0);
            pref("general.config.sandbox_enabled", false);
            """.trimIndent() + "\n"
        // Autoconfig skips the first line of the file.
        val script = """
            // GameNative: hide the sandbox-disabled bar; PRoot cannot host Firefox's sandbox.
            try {
              const { SandboxUtils } = ChromeUtils.importESModule("resource://gre/modules/SandboxUtils.sys.mjs");
              SandboxUtils.maybeWarnAboutDisabledContentSandbox = function () {};
            } catch (e) {
              Components.utils.reportError(e);
            }
            // GameNative: this window's scale, which may differ from the shared default pref.
            try {
              const scale = getenv("$DEVICE_SCALE_ENV");
              if (scale) defaultPref("layout.css.devPixelsPerPx", scale);
            } catch (e) {
              Components.utils.reportError(e);
            }
            // GameNative: sign-in redirects (cursor://, vscode://) go to the running app.
            pref("network.protocol-handler.external.cursor", true);
            pref("network.protocol-handler.external.vscode", true);
            pref("network.protocol-handler.warn-external.cursor", false);
            pref("network.protocol-handler.warn-external.vscode", false);
            pref("network.protocol-handler.expose.cursor", false);
            pref("network.protocol-handler.expose.vscode", false);
            """.trimIndent() + "\n"
        for (dirName in listOf("usr/lib/firefox", "usr/lib/firefox-beta")) {
            val appDir = File(rootfs, dirName)
            if (!appDir.isDirectory) continue
            File(appDir, "defaults/pref").apply { mkdirs() }.resolve("autoconfig.js").writeText(autoconfig)
            File(appDir, "gamenative.cfg").writeText(script)
        }
    }

    /**
     * Read by the Firefox autoconfig script ([ensureFirefoxSandboxNotice]) as the window's device
     * scale. Sessions that set it can each run at their own scale; the pref file written by
     * [writeFirefoxScale] is what the rest fall back on.
     */
    const val DEVICE_SCALE_ENV = "GAMENATIVE_DEVICE_SCALE"

    /**
     * Gives Firefox the session's scale as a default pref; see [LinuxDisplayScale.deviceScale].
     * A default rather than a user pref, so about:config can still override it. Firefox reads it
     * at startup, so a density change reaches the next window, not a running one.
     *
     * Firefox also zooms by GTK's text scale, Xft/DPI over 96, which here is the toolkit DPI
     * (about 2.4x) rather than anything meant for a browser; on top of devPixelsPerPx that made
     * pages several times too large, worse after any XSETTINGS change. Pinned to 100% so
     * devPixelsPerPx is Firefox's only scale.
     */
    fun writeFirefoxScale(rootfs: File, xdpi: Int) {
        val scale = "%.3f".format(java.util.Locale.ROOT, LinuxDisplayScale.deviceScale(xdpi))
        val prefs = """
            pref("layout.css.devPixelsPerPx", "$scale");
            pref("ui.textScaleFactor", 100);
            pref("browser.display.os-zoom-behavior", 0);
            """.trimIndent() + "\n"
        for (dirName in listOf("usr/lib/firefox", "usr/lib/firefox-beta")) {
            val appDir = File(rootfs, dirName)
            if (!appDir.isDirectory) continue
            File(appDir, "defaults/pref").apply { mkdirs() }.resolve("gamenative-scale.js").writeText(prefs)
        }
    }

    /**
     * Puts a `/usr/local/bin/openshot-qt` shim ahead of the packaged `/usr/bin/openshot-qt`.
     *
     * OpenShot 4.x's `launch.py` does `from qt_api import …` before appending its package
     * directory to `sys.path`. Under a normal desktop install that often works by accident;
     * under our PRoot entry-point launch it dies with `ModuleNotFoundError: No module named
     * 'qt_api'` and the session stays an empty black desktop. Setting `PYTHONPATH` to the
     * package dir fixes it. Guest `PATH` prefers `/usr/local/bin`, so existing stubs and
     * `.desktop` Exec lines that say bare `openshot-qt` pick this up without a rescan.
     */
    private fun ensureOpenshotLaunchWrapper(rootfs: File) {
        val real = File(rootfs, "usr/bin/openshot-qt")
        val wrapper = File(rootfs, "usr/local/bin/openshot-qt")
        if (!real.isFile) {
            if (wrapper.exists()) wrapper.delete()
            return
        }
        wrapper.parentFile?.mkdirs()
        wrapper.writeText(
            """|#!/bin/sh
            |# Written by GameNative. See ensureOpenshotLaunchWrapper.
            |export PYTHONPATH="/usr/lib/python3/dist-packages/openshot_qt${'$'}{PYTHONPATH:+:${'$'}PYTHONPATH}"
            |exec /usr/bin/openshot-qt "${'$'}@"
            |""".trimMargin(),
        )
        wrapper.setExecutable(true, false)
    }

    /**
     * Makes system libavcodec visible to Firefox content/utility children and forces the
     * OS FFmpeg path for AAC.
     *
     * Those children set `LD_LIBRARY_PATH=/usr/lib/firefox` and (with the utility sandbox)
     * may never successfully link `libavcodec.so.60` from `/usr/lib/x86_64-linux-gnu`, so
     * YouTube keeps failing with `no decoder found for audio/mp4a-latm` after `ffmpeg` is
     * installed. Symlinks into the app dir and a defaults pref close that gap.
     *
     * Audio decoding must stay in the utility process (`media.utility-process.enabled`);
     * Firefox 15x rejects RDD audio with `NS_ERROR_DOM_MEDIA_DENIED_IN_NON_UTILITY`. The
     * utility sandbox is disabled separately via `MOZ_DISABLE_UTILITY_SANDBOX`.
     */
    private fun ensureFirefoxMediaCodecs(rootfs: File) {
        val codec = File(rootfs, "usr/lib/x86_64-linux-gnu/libavcodec.so.60")
        if (!codec.exists()) return

        val libs = listOf(
            "libavcodec.so.60" to "/usr/lib/x86_64-linux-gnu/libavcodec.so.60",
            "libavutil.so.58" to "/usr/lib/x86_64-linux-gnu/libavutil.so.58",
            "libswresample.so.4" to "/usr/lib/x86_64-linux-gnu/libswresample.so.4",
        )
        val prefs = """
            pref("media.ffmpeg.enabled", true);
            pref("media.rdd-ffmpeg.enabled", true);
            pref("media.ffvpx.enabled", true);
            pref("media.utility-process.enabled", true);
            // Widevine / EME: without this Firefox waits for an "Enable DRM" click, and DRM
            // demos report EME unsupported until the CDM is installed.
            pref("media.eme.enabled", true);
            pref("media.gmp-widevinecdm.enabled", true);
            pref("media.gmp-widevinecdm.visible", true);
            pref("media.gmp-manager.updateEnabled", true);
            """.trimIndent() + "\n"

        // Older builds locked EME on with a policy, which shows "managed by your organization"
        // and greys out the DRM toggle. The default prefs above already turn it on.
        val oldPolicies = """
            {
              "policies": {
                "EncryptedMediaExtensions": {
                  "Enabled": true,
                  "Locked": true
                }
              }
            }
            """.trimIndent() + "\n"

        for (dirName in listOf("usr/lib/firefox", "usr/lib/firefox-beta")) {
            val appDir = File(rootfs, dirName)
            if (!appDir.isDirectory) continue
            for ((name, target) in libs) {
                val link = File(appDir, name)
                // The target is a guest path, so from here the link always looks dangling.
                if (java.nio.file.Files.isSymbolicLink(link.toPath()) || link.exists()) continue
                runCatching {
                    java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
                }.onFailure {
                    Timber.w(it, "[LinuxRootfs]: could not link %s into %s", name, dirName)
                }
            }
            val prefDir = File(appDir, "defaults/pref").apply { mkdirs() }
            File(prefDir, "gamenative-media.js").writeText(prefs)
            val policyFile = File(appDir, "distribution/policies.json")
            if (policyFile.isFile && runCatching { policyFile.readText() }.getOrNull() == oldPolicies) {
                policyFile.delete()
            }
        }
    }

    /**
     * X resources for the toolkit that predates XSETTINGS.
     *
     * Read from here without running xrdb: Xt falls back to ~/.Xdefaults when the root
     * window carries no resource manager property, and nothing in this session sets one.
     *
     * Android X has no RENDER extension, so an Xft faceName leaves xterm unable to draw
     * glyphs. Core fonts (server-side ImageText/PolyText) are what work; Xft.dpi is still
     * written so GTK/Qt clients that read this file agree with xsettingsd.
     */
    private fun writeXdefaults(rootfs: File) {
        // Placeholder until a session rewrites this with the real matched DPI on launch.
        val dpi = LinuxDisplayScale.xdpi(160, LinuxDisplayScale.DEFAULT_SCALE_PERCENT)
        File(rootfs, "root/.Xdefaults").writeText(
            """
            Xft.dpi: $dpi
            XTerm*font: fixed
            XTerm*background: black
            XTerm*foreground: white
            XTerm*scrollBar: false
            XTerm*saveLines: 4096
            UXTerm*font: fixed
            UXTerm*background: black
            UXTerm*foreground: white
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

    private fun chmod(file: File, mode: Int) = RootfsArchive.chmod(file, mode)

    /**
     * Post-unpack tweaks that make the userland usable: on top of [RootfsArchive.prepareBase],
     * letting apt run as root instead of dropping to the _apt user (which it cannot do under
     * PRoot), and skipping translated package descriptions, which are 400MB of this device's
     * storage for text nothing here ever displays.
     */
    private fun prepare(rootfs: File) {
        RootfsArchive.prepareBase(rootfs)

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
     * refreshes apt and purges the snap transitional browsers.
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
     * Purges snapd and Ubuntu's snap-stub browsers. No browser is installed: the Mozilla,
     * xtradeb and Google sources are set up, and the user installs the one they want.
     *
     * The stubs are purged only when what is installed really is a stub (Ubuntu versions them
     * `1:1snap1...`), so a Firefox or Chromium the user installed from a real source survives.
     */
    private fun applyAptPolicyPackages(
        context: Context,
        rootfs: File,
        onProgress: (Progress) -> Unit,
    ) {
        onProgress(Progress("Removing snap packages", -1f))
        val stubs = listOf("firefox", "chromium-browser").filter { isSnapStub(rootfs, it) }
        val purge = LinuxProgramLauncher.runWithOutput(
            context,
            "apt-get remove -y --purge -o Dpkg::Use-Pty=0 snapd " + stubs.joinToString(" "),
            extraEnv = APT_ENV,
            timeoutSeconds = 600,
        )
        Timber.i("[LinuxRootfs]: snap purge (%s):\n%s", stubs, purge.takeLast(2000))
        for (leftover in listOf("snap", "var/snap", "var/lib/snapd")) {
            File(rootfs, leftover).deleteRecursively()
        }
    }

    /** Whether dpkg has [pkg] installed as one of Ubuntu's snap transitional packages. */
    private fun isSnapStub(rootfs: File, pkg: String): Boolean {
        val status = File(rootfs, "var/lib/dpkg/status").takeIf { it.isFile }?.readText() ?: return false
        val stanza = status.split("\n\n").firstOrNull { it.lineSequence().any { line -> line == "Package: $pkg" } }
            ?: return false
        val version = stanza.lineSequence().firstOrNull { it.startsWith("Version: ") }.orEmpty()
        return "snap" in version && stanza.contains("Status: install ok installed")
    }

    /** Fetches Mozilla's APT signing key into the rootfs when missing or empty. */
    private fun ensureMozillaKey(rootfs: File) = ensureAptKey(rootfs, MOZILLA_KEY_PATH, MOZILLA_KEY_URL)

    /** Fetches an armored APT signing key to [keyPath] when missing or empty. */
    private fun ensureAptKey(rootfs: File, keyPath: String, url: String) {
        val key = File(rootfs, keyPath)
        if (key.isFile && key.length() > 0L) return
        key.parentFile?.mkdirs()
        val client = OkHttpClient()
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) {
                throw IOException("APT key download failed for $keyPath: HTTP ${it.code}")
            }
            val body = it.body.string()
            // apt reads a .asc keyring only if it really is armored; an HTML error page
            // would make every later apt-get update fail on this source.
            if (!body.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----")) {
                throw IOException("APT key download for $keyPath returned no key")
            }
            key.writeText(body)
        }
        chmod(key, 420) // 0644
        Timber.i("[LinuxRootfs]: wrote APT key %s (%d bytes)", keyPath, key.length())
    }
}
