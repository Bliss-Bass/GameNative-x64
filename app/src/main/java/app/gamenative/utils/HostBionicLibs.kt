package app.gamenative.utils

import android.content.Context
import java.io.File

/**
 * x86_64 host-side bionic libs and component assets (imagefs/usr/lib is ARM-only).
 */
object HostBionicLibs {
    const val PULSE_ASSET_ARM = "pulseaudio-gamenative-20260612.tzst"
    const val PULSE_ASSET_X86_64 = "pulseaudio-gamenative-x86_64-20260827.tzst"
    const val BIONIC_LIBS_ASSET = "bionic-libs-x86_64-20260827.tzst"

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
}
