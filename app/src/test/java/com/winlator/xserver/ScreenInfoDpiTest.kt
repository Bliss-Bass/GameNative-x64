package com.winlator.xserver

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenInfoDpiTest {
    @Test
    fun legacyConstructor_keepsHistoricalMillimeters() {
        val info = ScreenInfo(1550, 970)
        assertEquals(155, info.widthInMillimeters.toInt())
        assertEquals(97, info.heightInMillimeters.toInt())
        assertEquals(0, info.dpi)
    }

    @Test
    fun matchedDpi_setsMillimetersFromLinuxDisplayScale() {
        // 1555px at 131dpi → mm = round(1555 * 25.4 / 131) = 302
        val info = ScreenInfo(1555, 970, 131)
        assertEquals(131, info.dpi)
        assertEquals(302, info.widthInMillimeters.toInt())
        assertEquals(188, info.heightInMillimeters.toInt())
    }
}
