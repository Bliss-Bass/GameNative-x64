package app.gamenative.utils

import android.content.Context
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
     * Wine dlopen("libvulkan.so.1"); Android only provides /system/lib64/libvulkan.so.
     * Idempotent — safe to call on every launch after host libs extraction.
     */
    @JvmStatic
    fun ensureVulkanLoaderSymlink(context: Context) {
        if (!HostCpu.current().isX86_64) return
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
}
