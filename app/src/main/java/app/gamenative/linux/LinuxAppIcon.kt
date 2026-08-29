package app.gamenative.linux

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Loads the icon a Linux desktop entry points at.
 *
 * Only PNG is decoded. Icon themes also use SVG and pixmaps still use XPM, neither of which
 * Android reads, and pulling in a renderer for a list icon is not worth it -- callers fall
 * back to a generic icon instead.
 */
object LinuxAppIcon {

    /** The icon at [iconPath] (a guest-absolute path), or null if it cannot be read. */
    fun load(context: Context, iconPath: String?): Bitmap? {
        val png = iconPath?.takeIf { it.endsWith(".png", ignoreCase = true) } ?: return null
        val file = File(LinuxRootfs.rootfsDir(context), png.removePrefix("/"))
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }
}
