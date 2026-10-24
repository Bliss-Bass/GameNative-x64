package app.gamenative.linux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinuxAppIdsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("linux_distros", Context.MODE_PRIVATE).edit().clear().commit()
        LinuxRootfs.rootDir(context).deleteRecursively()
    }

    @Test
    fun ubuntuIdsStayPlainSoExistingStubsAndShortcutsKeepWorking() {
        assertEquals("xterm.desktop", LinuxAppIds.qualify(LinuxDistroCatalog.UBUNTU, "xterm.desktop"))
        assertEquals(LinuxDistroCatalog.UBUNTU, LinuxAppIds.distroOf("xterm.desktop"))
        assertEquals("xterm.desktop", LinuxAppIds.entryOf("xterm.desktop"))
        assertEquals("app.gamenative.stub.xterm_desktop", LinuxAppIds.stubPackage("xterm.desktop"))
    }

    @Test
    fun otherDistrosAreQualifiedAndRoundTrip() {
        val id = LinuxAppIds.qualify("debian", "xterm.desktop")
        assertEquals("debian:xterm.desktop", id)
        assertEquals("debian", LinuxAppIds.distroOf(id))
        assertEquals("xterm.desktop", LinuxAppIds.entryOf(id))
    }

    @Test
    fun unknownPrefixesAndSessionKeysAreUbuntu() {
        assertEquals(LinuxDistroCatalog.UBUNTU, LinuxAppIds.distroOf(null))
        assertEquals(LinuxDistroCatalog.UBUNTU, LinuxAppIds.distroOf("argv:12345"))
        assertEquals(LinuxDistroCatalog.UBUNTU, LinuxAppIds.distroOf(LinuxSessions.DESKTOP_KEY))
        assertEquals("argv:12345", LinuxAppIds.entryOf("argv:12345"))
    }

    @Test
    fun stubPackagesDoNotCollideAcrossDistros() {
        val debian = LinuxAppIds.stubPackage("debian:foo.desktop")
        val ubuntu = LinuxAppIds.stubPackage("debian-foo.desktop")
        assertEquals("app.gamenative.stub.debian.foo_desktop", debian)
        assertNotEquals(ubuntu, debian)
        assertNotEquals(LinuxAppIds.stubPackage("archlinux:foo.desktop"), debian)
    }

    @Test
    fun scanCoversEveryIntegratedDistroAndTagsEachApp() {
        entry(LinuxRootfs.rootfsDir(context), "xterm.desktop", "XTerm")
        entry(fakeInstall("debian"), "xterm.desktop", "XTerm")
        entry(fakeInstall("archlinux"), "gimp.desktop", "GIMP")
        // Experimental: installable and usable from the terminal, never an app source.
        entry(fakeInstall("alpine"), "foot.desktop", "Foot")

        val apps = LinuxAppScanner.scan(context)

        assertEquals(
            setOf("xterm", "debian:xterm", "archlinux:gimp"),
            apps.map { it.entryId }.toSet(),
        )
        assertEquals("debian", apps.single { it.entryId == "debian:xterm" }.distroId)
        assertEquals(LinuxDistroCatalog.UBUNTU, apps.single { it.entryId == "xterm" }.distroId)
        assertEquals(apps.size, apps.map { it.id }.distinct().size)
        assertTrue(LinuxAppScanner.scan(context, "alpine").isEmpty())
    }

    @Test
    fun graphicsReadinessIsJudgedByWhatLanded() {
        val rootfs = fakeInstall("archlinux")
        assertFalse(LinuxGraphics.isReady(context, "archlinux"))
        for (path in listOf("usr/bin/xterm", "usr/bin/xsetroot", "usr/bin/xsettingsd", "usr/lib/libpulse.so.0")) {
            File(rootfs, path).apply { parentFile?.mkdirs() }.writeText("")
        }
        assertTrue(LinuxGraphics.isReady(context, "archlinux"))
        assertFalse(LinuxGraphics.isReady(context, "debian"))
    }

    private fun entry(rootfs: File, file: String, name: String) {
        File(rootfs, "usr/bin/${file.substringBefore('.')}").apply { parentFile?.mkdirs() }.writeText("#!/bin/sh\n")
        File(rootfs, "usr/share/applications/$file").apply { parentFile?.mkdirs() }.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=$name
            Exec=/usr/bin/${file.substringBefore('.')}
            """.trimIndent(),
        )
    }

    private fun fakeInstall(id: String): File {
        val rootfs = LinuxDistros.rootfsDir(context, id)
        File(rootfs, "etc").mkdirs()
        File(LinuxDistros.distrosDir(context), "$id/.installed").writeText(LinuxDistroCatalog.byId(id)!!.sha256!!)
        return rootfs
    }
}
