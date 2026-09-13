package app.gamenative.utils

import android.view.MotionEvent
import android.view.View

/**
 * Android 16 added pointer-capture modes. The no-arg [View.requestPointerCapture]
 * now requests ABSOLUTE capture (touchpad finger positions). Mice still hide the
 * system cursor, but their motion is reported on [MotionEvent.getX]/[MotionEvent.getY]
 * as SOURCE_MOUSE_RELATIVE. AXIS_RELATIVE_* is often zero, so a listener that only
 * reads those axes looks like a dead mouse.
 */
object PointerCaptureCompat {
    @JvmStatic
    fun requestRelativeCapture(view: View) {
        try {
            val relative = View::class.java.getField("POINTER_CAPTURE_MODE_RELATIVE").getInt(null)
            View::class.java.getMethod("requestPointerCapture", Int::class.javaPrimitiveType)
                .invoke(view, relative)
            return
        } catch (_: Throwable) {
            // Pre-16, or the capture-modes flag is off: the no-arg call is relative.
        }
        view.requestPointerCapture()
    }

    /** One sample of captured pointer motion, including historical batches. */
    @JvmStatic
    fun capturedDeltaX(event: MotionEvent): Float = capturedDelta(event, xAxis = true)

    @JvmStatic
    fun capturedDeltaY(event: MotionEvent): Float = capturedDelta(event, xAxis = false)

    private fun capturedDelta(event: MotionEvent, xAxis: Boolean): Float {
        var total = 0f
        val historySize = event.historySize
        for (i in 0 until historySize) {
            total += sampleDelta(event, i, historical = true, xAxis = xAxis)
        }
        total += sampleDelta(event, 0, historical = false, xAxis = xAxis)
        return total
    }

    private fun sampleDelta(event: MotionEvent, index: Int, historical: Boolean, xAxis: Boolean): Float {
        val documented = if (historical) {
            if (xAxis) event.getHistoricalX(index) else event.getHistoricalY(index)
        } else {
            if (xAxis) event.x else event.y
        }
        val relativeAxis = if (xAxis) MotionEvent.AXIS_RELATIVE_X else MotionEvent.AXIS_RELATIVE_Y
        val relative = if (historical) {
            event.getHistoricalAxisValue(relativeAxis, index)
        } else {
            event.getAxisValue(relativeAxis)
        }
        // Prefer getX/getY (API 36 captured-mouse contract). Fall back when both
        // documented axes in this sample are zero so older AXIS_RELATIVE devices still work.
        val otherDocumented = if (historical) {
            if (xAxis) event.getHistoricalY(index) else event.getHistoricalX(index)
        } else {
            if (xAxis) event.y else event.x
        }
        return if (documented != 0f || otherDocumented != 0f) documented else relative
    }
}
