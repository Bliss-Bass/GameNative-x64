package app.gamenative.linux

import android.content.Context
import android.util.DisplayMetrics
import app.gamenative.PrefManager

/**
 * How large the Linux session draws, in the one place that decides it.
 *
 * Android and the X world disagree about what a dot per inch is worth. Android sizes a `dp`
 * against a 160dpi baseline, so this panel's 219dpi renders its UI at 219/160 = 1.37x. X clients
 * -- GTK, Qt, anything using Xft -- size their defaults against a 96dpi baseline instead. Handing
 * them Android's 219 therefore asks for 219/96 = 2.28x, which is why Linux apps came out roughly
 * 1.7x larger than Android's own UI on the same screen rather than matching it.
 *
 * The fix is to convert between the baselines instead of passing the number through: a density
 * that means 1.37x to Android has to be expressed as 131 for it to mean 1.37x to X. Every
 * consumer -- the X server's -dpi, Xft/DPI in XSETTINGS, the panel's height -- goes through here,
 * because they only agree with each other if they share the arithmetic.
 *
 * On top of that sits a user multiplier, since matching Android is a sensible default rather than
 * a universally right answer: a desktop app's controls are designed for a mouse, and someone
 * driving this by touch may well want them larger than Android's own.
 */
object LinuxDisplayScale {

    /** What X clients assume when nothing tells them otherwise. */
    const val X_BASELINE_DPI = 96

    /** [DisplayMetrics.DENSITY_DEFAULT] -- the density at which one dp is one pixel. */
    private const val ANDROID_BASELINE_DPI = 160

    const val DEFAULT_SCALE_PERCENT = 100

    /** Offered in the UI. Coarse on purpose: this is a comfort setting, not a calibration. */
    val SCALE_CHOICES = listOf(75, 90, 100, 110, 125, 150, 200)

    // Below the X baseline text stops being legible; above this, toolkits start clamping
    // and window furniture no longer fits a tablet-sized screen.
    private const val MIN_DPI = 72
    private const val MAX_DPI = 400

    /**
     * The DPI to give X for an Android [densityDpi], with [scalePercent] applied.
     *
     * Pure so it can be reasoned about (and tested) without a Context: the two baselines and the
     * multiplier are the whole of the calculation.
     */
    fun xdpi(densityDpi: Int, scalePercent: Int): Int {
        val matched = densityDpi.toFloat() * X_BASELINE_DPI / ANDROID_BASELINE_DPI
        val scaled = matched * scalePercent / 100f
        return scaled.toInt().coerceIn(MIN_DPI, MAX_DPI)
    }

    /** As above, for the current display and the user's chosen scale. */
    fun xdpi(context: Context): Int =
        xdpi(context.resources.displayMetrics.densityDpi, PrefManager.linuxUiScalePercent)

    /** As above, but for a density the caller already has in hand (the session is told its own). */
    fun xdpi(densityDpi: Int): Int = xdpi(densityDpi, PrefManager.linuxUiScalePercent)

    /**
     * What the session is drawing at relative to an unscaled X client, for sizing the furniture we
     * draw ourselves. A tint2 panel is specified in pixels, so it has to be scaled by hand to end
     * up the same physical size as everything the toolkits are laying out.
     */
    fun factor(context: Context): Float = xdpi(context).toFloat() / X_BASELINE_DPI
}
