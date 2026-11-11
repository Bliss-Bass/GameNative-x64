package app.gamenative.ui.screen.xserver

import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer
import com.winlator.widget.XServerRendererView
import com.winlator.widget.XServerView
import com.winlator.xserver.extensions.PresentExtension

/**
 * Applies a container's Frame presentation mode to the view, the Present extension and the
 * renderer. Kept out of XServerScreen, which upstream also changes.
 */
internal fun applyFramePresentModeToEngines(xServerView: XServerRendererView?, mode: String) {
    val lean = mode == Container.FRAME_PRESENT_LEAN
    val balanced = lean || mode == Container.FRAME_PRESENT_BALANCED
    val hold = balanced || mode == Container.FRAME_PRESENT_QUALITY
    (xServerView as? XServerView)?.setNativeResolutionSurface(lean)
    xServerView?.getxServer()
        ?.getExtension<PresentExtension>(PresentExtension.MAJOR_OPCODE.toInt())
        ?.setHoldFlips(hold, balanced)
    (xServerView?.renderer as? VulkanRenderer)?.setDmaBufWriterWait(
        when {
            balanced -> VulkanRenderer.DMABUF_WAIT_GPU
            hold -> VulkanRenderer.DMABUF_WAIT_CPU
            else -> VulkanRenderer.DMABUF_WAIT_NONE
        },
    )
}
