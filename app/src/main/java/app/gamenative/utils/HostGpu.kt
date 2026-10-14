package app.gamenative.utils

import java.io.File

/**
 * GPU vendor of the host's DRM render node.
 *
 * Used to decide which Vulkan ICD payload to provision for the x86_64 guest, and which
 * staged manifests the guest loader sees ([vulkanIcdsFrom]).
 */
enum class HostGpu {
    INTEL,
    AMD,
    NVIDIA,
    VIRTIO,
    UNKNOWN,
    ;

    /**
     * The staged ICD manifests the guest should see on this GPU.
     *
     * Lavapipe always enumerates a device, so leaving it beside the hardware driver hands
     * the guest a second, CPU-backed GPU. DXVK skips it, but native Vulkan games such as
     * DOOM (2016) run their own device checks and quit on it. Every manifest is kept when
     * the vendor is unknown or its driver is missing, so lavapipe remains the fallback.
     */
    fun vulkanIcdsFrom(manifests: List<File>): List<File> {
        val prefix = when (this) {
            INTEL -> "intel_icd"
            AMD -> "radeon_icd"
            else -> return manifests
        }
        return manifests.filter { it.name.startsWith(prefix) }.ifEmpty { manifests }
    }

    companion object {
        private const val DRM_CLASS_DIR = "/sys/class/drm"

        // PCI vendor IDs as published in /sys/class/drm/card*/device/vendor.
        private const val PCI_INTEL = 0x8086
        private const val PCI_AMD = 0x1002
        private const val PCI_AMD_ATI = 0x1022
        private const val PCI_NVIDIA = 0x10de
        private const val PCI_VIRTIO = 0x1af4
        private const val PCI_VIRTIO_REDHAT = 0x1b36

        @JvmStatic
        fun current(): HostGpu = detectIn(File(DRM_CLASS_DIR))

        /**
         * Reads the vendor of the first real card node under [drmClassDir].
         *
         * Connector nodes such as `card1-eDP-1` are siblings of the card they belong to
         * and carry the same `device` link, so they are skipped to keep the result stable.
         */
        @JvmStatic
        fun detectIn(drmClassDir: File): HostGpu {
            val cards = drmClassDir
                .listFiles { f -> f.name.matches(CARD_NAME) }
                ?.sortedBy { it.name }
                ?: return UNKNOWN

            for (card in cards) {
                val vendor = readVendorId(File(card, "device/vendor")) ?: continue
                val gpu = fromPciVendorId(vendor)
                if (gpu != UNKNOWN) return gpu
            }
            return UNKNOWN
        }

        @JvmStatic
        fun fromPciVendorId(id: Int): HostGpu = when (id) {
            PCI_INTEL -> INTEL
            PCI_AMD, PCI_AMD_ATI -> AMD
            PCI_NVIDIA -> NVIDIA
            PCI_VIRTIO, PCI_VIRTIO_REDHAT -> VIRTIO
            else -> UNKNOWN
        }

        /** Maps `ro.hardware.vulkan`, which some builds publish when DRM sysfs is unreadable. */
        @JvmStatic
        fun fromVulkanHardware(name: String): HostGpu = when (name.trim().lowercase()) {
            "intel" -> INTEL
            "radeon", "amd", "amdgpu" -> AMD
            else -> UNKNOWN
        }

        private val CARD_NAME = Regex("^card\\d+$")

        private fun readVendorId(file: File): Int? {
            val text = runCatching { file.readText() }.getOrNull()?.trim() ?: return null
            return text.removePrefix("0x").toIntOrNull(16)
        }
    }
}
