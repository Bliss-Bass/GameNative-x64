package app.gamenative.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUiScaleTest {

    @Test
    fun sanitize_snapsToNearestChoice() {
        assertEquals(100, AppUiScale.sanitize(100))
        assertEquals(100, AppUiScale.sanitize(104))
        assertEquals(110, AppUiScale.sanitize(108))
        assertEquals(75, AppUiScale.sanitize(70))
        assertEquals(200, AppUiScale.sanitize(250))
    }

    @Test
    fun factor_isPercentOverOneHundred() {
        assertEquals(1.0f, AppUiScale.factor(100), 0.001f)
        assertEquals(1.25f, AppUiScale.factor(125), 0.001f)
        assertEquals(0.75f, AppUiScale.factor(75), 0.001f)
    }
}
