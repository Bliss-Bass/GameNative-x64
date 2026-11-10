package app.gamenative.utils

import android.content.Context
import android.util.Log
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import java.io.File

/**
 * How an x86_64 guest launch differs from upstream's, kept out of
 * BionicProgramLauncherComponent so upstream changes to that file merge cleanly.
 * Every call does nothing on other hosts.
 */
object X86GuestLaunch {
    private const val TAG = "X86GuestLaunch"

    /**
     * LD_PRELOAD for an x86_64 guest, replacing upstream's list whole. imagefs only carries ARM
     * builds of upstream's preloads, and there is no libredirect for x86_64: the SysV shm broker
     * and the exec redirect (which lets Wine's loader and its children run from app storage) come
     * from the APK's native library directory. Null on other hosts.
     */
    @JvmStatic
    fun preloads(context: Context, withEvshim: Boolean): String? {
        if (!HostCpu.current().isX86_64) return null
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libs = listOfNotNull(
            nativeLib(nativeDir, "libandroid-sysvshm.so"),
            if (withEvshim) nativeLib(nativeDir, "libevshim.so") else null,
            nativeLib(nativeDir, "libexec-redirect.so"),
        )
        return libs.joinToString(":")
    }

    private fun nativeLib(dir: String, name: String): String? {
        val file = File(dir, name)
        if (file.exists()) return file.path
        Log.w(TAG, "$name missing at ${file.path}")
        return null
    }

    /**
     * Display variables, and with [vulkan] the system-driver cleanup and the staged guest Vulkan
     * stack, for an x86_64 guest.
     */
    @JvmStatic
    fun applyGuestEnv(envVars: EnvVars, context: Context, imageFs: ImageFs, container: Container?, vulkan: Boolean) {
        if (!HostCpu.current().isX86_64) return
        HostDisplayEnv.applyForX86_64Guest(envVars, imageFs)
        if (!vulkan) return
        if (container != null && container.graphicsDriver.equals("System", ignoreCase = true)) {
            HostGraphicsEnv.sanitizeForSystemVulkan(envVars)
        }
        HostBionicLibs.applyGuestVulkanEnv(envVars, context, container)
    }
}
