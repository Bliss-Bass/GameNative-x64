package app.gamenative.linux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinuxVtConsolesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("linux_vt_consoles", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun consolesDefaultToUbuntuRootWithDefaultShell() {
        for (vt in LinuxVtConsoles.CONSOLES) {
            assertEquals(LinuxVtConsoles.Assignment(), LinuxVtConsoles.get(context, vt))
        }
    }

    @Test
    fun consolesAreIndependent() {
        LinuxVtConsoles.set(context, 4, LinuxVtConsoles.Assignment("ubuntu", "/usr/bin/zsh"))
        LinuxVtConsoles.set(context, 5, LinuxVtConsoles.Assignment("ubuntu", "/usr/bin/fish", "player"))
        LinuxVtConsoles.set(context, 6, null)

        assertEquals("/usr/bin/zsh", LinuxVtConsoles.get(context, 4)?.shell)
        assertEquals("player", LinuxVtConsoles.get(context, 5)?.user)
        assertNull(LinuxVtConsoles.get(context, 6))
    }

    @Test
    fun oneDistroCanTakeEveryConsole() {
        val debian = LinuxVtConsoles.Assignment("debian")
        LinuxVtConsoles.set(context, 5, null)
        LinuxVtConsoles.setAll(context, debian)
        for (vt in LinuxVtConsoles.CONSOLES) assertEquals(debian, LinuxVtConsoles.get(context, vt))
    }

    @Test(expected = IllegalArgumentException::class)
    fun androidConsolesAreNotGameNatives() {
        LinuxVtConsoles.set(context, 2, LinuxVtConsoles.Assignment())
    }

    @Test
    fun defaultShellIsBashWhenPresentElseSh() {
        val rootfs = Files.createTempDirectory("vt-rootfs").toFile()
        assertEquals("/bin/sh", LinuxVtConsoles.shellFor(rootfs, LinuxVtConsoles.Assignment()))

        File(rootfs, "usr/bin/bash").apply { parentFile?.mkdirs(); writeText("") }
        assertEquals("/usr/bin/bash", LinuxVtConsoles.shellFor(rootfs, LinuxVtConsoles.Assignment()))
        assertEquals("/bin/zsh", LinuxVtConsoles.shellFor(rootfs, LinuxVtConsoles.Assignment(shell = "/bin/zsh")))
    }

    @Test
    fun rootGetsALoginShellAndOthersLogInWithSu() {
        val root = LinuxVtConsoles.guestCommand(LinuxVtConsoles.Assignment()).last()
        assertTrue(root.endsWith("exec \"\$GN_SHELL\" -l"))

        val user = LinuxVtConsoles.guestCommand(LinuxVtConsoles.Assignment(user = "player")).last()
        assertTrue(user.contains("su -l -s \"\$GN_SHELL\" \"\$GN_USER\""))
    }
}
