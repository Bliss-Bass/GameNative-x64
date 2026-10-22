package app.gamenative.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlatformCapabilitiesTest {

    @Test
    fun linuxVtNeedsPermissionPropertyAndConsole() {
        assertTrue(PlatformCapabilities.linuxVt(permissionGranted = true, buildProperty = "1", consoleUsable = true))
    }

    @Test
    fun linuxVtOffWithoutPermission() {
        assertFalse(PlatformCapabilities.linuxVt(permissionGranted = false, buildProperty = "1", consoleUsable = true))
    }

    @Test
    fun linuxVtOffWithoutBuildProperty() {
        assertFalse(PlatformCapabilities.linuxVt(permissionGranted = true, buildProperty = "", consoleUsable = true))
        assertFalse(PlatformCapabilities.linuxVt(permissionGranted = true, buildProperty = "0", consoleUsable = true))
    }

    @Test
    fun linuxVtOffWhenConsoleUnusable() {
        assertFalse(PlatformCapabilities.linuxVt(permissionGranted = true, buildProperty = "1", consoleUsable = false))
    }

    /** A plain device: no Bass permission, property, console access, installer or addon. */
    @Test
    fun stockDeviceHasNoBassCapabilities() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = PlatformCapabilities.refresh(context)
        assertFalse(snapshot.linuxVt)
        assertFalse(snapshot.stubInstaller)
        assertFalse(snapshot.linuxServices)
    }
}
