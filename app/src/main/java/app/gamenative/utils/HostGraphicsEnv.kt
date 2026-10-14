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
        // DXGI hides Intel and reports an RX 6700 XT (0x73df) once libxess.dll is
        // loaded. That lie makes games pick a desktop preset. A custom vendor skips
        // the hide and leaves the real device id in place.
        pinIntelDxgiVendor(envVars)
        Timber.i("HostGraphicsEnv: sanitized guest env for x86_64 system Vulkan")
    }

    /**
     * Keep DXGI advertising this machine's Intel vendor.
     *
     * DXVK forces `dxgi.hideIntelGpu` when XeSS is loaded, and that path is
     * skipped entirely once `dxgi.customVendorId` is set. The device id is left
     * alone, so the guest still sees this GPU rather than a stand-in.
     */
    @JvmStatic
    fun pinIntelDxgiVendor(envVars: EnvVars) {
        val gpu = HostGpu.current()
        val vulkanHw = systemProperty("ro.hardware.vulkan")
        if (!shouldPinIntelDxgi(gpu, vulkanHw)) {
            Timber.i("HostGraphicsEnv: left DXGI vendor alone (drm=$gpu vulkan=$vulkanHw)")
            return
        }
        pinIntelDxgiVendor(envVars, HostGpu.INTEL)
    }

    /**
     * DRM sysfs is not readable from the app on some Android builds (the Googlebook
     * publishes the GPU as `ro.hardware.vulkan=intel` instead). A known non-Intel
     * DRM vendor still wins, so an AMD host is never pinned from a stale property.
     */
    @JvmStatic
    fun shouldPinIntelDxgi(gpu: HostGpu, vulkanHardware: String): Boolean {
        if (gpu == HostGpu.INTEL) return true
        if (gpu != HostGpu.UNKNOWN) return false
        return vulkanHardware.equals("intel", ignoreCase = true)
    }

    @JvmStatic
    fun pinIntelDxgiVendor(envVars: EnvVars, gpu: HostGpu) {
        if (gpu != HostGpu.INTEL) return
        val raw = envVars.get("DXVK_CONFIG")
        if (raw.contains("dxgi.customVendorId")) return
        val inner = raw.trim().removePrefix("\"").removeSuffix("\"").trim()
        val pin = "dxgi.customVendorId = 8086"
        envVars.put("DXVK_CONFIG", if (inner.isEmpty()) pin else "$inner; $pin")
        Timber.i("HostGraphicsEnv: pinned DXGI vendor to Intel 8086")
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

    private fun systemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
