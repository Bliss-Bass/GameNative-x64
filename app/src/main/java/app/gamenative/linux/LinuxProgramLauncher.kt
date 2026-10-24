package app.gamenative.linux

import android.content.Context
import com.winlator.core.Callback
import com.winlator.core.ProcessHelper
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.util.TimeZone
import timber.log.Timber

/**
 * Runs programs inside the PRoot rootfs managed by [LinuxRootfs].
 *
 * Deliberately separate from [com.winlator.xenvironment.components.BionicProgramLauncherComponent]:
 * that path launches Wine, whose binaries are built against bionic and exec'd through
 * the Android linker. Here the guest is ordinary glibc software that only runs because
 * PRoot rewrites its paths and fakes root.
 *
 * Not an EnvironmentComponent, because these are many short-lived processes -- a shell,
 * `apt`, a GUI app -- rather than one process tied to the X environment's lifetime.
 */
object LinuxProgramLauncher {

    /** Where the guest's home lives, inside the rootfs. */
    const val GUEST_HOME = "/root"

    // Upstream PRoot, built by scripts/build-x86_64-proot-linux.sh -- not the Wine fork
    // vendored under app/src/main/cpp/proot, which cannot fake root and so cannot run apt.
    private const val PROOT = "libproot-linux.so"
    private const val PROOT_LOADER = "libproot-linux-loader.so"
    private val WHITESPACE = Regex("\\s+")

    /**
     * Electron/Chromium binaries that refuse to start as root. The browsers also get the flag
     * from each distro's own launcher config ([LinuxBrowserSetup]); this covers a launch that
     * reaches the binary before that config exists.
     */
    private val ROOT_SANDBOX_BINS = setOf(
        "cursor", "code", "code-oss", "electron",
        "chromium", "chromium-browser", "google-chrome", "google-chrome-stable",
    )

    private fun nativeLibraryDir(context: Context): String = context.applicationInfo.nativeLibraryDir

    fun isAvailable(context: Context): Boolean =
        LinuxRootfs.isInstalled(context) && File(nativeLibraryDir(context), PROOT).canExecute()

    /**
     * Builds the full argument vector -- PRoot itself at index 0 -- that runs [guestArgv]
     * in the rootfs.
     *
     * [displaySocketDir] is bound onto the guest's /tmp/.X11-unix so an X client finds
     * the server at DISPLAY=:0. Stock libX11 does not accept a socket path in DISPLAY,
     * which is why this is a bind rather than an environment variable.
     *
     * [rootfs] is Ubuntu's unless a caller runs another distro ([LinuxDistros.installedRootfs]).
     */
    fun buildArgv(
        context: Context,
        guestArgv: List<String>,
        cwd: String = GUEST_HOME,
        extraEnv: Map<String, String> = emptyMap(),
        displaySocketDir: File? = defaultDisplaySocketDir(context),
        extraBinds: List<String> = emptyList(),
        rootfs: File = LinuxRootfs.rootfsDir(context),
    ): List<String> {
        val libDir = nativeLibraryDir(context)

        val argv = mutableListOf("$libDir/$PROOT")
        argv += "--kill-on-exit"
        // dpkg chowns every file it unpacks, so without fake root apt cannot install
        // anything -- the feature's whole point.
        argv += "--root-id"
        // Deliberately no --link2symlink: PRoot's emulation writes host-absolute targets
        // into the .l2s symlinks it creates, which do not resolve from inside the guest.
        // dpkg's unpack-and-rename left perl -- and so debconf, and so every package with a
        // postinst -- pointing at nothing. Where SELinux denies hard links (stock Android,
        // targetSdk 29+), our PRoot build copies the file instead.
        argv += "--rootfs=${rootfs.absolutePath}"
        argv += "--cwd=$cwd"
        argv += "--bind=/dev"
        argv += "--bind=/proc"
        argv += "--bind=/sys"

        // /dev/shm is absent on Android; a directory in the rootfs stands in for it, which
        // anything using POSIX shared memory (most toolkits) needs.
        val shm = File(rootfs, "tmp/shm").apply { mkdirs() }
        argv += "--bind=${shm.absolutePath}:/dev/shm"

        if (displaySocketDir != null) argv += "--bind=${displaySocketDir.absolutePath}:/tmp/.X11-unix"

        // The audio daemon's socket lives outside every rootfs; see [LinuxPulse].
        val sound = LinuxPulse.hostSocketDir(context).apply { mkdirs() }
        argv += "--bind=${sound.absolutePath}:/tmp/.sound"

        // Android's shared storage, when this app is allowed to read it. Skipped rather than
        // bound-and-broken otherwise, so a file dialog shows no directory instead of one that
        // refuses to open.
        LinuxStorage.binds(context, rootfs).forEach { argv += "--bind=$it" }

        extraBinds.forEach { argv += "--bind=$it" }

        // env rather than exec'ing the program directly: PRoot passes the host environment
        // through, and the guest needs a glibc-shaped one.
        argv += "/usr/bin/env"
        argv += guestEnv(displaySocketDir != null, extraEnv).map { (key, value) -> "$key=$value" }
        argv += guestArgv
        return argv
    }

    private fun guestEnv(hasDisplay: Boolean, extraEnv: Map<String, String>): Map<String, String> =
        buildMap {
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("HOME", GUEST_HOME)
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            // The guest has no clue where it is: glibc reads /etc/localtime, which the base
            // image leaves at UTC, so a clock in the session would be hours out.
            put("TZ", TimeZone.getDefault().id)
            // Toolkits refuse to start without a runtime dir and otherwise warn on every launch.
            put("XDG_RUNTIME_DIR", "/tmp")
            if (hasDisplay) {
                put("DISPLAY", ":0")
                putAll(mesaEnv())
            }
            // Firefox (and Chromium-family browsers) spawn content processes behind a Linux
            // user-namespace / seccomp sandbox. Under PRoot those helpers SIGSEGV immediately,
            // so tabs open but never load. The sandbox cannot work here; disable it for the
            // whole graphical guest rather than special-casing browser argv.
            put("MOZ_DISABLE_CONTENT_SANDBOX", "1")
            put("MOZ_DISABLE_GMP_SANDBOX", "1")
            put("MOZ_DISABLE_RDD_SANDBOX", "1")
            // Utility process is where Firefox 15x may try media codecs; its sandbox blocks
            // dlopen of system libavcodec, so AAC (YouTube) fails with "no decoder found"
            // even when ffmpeg is installed and the RDD process has already mapped it.
            put("MOZ_DISABLE_UTILITY_SANDBOX", "1")
            // Host AAudio Pulse daemon; see [LinuxPulse]. Without this Cubeb falls through to
            // ALSA against Android's /dev/snd and media that needs an audio clock never starts.
            put("PULSE_SERVER", LinuxPulse.GUEST_SERVER)
            put("PULSE_LATENCY_MSEC", "60")
            putAll(extraEnv)
        }

    /**
     * The same thing as a single command string, for [ProcessHelper]. Safe only because
     * every path involved is under the app's data dir, which contains no spaces.
     */
    fun buildCommand(
        context: Context,
        argv: String,
        cwd: String = GUEST_HOME,
        extraEnv: Map<String, String> = emptyMap(),
        displaySocketDir: File? = defaultDisplaySocketDir(context),
        extraBinds: List<String> = emptyList(),
        rootfs: File = LinuxRootfs.rootfsDir(context),
    ): String = buildArgv(
        context,
        // Already a shell word list rather than an argv, since ProcessHelper takes a string.
        listOf(argv),
        cwd,
        extraEnv,
        displaySocketDir,
        extraBinds,
        rootfs,
    ).joinToString(" ")

    /** Environment for the PRoot process itself, as opposed to the guest's. */
    fun prootEnv(context: Context, rootfs: File = LinuxRootfs.rootfsDir(context)): EnvVars {
        val libDir = nativeLibraryDir(context)
        val env = EnvVars()
        // PRoot embeds a copy of the loader, but it would have to extract it to a file to
        // exec it, and an app may not exec from its own data dir. Pointing at the copy in
        // the lib dir, which is exec-allowed, avoids that.
        env.put("PROOT_LOADER", "$libDir/$PROOT_LOADER")
        env.put("PROOT_TMP_DIR", rootfs.absolutePath + "/tmp")
        return env
    }

    /** Starts [argv] in the rootfs and returns its pid, or -1 on failure. */
    fun exec(
        context: Context,
        argv: String,
        cwd: String = GUEST_HOME,
        extraEnv: Map<String, String> = emptyMap(),
        displaySocketDir: File? = defaultDisplaySocketDir(context),
        extraBinds: List<String> = emptyList(),
        onTerminated: ((Int) -> Unit)? = null,
        rootfs: File = LinuxRootfs.rootfsDir(context),
    ): Int {
        val command = buildCommand(context, argv, cwd, extraEnv, displaySocketDir, extraBinds, rootfs)
        Timber.i("[LinuxProgramLauncher]: exec %s", argv)
        return ProcessHelper.exec(
            command,
            prootEnv(context, rootfs).toStringArray(),
            rootfs,
            Callback<Int> { status -> onTerminated?.invoke(status) },
        )
    }

    /**
     * Chromium and Electron abort when the guest is root unless `--no-sandbox` is set.
     * PRoot's `--root-id` makes every guest process root, so those binaries never get past
     * startup. The flag is added only for those programs, and only when it is not already there.
     */
    internal fun argvForRootGuest(argv: String): String {
        val tokens = argv.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return argv
        val name = tokens.first().substringAfterLast('/')
        if (name !in ROOT_SANDBOX_BINS) return argv
        if (tokens.any { it == "--no-sandbox" }) return argv
        return argv.trim() + " --no-sandbox"
    }

    /**
     * Starts [argv] and returns the process, with its output logged under [tag].
     *
     * Unlike [exec] this keeps reading the child's pipes, which a long-lived process needs:
     * nothing drains them otherwise, and a server that logs enough to fill the 64K pipe
     * buffer would block in write() forever.
     */
    fun start(
        context: Context,
        argv: String,
        tag: String,
        cwd: String = GUEST_HOME,
        extraEnv: Map<String, String> = emptyMap(),
        displaySocketDir: File? = defaultDisplaySocketDir(context),
        extraBinds: List<String> = emptyList(),
        rootfs: File = LinuxRootfs.rootfsDir(context),
    ): Process? {
        val guestArgv = argvForRootGuest(argv)
        val command = buildCommand(context, guestArgv, cwd, extraEnv, displaySocketDir, extraBinds, rootfs)
        Timber.i("[LinuxProgramLauncher]: start %s", guestArgv)

        val process = ProcessHelper.startProcess(
            command,
            prootEnv(context, rootfs).toStringArray(),
            rootfs,
        ) ?: return null

        drain(process.inputStream, tag)
        drain(process.errorStream, tag)
        return process
    }

    /**
     * Host paths bound into the guest for Android-X graphical clients (DRI3 / GBM).
     */
    fun graphicalBinds(): List<String> = buildList {
        val dri = File("/dev/dri")
        if (dri.isDirectory) add("${dri.absolutePath}:/dev/dri")
    }

    /**
     * Mesa / libGL hints so guest clients prefer DRI3 against the Android X server.
     */
    fun mesaEnv(): Map<String, String> = mapOf(
        "LIBGL_DRI3_ENABLE" to "1",
        // Avoid accidental EGL/Wayland attempts under PRoot.
        "GDK_BACKEND" to "x11",
        "QT_QPA_PLATFORM" to "xcb",
    )

    /**
     * The pid behind a [Process]. Android's Process.pid() needs API 33 with desugaring, so
     * this reads the field the same way [ProcessHelper] does.
     */
    fun pidOf(process: Process): Int = runCatching {
        process.javaClass.getDeclaredField("pid").let { field ->
            field.isAccessible = true
            field.getInt(process)
        }
    }.getOrDefault(-1)

    private fun drain(stream: java.io.InputStream, tag: String) {
        Thread({
            runCatching {
                stream.bufferedReader().forEachLine { line -> Timber.i("[%s]: %s", tag, line) }
            }
        }, "drain-$tag").apply { isDaemon = true }.start()
    }

    /** Runs [argv] to completion and returns its combined output. For short commands. */
    fun runWithOutput(
        context: Context,
        argv: String,
        cwd: String = GUEST_HOME,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 30,
        rootfs: File = LinuxRootfs.rootfsDir(context),
    ): String = ProcessHelper.execWithOutput(
        buildCommand(context, argv, cwd, extraEnv, rootfs = rootfs),
        prootEnv(context, rootfs).toStringArray(),
        rootfs,
        true,
        timeoutSeconds,
    )

    /**
     * The X server's socket directory, or null when no session is running. Callers that
     * launch GUI apps should bring a session up first.
     */
    fun defaultDisplaySocketDir(context: Context): File? =
        File(ImageFs.find(context).rootDir, "tmp/.X11-unix").takeIf { it.isDirectory }
}
