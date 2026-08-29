package app.gamenative.linux

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinuxAppScannerTest {

    private lateinit var context: Context
    private lateinit var rootfs: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rootfs = LinuxRootfs.rootfsDir(context)
        rootfs.deleteRecursively()
        rootfs.mkdirs()
    }

    private fun desktopEntry(name: String, contents: String, dir: String = "usr/share/applications") {
        val target = File(rootfs, "$dir/$name").apply { parentFile?.mkdirs() }
        target.writeText(contents.trimIndent())
    }

    private fun binary(path: String) {
        File(rootfs, path).apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
    }

    private fun icon(path: String) {
        File(rootfs, path).apply {
            parentFile?.mkdirs()
            writeText("png")
        }
    }

    @Test
    fun `reports an application with its command and icon`() {
        icon("usr/share/icons/hicolor/128x128/apps/gedit.png")
        desktopEntry(
            "gedit.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Text Editor
            Comment=Edit text files
            Exec=gedit %U
            Icon=gedit
            Categories=GTK;Utility;TextEditor;
            """,
        )

        val app = LinuxAppScanner.scan(context).single()

        assertEquals("Text Editor", app.name)
        assertEquals("gedit", app.exec)
        assertEquals("Edit text files", app.comment)
        assertEquals("/usr/share/icons/hicolor/128x128/apps/gedit.png", app.iconPath)
        assertEquals(listOf("GTK", "Utility", "TextEditor"), app.categories)
    }

    @Test
    fun `strips every field code from the command`() {
        desktopEntry(
            "viewer.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Viewer
            Exec=viewer --open %F --icon %i %c
            """,
        )

        assertEquals("viewer --open --icon", LinuxAppScanner.scan(context).single().exec)
    }

    @Test
    fun `ignores entries that ask not to be shown`() {
        desktopEntry(
            "hidden.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Hidden
            Exec=hidden
            NoDisplay=true
            """,
        )
        desktopEntry(
            "removed.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Removed
            Exec=removed
            Hidden=True
            """,
        )
        desktopEntry(
            "link.desktop",
            """
            [Desktop Entry]
            Type=Link
            Name=A link
            URL=https://example.com
            """,
        )

        assertTrue(LinuxAppScanner.scan(context).isEmpty())
    }

    @Test
    fun `ignores an entry whose program is gone`() {
        desktopEntry(
            "stale.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Stale
            Exec=stale
            TryExec=stale
            """,
        )
        assertTrue(LinuxAppScanner.scan(context).isEmpty())

        binary("usr/bin/stale")
        assertEquals("Stale", LinuxAppScanner.scan(context).single().name)
    }

    @Test
    fun `does not let an action group override the entry`() {
        desktopEntry(
            "browser.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Browser
            Exec=browser

            [Desktop Action new-window]
            Name=New Window
            Exec=browser --new-window
            """,
        )

        val app = LinuxAppScanner.scan(context).single()
        assertEquals("Browser", app.name)
        assertEquals("browser", app.exec)
    }

    @Test
    fun `wraps a console program in a terminal`() {
        desktopEntry(
            "htop.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=htop
            Exec=htop
            Terminal=true
            """,
        )

        assertEquals("xterm -e htop", LinuxAppScanner.scan(context).single().launchArgv)
    }

    @Test
    fun `prefers a user entry over the packaged one`() {
        desktopEntry(
            "editor.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Packaged
            Exec=editor
            """,
        )
        desktopEntry(
            "editor.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Overridden
            Exec=editor --custom
            """,
            dir = "root/.local/share/applications",
        )

        val app = LinuxAppScanner.scan(context).single()
        assertEquals("Overridden", app.name)
        assertEquals("editor --custom", app.exec)
    }

    @Test
    fun `gives an entry the same id across scans and finds it by that id`() {
        desktopEntry(
            "stable.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Stable
            Exec=stable
            """,
        )

        val first = LinuxAppScanner.scan(context).single()
        val second = LinuxAppScanner.scan(context).single()

        assertEquals(first.id, second.id)
        assertTrue(first.id > 0)
        assertEquals("Stable", LinuxAppScanner.find(context, first.id)?.name)
    }

    @Test
    fun `takes an absolute icon path only when the file exists`() {
        desktopEntry(
            "absolute.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=Absolute
            Exec=absolute
            Icon=/opt/absolute/logo.png
            """,
        )
        assertNull(LinuxAppScanner.scan(context).single().iconPath)

        icon("opt/absolute/logo.png")
        assertEquals("/opt/absolute/logo.png", LinuxAppScanner.scan(context).single().iconPath)
    }

    @Test
    fun `falls back to the largest size-suffixed pixmap`() {
        // What xterm actually ships: an Icon key with no file of that exact name.
        for (size in listOf(16, 48, 32)) {
            icon("usr/share/pixmaps/mini.xterm_${size}x$size.xpm")
        }
        desktopEntry(
            "xterm.desktop",
            """
            [Desktop Entry]
            Type=Application
            Name=XTerm
            Exec=xterm
            Icon=mini.xterm
            """,
        )

        assertEquals(
            "/usr/share/pixmaps/mini.xterm_48x48.xpm",
            LinuxAppScanner.scan(context).single().iconPath,
        )
    }

    @Test
    fun `sorts by name regardless of case`() {
        for (name in listOf("beta", "Alpha", "gamma")) {
            desktopEntry(
                "$name.desktop",
                """
                [Desktop Entry]
                Type=Application
                Name=$name
                Exec=$name
                """,
            )
        }

        assertEquals(
            listOf("Alpha", "beta", "gamma"),
            LinuxAppScanner.scan(context).map { it.name },
        )
    }
}
