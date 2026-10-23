package app.gamenative.linux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinuxDistrosTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("linux_distros", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("linux_vt_consoles", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("linux_desktop_sessions", Context.MODE_PRIVATE).edit().clear().commit()
        LinuxRootfs.rootDir(context).deleteRecursively()
    }

    @Test
    fun catalogEntriesAreComplete() {
        val ids = LinuxDistroCatalog.all.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals(LinuxDistroCatalog.UBUNTU, ids.first())
        for (distro in LinuxDistroCatalog.all.filter { it.id != LinuxDistroCatalog.UBUNTU }) {
            assertTrue(distro.id, distro.tarballUrl!!.startsWith("https://"))
            assertTrue(distro.id, distro.sha256!!.matches(Regex("[0-9a-f]{64}")))
            assertFalse("only Ubuntu carries the graphical session", distro.graphical)
        }
        assertTrue(LinuxDistroCatalog.byId(LinuxDistroCatalog.UBUNTU)!!.graphical)
    }

    @Test
    fun eachDistroHasItsOwnRootfs() {
        assertEquals(
            File(context.filesDir, "linux/distros/ubuntu/rootfs"),
            LinuxRootfs.rootfsDir(context),
        )
        assertEquals(
            File(context.filesDir, "linux/distros/alpine/rootfs"),
            LinuxDistros.rootfsDir(context, "alpine"),
        )
    }

    @Test
    fun legacyUbuntuRootfsMovesOnce() {
        val legacy = File(LinuxRootfs.rootDir(context), "rootfs/etc").apply { mkdirs() }
        File(legacy, "os-release").writeText("ID=ubuntu\n")

        LinuxDistros.migrateLegacyLayout(context)
        LinuxDistros.migrateLegacyLayout(context)

        assertFalse(File(LinuxRootfs.rootDir(context), "rootfs").exists())
        assertEquals("ID=ubuntu\n", File(LinuxRootfs.rootfsDir(context), "etc/os-release").readText())
    }

    @Test
    fun migrationKeepsAnExistingNewLayout() {
        File(LinuxRootfs.rootDir(context), "rootfs/etc").mkdirs()
        File(LinuxRootfs.rootfsDir(context), "etc").mkdirs()

        LinuxDistros.migrateLegacyLayout(context)

        assertTrue(File(LinuxRootfs.rootDir(context), "rootfs").exists())
        assertTrue(LinuxRootfs.rootfsDir(context).exists())
    }

    @Test
    fun resolveStripsLeadingComponentsAndRejectsEscapes() {
        val rootfs = File("/r")
        assertEquals(File("/r/etc/hosts"), RootfsArchive.resolve(rootfs, "alpine-x86_64/etc/hosts", 1))
        assertEquals(File("/r/etc/hosts"), RootfsArchive.resolve(rootfs, "./etc/hosts"))
        assertNull(RootfsArchive.resolve(rootfs, "alpine-x86_64/", 1))
        assertNull(RootfsArchive.resolve(rootfs, "alpine-x86_64/../../etc/passwd", 1))
    }

    @Test
    fun extractsXzTarballsWithStrip() {
        val tarball = Files.createTempFile("distro", ".tar.xz").toFile()
        TarArchiveOutputStream(XZCompressorOutputStream(tarball.outputStream())).use { tar ->
            val body = "root:x:0:\n".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("alpine-x86_64/etc/").apply { mode = 0x41ed })
            tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry("alpine-x86_64/etc/group").apply { size = body.size.toLong(); mode = 0x8000 })
            tar.write(body)
            tar.closeArchiveEntry()
        }
        val rootfs = Files.createTempDirectory("rootfs").toFile()

        RootfsArchive.extract(tarball, rootfs, stripComponents = 1, ownerAccess = true)

        assertEquals("root:x:0:\n", File(rootfs, "etc/group").readText())
        assertFalse(File(rootfs, "alpine-x86_64").exists())
    }

    @Test
    fun defaultFallsBackToUbuntuUnlessInstalled() {
        assertEquals(LinuxDistroCatalog.UBUNTU, LinuxDistros.defaultId(context))
        LinuxDistros.setDefault(context, "alpine")
        assertEquals(LinuxDistroCatalog.UBUNTU, LinuxDistros.defaultId(context))

        fakeInstall("alpine")
        assertEquals("alpine", LinuxDistros.defaultId(context))
        assertNotNull(LinuxDistros.installedRootfs(context, "alpine"))
    }

    @Test
    fun uninstallMovesConsolesBackToTheDefault() {
        fakeInstall("alpine")
        LinuxDistros.setDefault(context, "alpine")
        LinuxVtConsoles.set(context, 5, LinuxVtConsoles.Assignment("alpine", "/bin/ash"))

        runBlocking { LinuxDistros.uninstall(context, "alpine") }

        assertFalse(LinuxDistros.isInstalled(context, "alpine"))
        assertFalse(File(LinuxDistros.distrosDir(context), "alpine").exists())
        assertEquals(LinuxDistroCatalog.UBUNTU, LinuxDistros.defaultId(context))
        assertEquals(LinuxVtConsoles.Assignment(), LinuxVtConsoles.get(context, 5))
    }

    @Test
    fun unassignedConsolesRunTheFirstInstalledDistro() {
        fakeInstall("alpine")
        fakeInstall("fedora")
        assertEquals("alpine", LinuxDistros.defaultId(context))
        assertEquals("alpine", LinuxVtConsoles.get(context, 4)?.distroId)

        // The user's default wins over catalog order, and an explicit pick wins over both.
        LinuxDistros.setDefault(context, "fedora")
        LinuxVtConsoles.set(context, 6, LinuxVtConsoles.Assignment("alpine"))
        assertEquals("fedora", LinuxVtConsoles.get(context, 4)?.distroId)
        assertEquals("fedora", LinuxVtConsoles.get(context, 5)?.distroId)
        assertEquals("alpine", LinuxVtConsoles.get(context, 6)?.distroId)
        assertNull(LinuxVtConsoles.assignedDistro(context, 4))
    }

    @Test
    fun uninstallLeavesItsConsolesFollowingTheDefault() {
        fakeInstall("alpine")
        fakeInstall("fedora")
        LinuxVtConsoles.set(context, 4, LinuxVtConsoles.Assignment("fedora", "/bin/bash"))

        runBlocking { LinuxDistros.uninstall(context, "fedora") }

        assertNull(LinuxVtConsoles.assignedDistro(context, 4))
        assertEquals(LinuxVtConsoles.Assignment("alpine"), LinuxVtConsoles.get(context, 4))
    }

    @Test
    fun uninstallForgetsTheDesktopChoice() {
        fakeInstall("alpine")
        LinuxDesktopSessions.setDesktopDistro(context, "alpine")
        LinuxDesktopSessions.select(context, "alpine", LinuxDesktopSessions.Session.XFCE)

        runBlocking { LinuxDistros.uninstall(context, "alpine") }
        fakeInstall("alpine")

        assertEquals(LinuxDistroCatalog.UBUNTU, LinuxDesktopSessions.desktopDistro(context))
        assertEquals(LinuxDesktopSessions.Session.OPENBOX, LinuxDesktopSessions.selected(context, "alpine"))
    }

    private fun fakeInstall(id: String) {
        File(LinuxDistros.rootfsDir(context, id), "etc").mkdirs()
        File(LinuxDistros.distrosDir(context), "$id/.installed").writeText(LinuxDistroCatalog.byId(id)!!.sha256!!)
    }
}
