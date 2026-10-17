package app.gamenative.linux

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class LinuxSchemeHandlerTest {

    @Test
    fun forwardArgv_ignoresBringingTheSameAppForward() {
        assertNull(LinuxSchemeHandler.forwardArgv("/usr/share/cursor/cursor", "/usr/share/cursor/cursor"))
        assertNull(LinuxSchemeHandler.forwardArgv("/usr/share/cursor/cursor", null))
    }

    @Test
    fun forwardArgv_keepsACallbackUrlOnTheRunningProgram() {
        assertEquals(
            "/usr/share/cursor/cursor cursor://anysphere.cursor-retrieval/login?code=abc&uuid=1",
            LinuxSchemeHandler.forwardArgv(
                "/usr/share/cursor/cursor",
                "/usr/share/cursor/cursor cursor://anysphere.cursor-retrieval/login?code=abc&uuid=1",
            ),
        )
    }

    @Test
    fun forwardArgv_prefixesABareUrlWithTheRunningProgram() {
        assertEquals(
            "/usr/share/cursor/cursor cursor://callback",
            LinuxSchemeHandler.forwardArgv("/usr/share/cursor/cursor", "cursor://callback"),
        )
    }

    @Test
    fun install_registersTheSchemesAndTheOpener() {
        val rootfs = createTempDirectory(prefix = "scheme-rootfs").toFile()
        File(rootfs, "root/.config").mkdirs()
        File(rootfs, "root/.config/mimeapps.list").writeText(
            "[Default Applications]\nx-scheme-handler/http=firefox.desktop\n",
        )

        LinuxSchemeHandler.install(rootfs)

        val opener = File(rootfs, "usr/local/bin/gamenative-scheme-open")
        assertTrue(opener.canExecute())
        assertTrue(opener.readText().contains("/usr/share/cursor/cursor --no-sandbox"))
        val desktop = File(rootfs, "usr/local/share/applications/gamenative-scheme-open.desktop")
        assertTrue(desktop.readText().contains("x-scheme-handler/cursor"))
        val mime = File(rootfs, "root/.config/mimeapps.list").readText()
        assertTrue(mime.contains("x-scheme-handler/http=firefox.desktop"))
        assertTrue(mime.contains("x-scheme-handler/cursor=gamenative-scheme-open.desktop"))
        assertTrue(mime.contains("x-scheme-handler/vscode=gamenative-scheme-open.desktop"))
    }
}
