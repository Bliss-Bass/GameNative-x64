package app.gamenative.enums

import app.gamenative.R

/**
 * How a guest Vulkan frame reaches the in-app X server on x86_64.
 *
 * Each route is a trade between how much copying a frame costs and how much of the stack has to be
 * in place for it to work at all, so the choice is a user setting. [AUTO] is the default; it is
 * resolved per game by [app.gamenative.utils.PresentationFallback] and never reaches Mesa itself.
 */
enum class PresentationPath(
    val key: String,
    /** What Mesa's WSI is told; empty means "leave the hardware path alone". */
    val wsiDebug: String,
    val titleRes: Int,
    val summaryRes: Int,
) {
    /**
     * [DRI3], unless this game's DRI3 buffer imports were rejected before, then [SOFTWARE].
     * Software copy is not a universal floor (it shows no picture on some Android 17 devices), so
     * only a rejected import moves a game off DRI3, never an early exit on its own.
     */
    AUTO("auto", "", R.string.present_auto, R.string.present_auto_summary),

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
        val DEFAULT = AUTO

        fun fromKey(key: String?): PresentationPath =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
