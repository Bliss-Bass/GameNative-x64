package app.gamenative.linux

import android.content.Context
import com.winlator.core.Callback
import com.winlator.core.envvars.EnvVars
import com.winlator.core.ProcessHelper
import com.winlator.xenvironment.ImageFs
import java.io.File
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
     * Builds the command line that runs [argv] in the rootfs.
     *
     * [displaySocketDir] is bound onto the guest's /tmp/.X11-unix so an X client finds
     * the server at DISPLAY=:0. Stock libX11 does not accept a socket path in DISPLAY,
     * which is why this is a bind rather than an environment variable.
     */
    fun buildCommand(
        context: Context,
        argv: String,
        cwd: String = GUEST_HOME,
        extraEnv: Map<String, String> = emptyMap(),
        displaySocketDir: File? = defaultDisplaySocketDir(context),
        extraBinds: List<String> = emptyList(),
    ): String {
        val libDir = nativeLibraryDir(context)
        val rootfs = LinuxRootfs.rootfsDir(context)

        val guestEnv = EnvVars()
        guestEnv.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        guestEnv.put("HOME", GUEST_HOME)
        guestEnv.put("TERM", "xterm-256color")
        guestEnv.put("LANG", "C.UTF-8")
        // Toolkits refuse to start without a runtime dir and otherwise warn on every launch.
        guestEnv.put("XDG_RUNTIME_DIR", "/tmp")
        if (displaySocketDir != null) guestEnv.put("DISPLAY", ":0")
        extraEnv.forEach { (key, value) -> guestEnv.put(key, value) }

        val command = StringBuilder("$libDir/$PROOT")
        command.append(" --kill-on-exit")
        // dpkg chowns every file it unpacks, so without fake root apt cannot install
        // anything -- the feature's whole point.
        command.append(" --root-id")
        // Debian packages contain hardlinks; PRoot turns them into symlinks it tracks,
        // which also keeps the rootfs working on filesystems that refuse hardlinks.
        command.append(" --link2symlink")
        command.append(" --rootfs=").append(rootfs.absolutePath)
        command.append(" --cwd=").append(cwd)
        command.append(" --bind=/dev")
        command.append(" --bind=/proc")
        command.append(" --bind=/sys")

        // /dev/shm is absent on Android; a directory in the rootfs stands in for it, which
        // anything using POSIX shared memory (most toolkits) needs.
        val shm = File(rootfs, "tmp/shm").apply { mkdirs() }
        command.append(" --bind=").append(shm.absolutePath).append(":/dev/shm")

        if (displaySocketDir != null) {
            command.append(" --bind=").append(displaySocketDir.absolutePath).append(":/tmp/.X11-unix")
        }
        extraBinds.forEach { command.append(" --bind=").append(it) }

        command.append(" /usr/bin/env ").append(guestEnv.toEscapedString()).append(' ').append(argv)
        return command.toString()
    }

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
        onTerminated: ((Int) -> Unit)? = null,
    ): Int {
        val command = buildCommand(context, argv, cwd, extraEnv)
        Timber.i("[LinuxProgramLauncher]: exec %s", argv)
        return ProcessHelper.exec(
            command,
            prootEnv(context).toStringArray(),
            LinuxRootfs.rootfsDir(context),
            Callback<Int> { status -> onTerminated?.invoke(status) },
        )
    }

    /** Runs [argv] to completion and returns its combined output. For short commands. */
    fun runWithOutput(
        context: Context,
        argv: String,
        cwd: String = GUEST_HOME,
        timeoutSeconds: Int = 30,
    ): String = ProcessHelper.execWithOutput(
        buildCommand(context, argv, cwd),
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
