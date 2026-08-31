package app.gamenative.linux

import android.content.Context
import app.gamenative.PrefManager
import java.io.File
import timber.log.Timber

/**
 * The two shapes the graphical session takes.
 *
 * Running one application and sitting at a desktop want opposite things from a window manager.
 * An application launched from Android is the session: it should fill the Android window it was
 * launched into, and a title bar drawn inside that window would be a second set of controls for
 * it. A desktop is the other way round -- windows are windows, they need decorations to be moved
 * and closed, and there has to be something to start them from.
 *
 * So there are two openbox configurations rather than one compromise, chosen when the session
 * starts, and a panel that only the desktop gets.
 */
object LinuxDesktopConfig {

    enum class Mode {
        /** One application, filling the display. */
        APP,

        /** A desktop the user drives: decorated windows, a panel and a menu. */
        DESKTOP,
    }

    private const val CONFIG_DIR = "root/.config/openbox"

    /** Guest paths, since these are handed to programs running inside the rootfs. */
    const val APP_RC = "/root/.config/openbox/rc-app.xml"
    const val DESKTOP_RC = "/root/.config/openbox/rc-desktop.xml"
    const val PANEL_RC = "/root/.config/tint2/tint2rc"

    private const val DESKTOP_MENU = "menu-desktop.xml"

    /** The desktop's background, matching the app's own dark surface. */
    const val BACKGROUND = "#1b1d26"

    /**
     * Writes both window manager configurations, from the one openbox ships.
     *
     * Amended rather than written from scratch: the stock rc.xml carries the keybindings, theme
     * and mouse bindings openbox needs to be usable at all, and a hand-written minimal one loses
     * every one of them.
     */
    fun writeWindowManagerConfigs(rootfs: File) {
        val stock = File(rootfs, "etc/xdg/openbox/rc.xml")
        if (!stock.isFile) {
            Timber.w("[LinuxDesktopConfig]: no stock openbox rc.xml; leaving defaults")
            return
        }

        val text = stock.readText()
        val dir = File(rootfs, CONFIG_DIR).apply { mkdirs() }

        // Maximized so a window follows the desktop when Android resizes it, undecorated because
        // Android already drew the frame around it.
        val filling = """
            <applications>
              <application class="*">
                <maximized>yes</maximized>
                <decor>no</decor>
              </application>
        """.trimIndent() + "\n"

        if (!text.contains("<applications>")) {
            Timber.w("[LinuxDesktopConfig]: openbox rc.xml has no <applications> section")
        }
        File(dir, "rc-app.xml").writeText(text.replaceFirst("<applications>", filling))

        // The desktop keeps openbox's own defaults and only changes where the root menu comes
        // from, which is the file we generate from what is installed.
        File(dir, "rc-desktop.xml").writeText(
            text.replaceFirst(Regex("""<file>[^<]*</file>"""), "<file>$DESKTOP_MENU</file>"),
        )

        // Left behind by earlier versions, which had a single configuration.
        File(dir, "rc.xml").delete()
    }

    /**
     * Writes the desktop's root menu and panel from [apps].
     *
     * Rewritten every time a desktop session starts rather than at install: apt runs in our own
     * terminal, so what is installed changes under us, and neither a menu nor a launcher is worth
     * having if it does not list what is actually there.
     */
    fun writeDesktop(context: Context, apps: List<LinuxAppScanner.LinuxApp>) {
        val rootfs = LinuxRootfs.rootfsDir(context)
        runCatching {
            writeMenu(rootfs, apps)
            writePanel(context, rootfs, apps)
        }.onFailure { Timber.w(it, "[LinuxDesktopConfig]: could not write the desktop configuration") }
    }

    /** The long-press menu: everything installed, a terminal, and a way to restart the session. */
    private fun writeMenu(rootfs: File, apps: List<LinuxAppScanner.LinuxApp>) {
        val entries = apps.joinToString("\n") { app ->
            """
            |    <item label="${escape(app.name)}">
            |      <action name="Execute"><command>${escape(app.launchArgv)}</command></action>
            |    </item>
            """.trimMargin()
        }

        File(rootfs, "$CONFIG_DIR/$DESKTOP_MENU").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<openbox_menu xmlns="http://openbox.org/3.4/menu">
            |  <menu id="root-menu" label="Applications">
            |    <item label="Terminal">
            |      <action name="Execute"><command>xterm</command></action>
            |    </item>
            |    <separator />
            |$entries
            |    <separator />
            |    <item label="Restart window manager">
            |      <action name="Restart" />
            |    </item>
            |  </menu>
            |</openbox_menu>
            |
            """.trimMargin(),
        )
    }

    /**
     * The panel: a launcher, the open windows and a clock.
     *
     * A panel rather than only the root menu because this is a touch screen. A right-click is a
     * long press away at best, and a menu that appears where the finger landed is not something
     * to build a desktop on; a row of targets along the bottom edge is.
     */
    private fun writePanel(context: Context, rootfs: File, apps: List<LinuxAppScanner.LinuxApp>) {
        // Sized in physical terms: a panel a finger can hit is about 9mm, which is this many
        // pixels on whatever panel the tablet has.
        //
        // Deliberately Android's real density and not the DPI the X server is given. That one is
        // rebased onto X's 96dpi convention so toolkits lay out at the right size, which makes it
        // the wrong number for asking "how many pixels is 9mm". The user's scale still applies:
        // someone who wants a larger UI wants a larger panel with it.
        val densityDpi = context.resources.displayMetrics.densityDpi
        val scale = PrefManager.linuxUiScalePercent / 100f
        val height = (densityDpi * 0.35f * scale).toInt().coerceIn(40, 160)
        val iconSize = height - (height / 4)

        // Capped, because the launcher is the shortcut to the handful of things someone reaches
        // for; the menu is where everything lives.
        val launchers = apps.take(LAUNCHER_LIMIT).joinToString("\n") {
            "launcher_item_app = ${it.desktopFile}"
        }

        File(rootfs, "root/.config/tint2").mkdirs()
        File(rootfs, "root/.config/tint2/tint2rc").writeText(
            """
            |# Written by GameNative. Changes are lost when the desktop next starts.
            |
            |# The backgrounds come first because tint2 resolves an id the moment it reads one,
            |# and a reference to a background it has not read yet takes the panel down with it.
            |
            |# Background 1: the panel
            |rounded = 0
            |border_width = 0
            |background_color = $BACKGROUND 100
            |border_color = #000000 0
            |
            |# Background 2: a task
            |rounded = 6
            |border_width = 0
            |background_color = #24242c 100
            |border_color = #000000 0
            |
            |# Background 3: the active task
            |rounded = 6
            |border_width = 0
            |background_color = #3a3a48 100
            |border_color = #000000 0
            |
            |panel_items = LTC
            |panel_size = 100% $height
            |panel_position = bottom center horizontal
            |panel_margin = 0 0
            |panel_padding = 4 0 4
            |panel_background_id = 1
            |panel_layer = bottom
            |wm_menu = 1
            |
            |launcher_padding = 4 0 6
            |launcher_icon_size = $iconSize
            |launcher_icon_theme_override = 0
            |launcher_tooltip = 0
            |$launchers
            |
            |taskbar_mode = single_desktop
            |taskbar_padding = 4 0 6
            |task_maximum_size = ${height * 5} $height
            |task_padding = 6 2 6
            |task_icon = 1
            |task_text = 1
            |task_font_color = #e6e6ea 100
            |task_background_id = 2
            |task_active_background_id = 3
            |
            |time1_format = %H:%M
            |time1_font = sans 10
            |clock_font_color = #e6e6ea 100
            |clock_padding = 8 0
            |
            """.trimMargin(),
        )
    }

    private const val LAUNCHER_LIMIT = 10

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
