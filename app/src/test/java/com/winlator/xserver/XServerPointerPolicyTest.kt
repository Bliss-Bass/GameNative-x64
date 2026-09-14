package com.winlator.xserver

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class XServerPointerPolicyTest {
    @Test
    fun lockedCursorClip_matchesUnityOnePixelLock() {
        assertTrue(XServer.isLockedCursorClip(Rect(640, 360, 641, 361)))
        assertTrue(XServer.isLockedCursorClip(Rect(0, 0, 16, 16)))
        assertFalse(XServer.isLockedCursorClip(Rect(0, 0, 1280, 720)))
        assertFalse(XServer.isLockedCursorClip(null))
    }

    @Test
    fun sourceEngineWarpsStayEnabledWithoutTinyClip() {
        val xServer = XServer(ScreenInfo(1280, 720), false)
        assertFalse(xServer.shouldIgnoreGuestPointerWarp())
        xServer.isRelativeMouseMovement = true
        assertTrue(xServer.shouldIgnoreGuestPointerWarp())
    }

    @Test
    fun injectPointerMoveDelta_movesFromScreenCenter() {
        val xServer = XServer(ScreenInfo(1280, 720), false)
        xServer.pointer.setPosition(640, 360)
        xServer.injectPointerMoveDelta(40, -20)
        assertEquals(680, xServer.pointer.x.toInt())
        assertEquals(340, xServer.pointer.y.toInt())
    }
}
