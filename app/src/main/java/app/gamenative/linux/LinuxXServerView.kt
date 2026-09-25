package app.gamenative.linux

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerView
import com.winlator.xserver.XServer
import timber.log.Timber

/**
 * Freeform presenter for a Linux [XServer]: Vulkan surface (games default) plus [TouchpadView].
 *
 * Touchscreen mode maps taps 1:1 into the X desktop — the relative touchpad path used for
 * Wine games misses Linux UI chrome.
 */
@SuppressLint("ViewConstructor")
class LinuxXServerView(
    context: Context,
    private val xServer: XServer,
) : FrameLayout(context) {

    private val xServerView = XServerView(context, xServer, "vulkan")
    private val touchpadView: TouchpadView

    init {
        // TouchpadView.updateXform requires xServer.renderer — set before constructing it
        // (same order as XServerScreen).
        xServer.renderer = xServerView.renderer
        touchpadView = TouchpadView(context, xServer, /* capturePointerOnExternalMouse = */ true).apply {
            setTouchscreenMode(true)
            setMoveCursorToTouchpoint(true)
        }

        addView(
            xServerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(
            touchpadView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        isFocusable = true
        isFocusableInTouchMode = true
        Timber.i(
            "[LinuxXServerView]: attached %dx%d (touchscreen)",
            xServer.screenInfo.width.toInt(),
            xServer.screenInfo.height.toInt(),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        xServerView.onResume()
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        xServerView.onPause()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (xServer.keyboard.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (touchpadView.onGenericMotionEvent(event)) return true
        return super.onGenericMotionEvent(event)
    }

    fun release() {
        runCatching { xServerView.onPause() }
    }
}
