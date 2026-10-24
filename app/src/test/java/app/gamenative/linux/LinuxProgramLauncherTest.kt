package app.gamenative.linux

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinuxProgramLauncherTest {

    @Test
    fun argvForRootGuest_addsNoSandbox_forCursor() {
        assertEquals(
            "/usr/share/cursor/cursor --no-sandbox",
            LinuxProgramLauncher.argvForRootGuest("/usr/share/cursor/cursor"),
        )
    }

    @Test
    fun argvForRootGuest_leavesAnExistingFlag() {
        assertEquals(
            "/usr/share/cursor/cursor --no-sandbox",
            LinuxProgramLauncher.argvForRootGuest("/usr/share/cursor/cursor --no-sandbox"),
        )
    }

    @Test
    fun argvForRootGuest_addsNoSandbox_forChromiumAndChrome() {
        assertEquals("/usr/bin/chromium --no-sandbox", LinuxProgramLauncher.argvForRootGuest("/usr/bin/chromium"))
        assertEquals(
            "/usr/bin/google-chrome-stable --no-sandbox",
            LinuxProgramLauncher.argvForRootGuest("/usr/bin/google-chrome-stable"),
        )
    }

    @Test
    fun argvForRootGuest_leavesOtherPrograms() {
        assertEquals("firefox", LinuxProgramLauncher.argvForRootGuest("firefox"))
    }
}
