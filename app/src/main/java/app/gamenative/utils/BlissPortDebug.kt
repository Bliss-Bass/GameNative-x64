package app.gamenative.utils

import app.gamenative.BuildConfig
import com.winlator.core.envvars.EnvVars
import timber.log.Timber

/**
 * Extra launch-time diagnostics for ax86 [BuildConfig.BLISS_PORT_DEBUG] builds.
 * Does not run on upstream GameNative flavors or when Diagnostic Run is active.
 */
object BlissPortDebug {
    const val WINE_DEBUG_CHANNELS = "+seh,+module,+d3d9,+dxgi,+vulkan"

    @JvmStatic
    fun isActive(diagnostics: Boolean): Boolean =
        BuildConfig.BLISS_PORT_DEBUG && !diagnostics

    @JvmStatic
    fun applyLaunchEnv(envVars: EnvVars, diagnostics: Boolean) {
        if (!isActive(diagnostics)) return
        envVars.put("WINEDEBUG", WINE_DEBUG_CHANNELS)
        envVars.put("PROTON_LOG", "1")
        // Our SysV shm broker is silent unless asked, and its handful of calls per swapchain are the
        // only window onto whether guest Mesa reached it at all.
        envVars.put("ANDROID_SYSVSHM_DEBUG", "1")
        // The container defaults silence Mesa (MESA_DEBUG=silent, MESA_NO_ERROR=1), which hides the
        // one message that says why a driver gave up -- and on the shared-memory present path that
        // message is the whole diagnosis.
        envVars.remove("MESA_DEBUG")
        envVars.remove("MESA_NO_ERROR")
        val dxvkLog = envVars.get("DXVK_LOG_LEVEL")
        if (dxvkLog.isEmpty() || dxvkLog == "none") {
            envVars.put("DXVK_LOG_LEVEL", "info")
        }
        Timber.i("BlissPortDebug: enabled WINEDEBUG/PROTON_LOG for guest launch")
    }

    @JvmStatic
    fun logGuestLine(line: String) {
        Timber.tag("WineGuest").d(line)
    }
}
