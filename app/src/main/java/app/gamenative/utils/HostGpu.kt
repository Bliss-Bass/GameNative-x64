package app.gamenative.utils

import java.io.File

/**
 * GPU vendor of the host's DRM render node.
 *
 * Used to decide which Vulkan ICD payload to provision for the x86_64 guest. It is
 * deliberately not used to pick a driver at run time: each Mesa ICD probes DRM itself,
 * so the Khronos loader already resolves that when several manifests are staged.
 */
enum class HostGpu {
    INTEL,
    AMD,
    NVIDIA,
    VIRTIO,
    UNKNOWN,
    ;

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

        private val CARD_NAME = Regex("^card\\d+$")

        private fun readVendorId(file: File): Int? {
            val text = runCatching { file.readText() }.getOrNull()?.trim() ?: return null
            return text.removePrefix("0x").toIntOrNull(16)
        }
    }
}
