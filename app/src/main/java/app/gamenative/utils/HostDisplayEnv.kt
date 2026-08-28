package app.gamenative.utils

import com.winlator.core.envvars.EnvVars
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.ImageFs
import timber.log.Timber
import java.io.File

/**
 * X11 display wiring for x86_64 hosts without [libredirect-bionic-wx.so].
 *
 * The in-app X server binds a Unix socket under `{imagefs}/tmp/.X11-unix/X0`, but
 * guest libX11 hardcodes `/tmp/.X11-unix` when DISPLAY is `:0`. Point DISPLAY at the
 * absolute socket path instead and align TMPDIR with the X server tmp root.
 */
object HostDisplayEnv {
  /** Absolute path to the X0 Unix socket (same layout as [UnixSocketConfig.XSERVER_PATH]). */
  @JvmStatic
  fun x11UnixSocketPath(tmpDir: String): String =
    "$tmpDir${UnixSocketConfig.XSERVER_PATH.removePrefix("/tmp")}"

  @JvmStatic
  fun applyForX86_64Guest(envVars: EnvVars, imageFs: ImageFs) {
    if (!HostCpu.current().isX86_64) return

    val tmpDir = imageFs.tmpDir.absolutePath
    File(tmpDir, ".X11-unix").mkdirs()

    envVars.put("TMPDIR", tmpDir)
    envVars.put("XDG_RUNTIME_DIR", tmpDir)
    envVars.put("DISPLAY", x11UnixSocketPath(tmpDir))
    envVars.remove("WINE_X11FORCEGLX")

    Timber.i(
      "HostDisplayEnv: x86_64 X11 DISPLAY=%s TMPDIR=%s",
      envVars.get("DISPLAY"),
      tmpDir,
    )
  }
}
