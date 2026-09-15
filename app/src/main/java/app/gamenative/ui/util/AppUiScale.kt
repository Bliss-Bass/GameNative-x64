package app.gamenative.ui.util

import app.gamenative.PrefManager
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How large GameNative's own Compose UI draws, as a percentage of the system density.
 *
 * Distinct from [app.gamenative.linux.LinuxDisplayScale]: that one sizes Linux/X sessions.
 * This one only multiplies Compose [androidx.compose.ui.unit.Density.density] so library,
 * settings, and overlays stay readable on dense tablet panels (e.g. 1080p at ~160dpi).
 *
 * Font scale is left alone on purpose — Compose `sp` already multiplies by density, so
 * bumping both would double-scale text.
 */
object AppUiScale {

    const val DEFAULT_SCALE_PERCENT = 100

    /** Coarse comfort steps; same spirit as the Linux session scale picker. */
    val SCALE_CHOICES = listOf(75, 90, 100, 110, 125, 150, 175, 200)

    private val _percent = MutableStateFlow(DEFAULT_SCALE_PERCENT)
    val percentFlow: StateFlow<Int> = _percent.asStateFlow()

    fun syncFromPrefs() {
        _percent.value = sanitize(PrefManager.appUiScalePercent)
    }

    fun setPercent(percent: Int) {
        val sanitized = sanitize(percent)
        PrefManager.appUiScalePercent = sanitized
        _percent.value = sanitized
    }

    fun sanitize(percent: Int): Int =
        SCALE_CHOICES.minByOrNull { abs(it - percent) } ?: DEFAULT_SCALE_PERCENT

    fun factor(percent: Int = _percent.value): Float = sanitize(percent) / 100f
}
