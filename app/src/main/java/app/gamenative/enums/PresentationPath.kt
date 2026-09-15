package app.gamenative.enums

import app.gamenative.R

/**
 * How a guest Vulkan frame reaches the in-app X server on x86_64.
 *
 * Each route is a trade between how much copying a frame costs and how much of the stack has to be
 * in place for it to work at all, so the choice is a user setting with [SOFTWARE] as the safe default.
 */
enum class PresentationPath(
    val key: String,
    /** What Mesa's WSI is told; empty means "leave the hardware path alone". */
    val wsiDebug: String,
    val titleRes: Int,
    val summaryRes: Int,
) {
    /**
     * Software WSI with MIT-SHM disabled: every frame is read back to the CPU and pushed over the X
     * socket with PutImage. Slowest, but the only route that needs nothing beyond the X server itself.
     */
    SOFTWARE("software", "sw,noshm", R.string.present_software, R.string.present_software_summary),

    /**
     * Software WSI over MIT-SHM 1.1: the frame is still copied to the CPU, but the server reads it out
     * of a shared segment instead of the socket. Needs the guest's shmget to reach our SysV broker,
     * which is what the x86_64 interposer provides.
     */
    SHM("shm", "sw", R.string.present_shm, R.string.present_shm_summary),

    /**
     * DRI3 1.2 with GPU zero-copy when possible: guest ANV exports a dma-buf; the server imports
     * it as AHardwareBuffer (scanout-capable) or Vulkan external memory, with LINEAR mmap+opaque
     * upload as fallback.
     */
    DRI3("dri3", "", R.string.present_dri3, R.string.present_dri3_summary),
    ;

    companion object {
        /**
         * Software until the interposer and the DRI3 import path are proven on the device in hand --
         * a wrong guess here does not degrade, it takes the guest down mid-frame.
         */
        val DEFAULT = SOFTWARE

        fun fromKey(key: String?): PresentationPath =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
