package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import com.winlator.core.TarCompressorUtils
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

    /**
     * Khronos loader + ICDs for ANV (Intel), RADV (AMD) and lavapipe (software).
     * Built by scripts/provision-x86_64-vulkan.sh tarball.
     */
    const val VULKAN_ASSET = "vulkan-x86_64-20260828.tzst"

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

    private fun vulkanStampFile(context: Context): File =
        File(hostVulkanRoot(context), ".asset_version")

    /**
     * Extract the guest Vulkan stack, once per asset version.
     *
     * Deliberately not part of [refreshComponentsFiles]' unconditional extraction: this
     * tree unpacks to ~227MB (lavapipe drags in libLLVM, kept so software rendering can
     * be forced), which is far too slow to redo on every launch. A stamp file records the
     * staged asset name, so a new asset re-extracts and an unchanged one is skipped.
     *
     * Without this the stack only ever existed where it had been pushed by hand over adb,
     * so a fresh install had no ICDs and no X11-capable loader, and DXVK could not get a
     * surface.
     */
    @JvmStatic
    fun ensureVulkanStack(context: Context) {
        if (!HostCpu.current().isX86_64) return
        val root = hostVulkanRoot(context)
        val stamp = vulkanStampFile(context)
        if (hasStagedVulkanLoader(context) &&
            stamp.isFile && stamp.readText().trim() == VULKAN_ASSET
        ) {
            return
        }

        val tmp = File(root.parentFile, "${root.name}.tmp")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()

        val ok = TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD, context.assets, VULKAN_ASSET, tmp,
        )
        if (!ok) {
            Timber.e("HostBionicLibs: failed to extract %s; guest Vulkan unavailable", VULKAN_ASSET)
            tmp.deleteRecursively()
            return
        }

        if (root.exists()) root.deleteRecursively()
        if (!tmp.renameTo(root)) {
            Timber.e("HostBionicLibs: failed to promote extracted %s", VULKAN_ASSET)
            tmp.deleteRecursively()
            return
        }
        File(root, ".asset_version").writeText(VULKAN_ASSET)
        Timber.i(
            "HostBionicLibs: staged guest Vulkan from %s -> %s",
            VULKAN_ASSET,
            stagedIcdManifests(context).joinToString { it.name },
        )
    }

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

    /**
     * Puts our SysV shm broker behind the name Mesa's ICDs were linked against.
     *
     * They carry a DT_NEEDED on `libandroid-shmem.so`, and bionic resolves a symbol referenced from a
     * dlopen'd library out of that library's own group before it consults LD_PRELOAD, so the shipped
     * Termux implementation answers `shmget` however we preload. Its ids are process-local and mean
     * nothing to the X server, which then refuses the attach. Both files are replaced because
     * LD_LIBRARY_PATH lists the two trees and only their order decides which one is found.
     */
    @JvmStatic
    fun overrideGuestShmemLib(context: Context) {
        val ours = File(context.applicationInfo.nativeLibraryDir, "libandroid-shmem.so")
        if (!ours.isFile) {
            Timber.w("HostBionicLibs: no libandroid-shmem.so to stage; guest shm stays Termux's")
            return
        }

        for (root in listOf(hostLibsRoot(context), hostVulkanRoot(context))) {
            val staged = File(root, "usr/lib/libandroid-shmem.so")
            if (!staged.isFile || staged.length() == ours.length()) continue
            runCatching { ours.copyTo(staged, overwrite = true) }
                .onSuccess { Timber.i("HostBionicLibs: staged our shm broker over %s", staged.path) }
                .onFailure { Timber.e(it, "HostBionicLibs: could not replace %s", staged.path) }
        }
    }

    /** Points the guest Vulkan loader at staged ICDs so DXVK can get an X11 surface. */
    @JvmStatic
    fun applyGuestVulkanEnv(envVars: EnvVars, context: Context) {
        if (!HostCpu.current().isX86_64) return
        val manifests = stagedIcdManifests(context)
        if (manifests.isEmpty()) return

        overrideGuestShmemLib(context)
        val value = manifests.joinToString(":") { it.absolutePath }
        envVars.put("VK_ICD_FILENAMES", value)
        envVars.put("VK_DRIVER_FILES", value)

        // How the frame gets from the guest to the X server is the user's choice, because each route
        // needs a different part of the stack to be present and none of them degrades gracefully:
        //
        //   software  every frame read back to the CPU and pushed with PutImage. `noshm` is what keeps
        //             MIT-SHM out of it -- Mesa enables shm whenever DRI3 and Present are advertised,
        //             and the server's MIT-SHM is the 1.1 shmid variant, which needs the guest's
        //             shmget to come from our own SysV broker. Without the x86_64 interposer that
        //             call lands in Termux's libandroid-shmem and yields a shmid the server never
        //             issued, so ShmAttach fails and PutImage raises BadSHMSegment.
        //   shm       the same copy, but read out of a shared segment rather than the socket.
        //   dri3      no copy: the guest exports the image and the server imports the dma-buf.
        //
        // See docs/X86_64_VULKAN_PROVISIONING.md.
        val path = PrefManager.presentationPath
        if (path.wsiDebug.isNotEmpty()) {
            envVars.put("MESA_VK_WSI_DEBUG", path.wsiDebug)
        }

        Timber.i("HostBionicLibs: guest Vulkan ICDs -> %s (presentation=%s)", value, path.key)
    }
}
