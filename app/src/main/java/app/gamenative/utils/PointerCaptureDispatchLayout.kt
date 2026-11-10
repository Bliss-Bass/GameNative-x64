package app.gamenative.utils

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import app.gamenative.PluviaApp

/**
 * Sits above Compose in the Activity content tree so captured mouse events do not
 * depend on AndroidComposeView.mFocused pointing at TouchpadView.
 *
 * Pointer capture is window-level, but [android.view.ViewGroup.dispatchCapturedPointerEvent]
 * only walks the focused child. Tablets sit in touch mode, so that walk is empty until a
 * screen tap (or a successful [android.view.View.requestFocus] on a focusable-in-touch-mode
 * view). Compose also often leaves mFocused null for an AndroidView that called
 * [android.view.View.requestFocus], so TouchpadView's listener never runs even though
 * dumpsys shows SOURCE_MOUSE_RELATIVE events on the window.
 *
 * This layout is outside Compose, focusable in touch mode, and forwards captured events
 * to [PluviaApp.touchpadView] as soon as the DecorView focused-child walk reaches it.
 */
class PointerCaptureDispatchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setDefaultFocusHighlightEnabled(false)
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
    }

    // Captured events follow the focus chain. Opening a game removes the focused library
    // node, and the window is left with no focused view at all, so every captured event was
    // dropped until a dialog moved focus away and back.
    private val focusLossListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus == null && hasPointerCapture()) post { ensureCaptureFocus() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusLossListener)
        ensureCaptureFocus()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusLossListener)
        super.onDetachedFromWindow()
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        if (hasCapture) ensureCaptureFocus()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            ensureCaptureFocus()
        }
    }

    /**
     * Take focus without waiting for a touchscreen tap. Tablets start in touch mode,
     * so a non-touch-mode [View.requestFocus] on this layout is a no-op and captured
     * mouse motion is dropped until the user touches the display.
     */
    fun ensureCaptureFocus() {
        if (!isAttachedToWindow) return
        if (hasFocus()) return
        requestFocus()
    }

    override fun dispatchCapturedPointerEvent(event: MotionEvent): Boolean {
        val touchpad = PluviaApp.touchpadView
        if (touchpad != null && touchpad.onCapturedPointer(touchpad, event)) {
            return true
        }
        return super.dispatchCapturedPointerEvent(event)
    }
}
