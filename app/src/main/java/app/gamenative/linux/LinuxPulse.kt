package app.gamenative.linux

import android.content.Context
import app.gamenative.utils.AssetUtils
import app.gamenative.utils.HostBionicLibs
import com.winlator.core.FileUtils
import com.winlator.core.ProcessHelper
import com.winlator.core.TarCompressorUtils
import com.winlator.core.envvars.EnvVars
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.XEnvironment
import java.io.File
import timber.log.Timber

/**
 * Host PulseAudio (bionic + AAudio sink) for Linux graphical sessions.
 *
 * Games already run this under [com.winlator.xenvironment.components.PulseAudioComponent]
 * with a socket inside the Wine imagefs. Linux apps are a separate PRoot rootfs with no
 * audio bridge of their own: Firefox's Cubeb finds libasound, cannot open Android's
 * `/dev/snd`, and YouTube (and anything else that waits on an audio clock) never starts.
 *
 * The socket is created on the host at `{rootfs}/tmp/.sound/PS0`, which is `/tmp/.sound/PS0`
 * inside the guest — no extra bind. Guests must have `libpulse0` so Cubeb can speak the
 * native protocol.
 */
object LinuxPulse {

    /** Guest-side value for `PULSE_SERVER`. */
    const val GUEST_SERVER = "unix:${UnixSocketConfig.PULSE_SERVER_PATH}"

    private var holders = 0

    @Synchronized
    fun acquire(context: Context) {
        if (holders == 0) start(context)
        holders++
    }

    @Synchronized
    fun release(context: Context) {
        if (holders == 0) return
        holders--
        if (holders == 0) stop()
    }

    private fun start(context: Context) {
        ensureAssets(context)

        val workingDir = File(context.filesDir, "pulseaudio")
        val socket = UnixSocketConfig.createSocket(
            LinuxRootfs.rootfsDir(context).absolutePath,
            UnixSocketConfig.PULSE_SERVER_PATH,
        )

        val configFile = File(workingDir, "default.pa")
        FileUtils.writeString(
            configFile,
            """
            load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket="${socket.path}"
            load-module module-aaudio-sink volume=1.0 performance_mode=1
            """.trimIndent() + "\n",
        )

        killExisting()

        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val modulesDir = File(workingDir, "modules")
        val env = EnvVars().apply {
            put("LD_LIBRARY_PATH", "/system/lib64:$nativeLibraryDir:$modulesDir")
            put("HOME", workingDir.absolutePath)
            put("TMPDIR", XEnvironment.getTmpDir(context).absolutePath)
        }

        val command = buildString {
            append("$nativeLibraryDir/libpulseaudio.so")
            append(" --system=false")
            append(" --disable-shm=true")
            append(" --fail=false")
            append(" -n --file=default.pa")
            append(" --daemonize=true")
            append(" --use-pid-file=false")
            append(" --exit-idle-time=-1")
        }

        val output = ProcessHelper.execWithOutput(command, env.toStringArray(), workingDir, true)
        Timber.i("[LinuxPulse]: started at %s (%s)", socket.path, output.take(200))
    }

    private fun stop() {
        killExisting()
        Timber.i("[LinuxPulse]: stopped")
    }

    private fun killExisting() {
        val pids = ProcessHelper.listSubProcesses()
            .filter { it.name.contains("libpulseaudio.so") }
            .map { it.pid }
        if (pids.isEmpty()) return
        Timber.w("[LinuxPulse]: killing existing pulseaudio pids %s", pids)
        pids.forEach { ProcessHelper.killProcess(it) }
        runCatching { Thread.sleep(200) }
    }

    private fun ensureAssets(context: Context) {
        val workingDir = File(context.filesDir, "pulseaudio")
        val modules = File(workingDir, "modules")
        if (modules.isDirectory && File(workingDir, "pactl").canExecute()) return

        AssetUtils.extractComponentsWithVersionCheck(
            listOf(HostBionicLibs.pulseAssetName() to workingDir),
            context.assets,
            TarCompressorUtils.Type.ZSTD,
        )
    }
}
