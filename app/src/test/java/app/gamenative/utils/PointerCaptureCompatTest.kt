package app.gamenative.utils

import android.view.MotionEvent
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PointerCaptureCompatTest {
    @Test
    fun capturedDelta_prefersGetXGetY() {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 4.5f, -2f, 0)
        try {
            assertEquals(4.5f, PointerCaptureCompat.capturedDeltaX(event), 0.01f)
            assertEquals(-2f, PointerCaptureCompat.capturedDeltaY(event), 0.01f)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun dispatchCapturedPointer_returnsFalseWhenNoTouchpad() {
        val layout = PointerCaptureDispatchLayout(RuntimeEnvironment.getApplication())
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 4f, -2f, 0)
        try {
            assertFalse(layout.dispatchCapturedPointerEvent(event))
        } finally {
            event.recycle()
        }
    }

    @Test
    fun captureRoot_isFocusableInTouchMode() {
        val layout = PointerCaptureDispatchLayout(RuntimeEnvironment.getApplication())
        assertTrue(layout.isFocusable)
        assertTrue(layout.isFocusableInTouchMode)
        assertEquals(FrameLayout.FOCUS_BEFORE_DESCENDANTS, layout.descendantFocusability)
    }
}
