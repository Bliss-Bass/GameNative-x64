package app.gamenative.utils

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
