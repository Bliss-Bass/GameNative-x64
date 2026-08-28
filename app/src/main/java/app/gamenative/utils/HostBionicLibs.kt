package app.gamenative.utils

import android.content.Context
import com.winlator.core.envvars.EnvVars
import timber.log.Timber
import java.io.File
import java.nio.file.Files

/**
 * x86_64 host-side bionic libs and component assets (imagefs/usr/lib is ARM-only).
 */
object HostBionicLibs {
    const val PULSE_ASSET_ARM = "pulseaudio-gamenative-20260612.tzst"
    const val PULSE_ASSET_X86_64 = "pulseaudio-gamenative-x86_64-20260827.tzst"
    const val BIONIC_LIBS_ASSET = "bionic-libs-x86_64-20260828.tzst"

    @JvmStatic
    fun pulseAssetName(): String =
        if (HostCpu.current().isX86_64) PULSE_ASSET_X86_64 else PULSE_ASSET_ARM

    @JvmStatic
    fun hostLibsRoot(context: Context): File =
        File(context.filesDir, "host_libs_x86_64")

    @JvmStatic
    fun hostUsrLibDir(context: Context): File =
        File(hostLibsRoot(context), "usr/lib")

    @JvmStatic
    fun hostFontsConfigDir(context: Context): File =
        File(hostLibsRoot(context), "usr/etc/fonts")

    /**
     * Guest Vulkan stack (Khronos loader + ICDs). Kept out of [hostLibsRoot] because that
     * tree is deleted and re-extracted from assets on every launch.
     */
    @JvmStatic
    fun hostVulkanRoot(context: Context): File =
        File(context.filesDir, "host_vk_x86_64")

    @JvmStatic
    fun hostVulkanLibDir(context: Context): File =
        File(hostVulkanRoot(context), "usr/lib")

    @JvmStatic
    fun vulkanIcdDir(context: Context): File =
        File(hostVulkanRoot(context), "usr/share/vulkan/icd.d")

    @JvmStatic
    fun stagedIcdManifests(context: Context): List<File> =
        vulkanIcdDir(context)
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** True when a full Khronos loader is staged (Android's loader has no X11 surfaces). */
    @JvmStatic
    fun hasStagedVulkanLoader(context: Context): Boolean =
        File(hostVulkanLibDir(context), "libvulkan.so.1").isFile && stagedIcdManifests(context).isNotEmpty()

    /**
     * Wine dlopen("libvulkan.so.1"); Android only provides /system/lib64/libvulkan.so.
     * Idempotent — safe to call on every launch after host libs extraction.
     *
     * Skipped when a Khronos loader is staged: that one enumerates ICD manifests and can
     * expose X11 surfaces, whereas Android's loader only ever offers android_surface.
     */
    @JvmStatic
    fun ensureVulkanLoaderSymlink(context: Context) {
        if (!HostCpu.current().isX86_64) return
        if (hasStagedVulkanLoader(context)) return
        val libDir = hostUsrLibDir(context)
        if (!libDir.isDirectory) return
        val link = File(libDir, "libvulkan.so.1")
        val systemVulkan = File("/system/lib64/libvulkan.so")
        if (!systemVulkan.isFile) return
        if (link.exists()) {
            link.delete()
        }
        Files.createSymbolicLink(link.toPath(), systemVulkan.toPath())
    }

    /** Points the guest Vulkan loader at staged ICDs so DXVK can get an X11 surface. */
    @JvmStatic
    fun applyGuestVulkanEnv(envVars: EnvVars, context: Context) {
        if (!HostCpu.current().isX86_64) return
        val manifests = stagedIcdManifests(context)
        if (manifests.isEmpty()) return
        val value = manifests.joinToString(":") { it.absolutePath }
        envVars.put("VK_ICD_FILENAMES", value)
        envVars.put("VK_DRIVER_FILES", value)

        // The in-app X server has no DRI3/Present buffer sharing without the Vortek
        // renderer (arm64-only), so keep Mesa's WSI on the software XPutImage path.
        // `noshm` additionally keeps MIT-SHM out of it: Mesa enables shm whenever DRI3
        // and Present are advertised, which routes presentation through bionic SysV-shm
        // emulation and costs the guest its X connection mid-frame.
        envVars.put("MESA_VK_WSI_DEBUG", "sw,noshm")

        Timber.i("HostBionicLibs: guest Vulkan ICDs -> %s (WSI=sw)", value)
    }
}
