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
     */
    fun buildArgv(
        context: Context,
        guestArgv: List<String>,
        cwd: String = GUEST_HOME,
        extraEnv: Map<String, String> = emptyMap(),
        displaySocketDir: File? = defaultDisplaySocketDir(context),
        extraBinds: List<String> = emptyList(),
    ): List<String> {
        val libDir = nativeLibraryDir(context)
        val rootfs = LinuxRootfs.rootfsDir(context)

        val argv = mutableListOf("$libDir/$PROOT")
        argv += "--kill-on-exit"
        // dpkg chowns every file it unpacks, so without fake root apt cannot install
        // anything -- the feature's whole point.
        argv += "--root-id"
        // Deliberately no --link2symlink: /data is ext4 and takes hardlinks, and PRoot's
        // emulation writes host-absolute targets into the .l2s symlinks it creates, which
        // do not resolve from inside the guest. dpkg's unpack-and-rename left perl -- and
        // so debconf, and so every package with a postinst -- pointing at nothing.
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
            if (hasDisplay) put("DISPLAY", ":0")
            // Firefox (and Chromium-family browsers) spawn content processes behind a Linux
            // user-namespace / seccomp sandbox. Under PRoot those helpers SIGSEGV immediately,
            // so tabs open but never load. The sandbox cannot work here; disable it for the
            // whole graphical guest rather than special-casing browser argv.
            put("MOZ_DISABLE_CONTENT_SANDBOX", "1")
            put("MOZ_DISABLE_GMP_SANDBOX", "1")
            put("MOZ_DISABLE_RDD_SANDBOX", "1")
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
    ): String = buildArgv(
        context,
        // Already a shell word list rather than an argv, since ProcessHelper takes a string.
        listOf(argv),
        cwd,
        extraEnv,
        displaySocketDir,
        extraBinds,
    ).joinToString(" ")

    /** Environment for the PRoot process itself, as opposed to the guest's. */
    fun prootEnv(context: Context): EnvVars {
        val libDir = nativeLibraryDir(context)
        val env = EnvVars()
        // PRoot embeds a copy of the loader, but it would have to extract it to a file to
        // exec it, and an app may not exec from its own data dir. Pointing at the copy in
        // the lib dir, which is exec-allowed, avoids that.
        env.put("PROOT_LOADER", "$libDir/$PROOT_LOADER")
        env.put("PROOT_TMP_DIR", LinuxRootfs.rootfsDir(context).absolutePath + "/tmp")
        return env
    }

    /** Starts [argv] in the rootfs and returns its pid, or -1 on failure. */
    fun exec(
        context: Context,
        argv: String,
        cwd: String = GUEST_HOME,
        extraEnv: Map<String, String> = emptyMap(),
        displaySocketDir: File? = defaultDisplaySocketDir(context),
        onTerminated: ((Int) -> Unit)? = null,
    ): Int {
        val command = buildCommand(context, argv, cwd, extraEnv, displaySocketDir)
        Timber.i("[LinuxProgramLauncher]: exec %s", argv)
        return ProcessHelper.exec(
            command,
            prootEnv(context).toStringArray(),
            LinuxRootfs.rootfsDir(context),
            Callback<Int> { status -> onTerminated?.invoke(status) },
        )
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
    ): Process? {
        val command = buildCommand(context, argv, cwd, extraEnv, displaySocketDir)
        Timber.i("[LinuxProgramLauncher]: start %s", argv)

        val process = ProcessHelper.startProcess(
            command,
            prootEnv(context).toStringArray(),
            LinuxRootfs.rootfsDir(context),
        ) ?: return null

        drain(process.inputStream, tag)
        drain(process.errorStream, tag)
        return process
    }

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
    ): String = ProcessHelper.execWithOutput(
        buildCommand(context, argv, cwd, extraEnv),
        prootEnv(context).toStringArray(),
        LinuxRootfs.rootfsDir(context),
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
