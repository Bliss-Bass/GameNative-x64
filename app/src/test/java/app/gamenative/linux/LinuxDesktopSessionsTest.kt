package app.gamenative.linux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinuxDesktopSessionsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        for (prefs in listOf("linux_desktop_sessions", "linux_distros")) {
            context.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().clear().commit()
        }
        LinuxRootfs.rootDir(context).deleteRecursively()
    }

    @Test
    fun everySessionHasPackagesForEveryPackageManager() {
        for (session in LinuxDesktopSessions.Session.entries) {
            for (pm in LinuxPackageManager.entries) {
                assertTrue("$session on $pm", LinuxDesktopSessions.packages(session, pm).isNotEmpty())
            }
        }
    }

    @Test
    fun desktopFallsBackToUbuntuOpenbox() {
        assertEquals(
            LinuxDesktopSessions.Target(LinuxDistroCatalog.UBUNTU, LinuxDesktopSessions.Session.OPENBOX),
            LinuxDesktopSessions.target(context),
        )
        assertTrue(LinuxDesktopSessions.target(context).isBuiltIn)

        // Chosen but not installed: still the default.
        LinuxDesktopSessions.setDesktopDistro(context, "debian")
        LinuxDesktopSessions.select(context, "debian", LinuxDesktopSessions.Session.KDE)
        assertTrue(LinuxDesktopSessions.target(context).isBuiltIn)
    }

    @Test
    fun installedSessionInAnotherDistroIsTheTarget() {
        val rootfs = fakeInstall("debian")
        for (path in listOf("usr/bin/startxfce4", "usr/bin/Xtigervnc")) {
            File(rootfs, path).apply { parentFile?.mkdirs() }.writeText("")
        }
        LinuxDesktopSessions.setDesktopDistro(context, "debian")
        LinuxDesktopSessions.select(context, "debian", LinuxDesktopSessions.Session.XFCE)

        val target = LinuxDesktopSessions.target(context)
        assertEquals(LinuxDesktopSessions.Target("debian", LinuxDesktopSessions.Session.XFCE), target)
        assertFalse(target.isBuiltIn)
    }

    @Test
    fun aSessionWithoutAnXServerIsNotInstalled() {
        val rootfs = fakeInstall("alpine")
        File(rootfs, "usr/bin/startlxqt").apply { parentFile?.mkdirs() }.writeText("")
        assertFalse(LinuxDesktopSessions.isInstalled(context, "alpine", LinuxDesktopSessions.Session.LXQT))
        File(rootfs, "usr/bin/Xvnc").writeText("")
        assertTrue(LinuxDesktopSessions.isInstalled(context, "alpine", LinuxDesktopSessions.Session.LXQT))
    }

    @Test
    fun prepareTurnsOffSystemdAndCompositingWithoutOverridingTheUser() {
        val rootfs = Files.createTempDirectory("rootfs").toFile()
        LinuxDesktopSessions.prepare(rootfs, LinuxDesktopSessions.Session.KDE)
        assertEquals("[General]\nsystemdBoot=false\n", File(rootfs, "root/.config/startkderc").readText())
        assertTrue(File(rootfs, "root/.config/kwinrc").readText().contains("Enabled=false"))

        File(rootfs, "root/.config/kwinrc").writeText("[Compositing]\nEnabled=true\n")
        LinuxDesktopSessions.prepare(rootfs, LinuxDesktopSessions.Session.KDE)
        assertTrue(File(rootfs, "root/.config/kwinrc").readText().contains("Enabled=true"))

        LinuxDesktopSessions.prepare(rootfs, LinuxDesktopSessions.Session.LXQT)
        assertTrue(File(rootfs, "root/.config/lxqt/session.conf").readText().contains("window_manager=openbox"))
    }

    @Test
    fun launchRunsUnderASessionBus() {
        val (command, env) = LinuxDesktopSessions.launch(LinuxDesktopSessions.Session.KDE)
        assertEquals("/usr/bin/dbus-launch --exit-with-session /usr/bin/startplasma-x11", command)
        assertEquals("N", env["KWIN_COMPOSE"])
    }

    @Test
    fun shellsComeFromEtcShellsWithBashFirst() {
        val rootfs = Files.createTempDirectory("rootfs").toFile()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/shells").writeText("# comment\n/bin/sh\n/bin/zsh\n/usr/bin/zsh\n/bin/bash\n/usr/sbin/nologin\n")
        for (shell in listOf("bin/sh", "bin/zsh", "usr/bin/zsh", "bin/bash")) {
            File(rootfs, shell).apply { parentFile?.mkdirs() }.writeText("")
        }
        assertEquals(listOf("/bin/bash", "/bin/sh", "/bin/zsh"), LinuxDistros.shells(rootfs))
    }

    @Test
    fun consoleUsersAreRootAndOrdinaryAccounts() {
        val rootfs = Files.createTempDirectory("rootfs").toFile()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText(
            "root:x:0:0:root:/root:/bin/bash\n" +
                "daemon:x:1:1::/:/usr/sbin/nologin\n" +
                "player:x:1000:1000::/home/player:/bin/bash\n" +
                "nobody:x:65534:65534::/:/usr/sbin/nologin\n",
        )
        assertEquals(listOf("root", "player"), LinuxVtConsoles.users(rootfs))
    }

    private fun fakeInstall(id: String): File {
        val rootfs = LinuxDistros.rootfsDir(context, id)
        File(rootfs, "etc").mkdirs()
        File(LinuxDistros.distrosDir(context), "$id/.installed").writeText(LinuxDistroCatalog.byId(id)!!.sha256!!)
        return rootfs
    }
}
