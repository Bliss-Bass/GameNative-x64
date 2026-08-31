package app.gamenative.stubs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * The icon a stub falls back to, shared by the game and Linux paths.
 *
 * Every stub needs an icon, and there are several ordinary ways not to have one: a game with no
 * store artwork, or a desktop entry naming an icon that is missing or in a format Android cannot
 * decode -- XPM, which plenty of older X applications still ship, being the common case.
 *
 * None of those is a reason to refuse to create the entry. An entry the user can find and launch,
 * wearing a plain tile, beats no entry at all over a missing image.
 */
object StubIcons {

    /** Behind a lettered tile. Matches the launcher's own placeholder tone rather than shouting. */
    private const val BACKGROUND = 0xFF37474F.toInt()

    /** A tile carrying [label]'s first letter. */
    fun lettered(context: Context, label: String): Bitmap {
        val size = (108f * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        val letter = label.trim().firstOrNull()?.uppercase() ?: "?"

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Offset from the centre by the glyph's own extents, since drawText places the baseline.
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f

        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(BACKGROUND)
                drawText(letter, size / 2f, baseline, paint)
            }
        }
    }

    /** A stub's icon travels as a PNG, whatever it was drawn from. */
    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream()
        .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        .toByteArray()
}
