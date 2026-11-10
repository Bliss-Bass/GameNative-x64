package app.gamenative.utils

import app.gamenative.enums.PresentationPath
import org.junit.Assert.assertEquals
import org.junit.Test

class PresentationFallbackTest {
    @Test
    fun automatic_isDri3WithoutAFallback() {
        assertEquals(PresentationPath.DRI3, PresentationFallback.resolve(PresentationPath.AUTO, null, 170))
        assertEquals(PresentationPath.DRI3, PresentationFallback.resolve(PresentationPath.AUTO, "", 170))
    }

    @Test
    fun automatic_isSoftwareAfterAFallbackFromThisVersion() {
        assertEquals(PresentationPath.SOFTWARE, PresentationFallback.resolve(PresentationPath.AUTO, "software@170", 170))
    }

    @Test
    fun automatic_retriesDri3AfterAnUpdate() {
        assertEquals(PresentationPath.DRI3, PresentationFallback.resolve(PresentationPath.AUTO, "software@169", 170))
    }

    @Test
    fun explicitChoice_ignoresTheFallback() {
        for (path in listOf(PresentationPath.SOFTWARE, PresentationPath.SHM, PresentationPath.DRI3)) {
            assertEquals(path, PresentationFallback.resolve(path, "software@170", 170))
        }
    }

    @Test
    fun default_isAutomatic() {
        assertEquals(PresentationPath.AUTO, PresentationPath.DEFAULT)
        assertEquals(PresentationPath.AUTO, PresentationPath.fromKey("bogus"))
    }
}
