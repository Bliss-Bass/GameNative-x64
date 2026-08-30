package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import app.gamenative.MainActivity
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.linux.LinuxAppIcon
import app.gamenative.linux.LinuxAppScanner
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fetches the artwork at [url], or null if there is none to be had.
 *
 * Games carry artwork as a remote URL, so this goes through Coil to reuse its cache: the same
 * image is already on screen in the library, and asking again should not mean fetching again.
 * Hardware bitmaps are refused because the result gets read pixel by pixel afterwards.
 */
internal suspend fun loadGameArtwork(context: Context, url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null

    return withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()

            when (val drawable = (ImageLoader(context).execute(request) as? SuccessResult)?.drawable) {
                null -> null
                is BitmapDrawable -> drawable.bitmap
                else -> Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                ).also { bitmap ->
                    Canvas(bitmap).also { drawable.setBounds(0, 0, it.width, it.height) }
                        .let { drawable.draw(it) }
                }
            }
        }.getOrNull()
    }
}

/**
 * Squares [src] up for a launcher, on a background sampled from its own edges.
 *
 * Game artwork is wide and application icons are transparent, and a launcher wants neither: this
 * insets the image inside a square tile and fills what is left with a colour taken from the image
 * so the result does not read as a mistake.
 */
internal fun createAdaptiveIconBitmap(context: Context, src: Bitmap): Bitmap {
    val density = context.resources.displayMetrics.density
    val targetSize = (108f * density).toInt().coerceAtLeast(108)
    val outBmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(outBmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // --- Compute a background color from the icon's edge-center pixels (top/bottom/left/right) ---
    fun medianChannel(a: Int, b: Int, c: Int, d: Int): Int {
        // Median-of-four by sorting 4 values; small fixed-size so simple sort is fine
        val arr = intArrayOf(a, b, c, d)
        Arrays.sort(arr)
        // For even count, take average of the two middle values to avoid bias
        return ((arr[1] + arr[2]) / 2f).toInt()
    }
    fun sampleEdgeColor(bmp: Bitmap): Int {
        if (bmp.width <= 1 || bmp.height <= 1) return 0 // transparent fallback
        val midX = bmp.width / 2
        val midY = bmp.height / 2
        val top = bmp.getPixel(midX.coerceIn(0, bmp.width - 1), 0)
        val bottom = bmp.getPixel(midX.coerceIn(0, bmp.width - 1), bmp.height - 1)
        val left = bmp.getPixel(0, midY.coerceIn(0, bmp.height - 1))
        val right = bmp.getPixel(bmp.width - 1, midY.coerceIn(0, bmp.height - 1))

        // If three or more of the four edge-center pixels are exactly the same color, use that color
        val counts = hashMapOf<Int, Int>()
        listOf(top, bottom, left, right).forEach { c -> counts[c] = (counts[c] ?: 0) + 1 }
        val majority = counts.entries.firstOrNull { it.value >= 3 }?.key
        if (majority != null) return majority

        // Otherwise, fall back to median per channel to get a robust blended background
        val r = medianChannel(Color.red(top), Color.red(bottom), Color.red(left), Color.red(right))
        val g = medianChannel(Color.green(top), Color.green(bottom), Color.green(left), Color.green(right))
        val b = medianChannel(Color.blue(top), Color.blue(bottom), Color.blue(left), Color.blue(right))
        val a = medianChannel(Color.alpha(top), Color.alpha(bottom), Color.alpha(left), Color.alpha(right))
        return Color.argb(a, r, g, b)
    }

    val bgColor = sampleEdgeColor(src)

    // Fill background first so transparent icons still have a pleasant backdrop
    canvas.drawColor(bgColor)

    // Add uniform inset so icons are not cropped too tightly
    val insetFraction = 0.18f // 18% padding around
    val availSize = targetSize * (1f - insetFraction * 2f)

    // Center-fit scale to keep entire icon visible inside the padded area
    val scale = minOf(
        availSize.toFloat() / src.width.coerceAtLeast(1),
        availSize.toFloat() / src.height.coerceAtLeast(1),
    )
    val drawW = src.width * scale
    val drawH = src.height * scale
    val left = (targetSize - drawW) / 2f
    val top = (targetSize - drawH) / 2f
    val dest = RectF(left, top, left + drawW, top + drawH)

    canvas.drawBitmap(src, null, dest, paint)

    return outBmp
}

/** What a Linux application's shortcut id starts with, which is how we know one of ours. */
private const val LINUX_SHORTCUT_PREFIX = "linux_"

/**
 * Offers to pin a launcher shortcut for a Linux application.
 *
 * The shortcut carries the command rather than an id, so it keeps working across a rescan and
 * does not need the catalog to resolve it. Its icon is the entry's own, which is what makes a
 * pinned Linux app look like any other app on the home screen.
 */
internal suspend fun createLinuxAppShortcut(
    context: Context,
    app: LinuxAppScanner.LinuxApp,
) {
    val appContext = context.applicationContext
    val shortcutManager = appContext.getSystemService(ShortcutManager::class.java)

    val intent = Intent(MainActivity.ACTION_LINUX_DESKTOP).apply {
        setClass(appContext, MainActivity::class.java)
        putExtra(MainActivity.EXTRA_LINUX_ARGV, app.launchArgv)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val bitmap = withContext(Dispatchers.IO) { LinuxAppIcon.load(appContext, app.iconPath) }
    val icon: Icon = if (bitmap != null) {
        val adaptive = createAdaptiveIconBitmap(appContext, bitmap)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Icon.createWithAdaptiveBitmap(adaptive)
        } else {
            Icon.createWithBitmap(adaptive)
        }
    } else {
        Icon.createWithResource(appContext, R.mipmap.ic_shortcut_filter)
    }

    val shortcut = ShortcutInfo.Builder(appContext, "$LINUX_SHORTCUT_PREFIX${app.id}")
        .setShortLabel(app.name)
        .setLongLabel(app.name)
        .setIcon(icon)
        .setIntent(intent)
        .build()

    withContext(Dispatchers.Main) {
        // One we disabled when the application went away is still sitting on the home screen, and
        // pinning again would leave the user with two. Bringing that one back is what they meant.
        if (shortcutManager?.pinnedShortcuts?.any { it.id == shortcut.id } == true) {
            shortcutManager.enableShortcuts(listOf(shortcut.id))
            shortcutManager.updateShortcuts(listOf(shortcut))
            return@withContext
        }

        if (shortcutManager?.isRequestPinShortcutSupported == true) {
            shortcutManager.requestPinShortcut(shortcut, null)
        } else {
            // No pin dialog on this launcher: a dynamic shortcut at least reaches the long-press
            // menu on the app's own icon. Capped by the platform, hence the take().
            val existing = shortcutManager?.dynamicShortcuts?.filterNot { it.id == shortcut.id } ?: emptyList()
            shortcutManager?.dynamicShortcuts = (existing + shortcut).take(4)
        }
    }
}

/**
 * Disables the Linux shortcuts that no longer stand for an installed application.
 *
 * Disabled rather than removed: a pinned shortcut belongs to the home screen, and the platform
 * gives no way to take one back. Disabling greys it out and lets us say why when it is tapped,
 * which is better than a shortcut that silently does nothing.
 */
internal fun retireLinuxShortcuts(context: Context, apps: List<LinuxAppScanner.LinuxApp>) {
    val appContext = context.applicationContext
    val shortcutManager = appContext.getSystemService(ShortcutManager::class.java) ?: return

    val live = apps.mapTo(mutableSetOf()) { "linux_${it.id}" }
    fun stale(shortcuts: List<ShortcutInfo>) =
        shortcuts.filter { it.id.startsWith(LINUX_SHORTCUT_PREFIX) && it.id !in live }

    val pinned = stale(shortcutManager.pinnedShortcuts).filter { it.isEnabled }.map { it.id }
    if (pinned.isNotEmpty()) {
        shortcutManager.disableShortcuts(pinned, appContext.getString(R.string.linux_shortcut_gone))
    }

    val dynamic = stale(shortcutManager.dynamicShortcuts).map { it.id }
    if (dynamic.isNotEmpty()) {
        shortcutManager.removeDynamicShortcuts(dynamic)
    }
}

internal suspend fun createPinnedShortcut(context: Context, gameId: Int, label: String, gameSource: GameSource, iconUrl: String?) {
    val appContext = context.applicationContext
    val shortcutManager = appContext.getSystemService(ShortcutManager::class.java)

    val intent = Intent("app.gamenative.LAUNCH_GAME").apply {
        setClass(appContext, MainActivity::class.java)
        putExtra("app_id", gameId)
        putExtra("game_source", gameSource.name)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    val bitmapIcon = loadGameArtwork(appContext, iconUrl)

    val finalIcon: Icon = if (bitmapIcon != null) {
        val adaptiveBmp = createAdaptiveIconBitmap(appContext, bitmapIcon)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Icon.createWithAdaptiveBitmap(adaptiveBmp)
        } else {
            Icon.createWithBitmap(adaptiveBmp)
        }
    } else {
        Icon.createWithResource(appContext, R.mipmap.ic_shortcut_filter)
    }

    val shortcut = ShortcutInfo.Builder(appContext, "game_$gameId")
        .setShortLabel(label)
        .setLongLabel(label)
        .setIcon(finalIcon)
        .setIntent(intent)
        .build()

    withContext(Dispatchers.Main) {
        if (shortcutManager?.isRequestPinShortcutSupported == true) {
            shortcutManager.requestPinShortcut(shortcut, null)
        } else {
            val existing = shortcutManager?.dynamicShortcuts ?: emptyList()
            shortcutManager?.dynamicShortcuts = (existing + shortcut).take(4)
        }
    }
}
