package app.gamenative.utils

import com.winlator.core.envvars.EnvVars
import timber.log.Timber

/**
 * Strips ARM Turnip/Wrapper env vars on x86_64 hosts using the system Vulkan driver
 * (Intel/AMD Mesa on ax86 tablets) and applies DXVK defaults needed for Proton.
 */
object HostGraphicsEnv {
    private val ARM_ONLY_KEYS = setOf(
        "TU_DEBUG",
        "ZINK_DESCRIPTORS",
        "ZINK_DEBUG",
        "VK_INSTANCE_LAYERS",
        "VK_LAYER_PATH",
        "ENABLE_UTIL_LAYER",
        "GALLIUM_DRIVER",
        "LIBGL_KOPPER_DISABLE",
        "VORTEK_SERVER_PATH",
        "WRAPPER_VKINSTANCE_VERSION",
        "mesa_glthread",
    )

    private const val DXVK_DLL_OVERRIDES =
        "d3d8=n,b;d3d9=n,b;d3d10=n,b;d3d11=n,b;dxgi=n,b"

    @JvmStatic
    fun sanitizeForSystemVulkan(envVars: EnvVars) {
        if (!HostCpu.current().isX86_64) return

        ARM_ONLY_KEYS.forEach { envVars.remove(it) }
        envVars.iterator().asSequence().toList().forEach { key ->
            if (key.startsWith("WRAPPER_") || key.startsWith("ZINK_")) {
                envVars.remove(key)
            }
        }

        val icd = envVars.get("VK_ICD_FILENAMES")
        if (icd.contains("aarch64", ignoreCase = true)) {
            envVars.remove("VK_ICD_FILENAMES")
        }

        // Intel/AMD WSI on ax86 is more reliable with fifo than mailbox (container
        // Bliss configs often ship mailbox from ARM Turnip tuning).
        envVars.put("MESA_VK_WSI_PRESENT_MODE", "fifo")

        ensureDxvkDllOverrides(envVars)
        Timber.i("HostGraphicsEnv: sanitized guest env for x86_64 system Vulkan")
    }

    @JvmStatic
    fun ensureDxvkDllOverrides(envVars: EnvVars) {
        val existing = envVars.get("WINEDLLOVERRIDES")
        if (existing.contains("d3d9=n")) return
        envVars.put(
            "WINEDLLOVERRIDES",
            if (existing.isEmpty()) DXVK_DLL_OVERRIDES else "$existing;$DXVK_DLL_OVERRIDES",
        )
    }
}
