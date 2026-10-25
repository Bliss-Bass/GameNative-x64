package app.gamenative.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinuxAppUninstallerTest {

    private val entry = "/usr/share/applications/htop.desktop"

    @Test
    fun aptNamesTheOwningPackage() {
        assertEquals("htop", LinuxAppUninstaller.parseOwner(LinuxPackageManager.APT, "htop: $entry\n", entry))
    }

    @Test
    fun aptTakesTheFirstOfSharedOwnersAndDropsTheArchitecture() {
        assertEquals(
            "libfoo1",
            LinuxAppUninstaller.parseOwner(LinuxPackageManager.APT, "libfoo1:amd64, libfoo-common: $entry\n", entry),
        )
    }

    @Test
    fun aptIgnoresDiversionsAndMisses() {
        val diverted = "diversion by gamenative from: $entry\ndiversion by gamenative to: $entry.real\n"
        assertNull(LinuxAppUninstaller.parseOwner(LinuxPackageManager.APT, diverted, entry))
        assertNull(
            LinuxAppUninstaller.parseOwner(
                LinuxPackageManager.APT,
                "dpkg-query: no path found matching pattern $entry\n",
                entry,
            ),
        )
    }

    @Test
    fun pacmanPrintsJustTheName() {
        assertEquals("htop", LinuxAppUninstaller.parseOwner(LinuxPackageManager.PACMAN, "htop\n", entry))
        assertNull(
            LinuxAppUninstaller.parseOwner(LinuxPackageManager.PACMAN, "error: No package owns $entry\n", entry),
        )
    }
}
