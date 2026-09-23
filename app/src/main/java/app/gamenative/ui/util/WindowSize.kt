package app.gamenative.ui.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager

/**
 * Window width size classes based on Material Design 3 guidelines.
 * https://m3.material.io/foundations/layout/applying-layout/window-size-classes
 */
enum class WindowWidthClass {
    COMPACT,  // < 600dp
    MEDIUM,   // 600-840dp
    EXPANDED, // > 840dp
}

/**
 * Top/side insets for app chrome in fullscreen and freeform/desktop windowed mode.
 *
 * Desktop freeform (SmartDock-DFC) attaches a [WindowInsets.captionBar] source to the task;
 * status bars alone are not enough under the window title decoration.
 */
@Composable
fun pluviaTopContentHorizontalInsets(): WindowInsets =
    WindowInsets.statusBars
        .union(WindowInsets.displayCutout)
        .union(WindowInsets.captionBar)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

@Composable
fun Modifier.pluviaTopSafeAreaPadding(): Modifier =
    windowInsetsPadding(pluviaTopContentHorizontalInsets())

/**
 * Full safe drawing insets: caption / status / cutout on top, and SmartDock's navigationBars
 * (dock) on the bottom when a window overlaps the reserved dock space.
 */
@Composable
fun Modifier.pluviaSafeDrawingPadding(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing)

@Composable
fun rememberWindowWidthClass(): WindowWidthClass {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp) {
        when {
            configuration.screenWidthDp < 600 -> WindowWidthClass.COMPACT
            configuration.screenWidthDp < 840 -> WindowWidthClass.MEDIUM
            else -> WindowWidthClass.EXPANDED
        }
    }
}

@Composable
fun rememberScreenWidthDp(): Int {
    val configuration = LocalConfiguration.current
    return configuration.screenWidthDp
}

@Composable
fun shouldShowGamepadUI(): Boolean {
    if (!PrefManager.showGamepadHints) {
        return false
    }
    return rememberWindowWidthClass() != WindowWidthClass.COMPACT
}

@Composable
fun adaptivePanelWidth(preferredWidth: Dp, maxWidthPercent: Float = 0.85f): Dp {
    val screenWidthDp = rememberScreenWidthDp()
    val maxWidth = (screenWidthDp * maxWidthPercent).dp
    return minOf(preferredWidth, maxWidth)
}

object AdaptivePadding {
    @Composable
    fun horizontal(): Dp = when (rememberWindowWidthClass()) {
        WindowWidthClass.COMPACT -> 12.dp
        WindowWidthClass.MEDIUM -> 16.dp
        WindowWidthClass.EXPANDED -> 20.dp
    }

    @Composable
    fun gridSpacing(): Dp = when (rememberWindowWidthClass()) {
        WindowWidthClass.COMPACT -> 8.dp
        WindowWidthClass.MEDIUM -> 10.dp
        WindowWidthClass.EXPANDED -> 12.dp
    }
}

object AdaptiveHeroHeight {
    @Composable
    fun get(): Dp = when (rememberWindowWidthClass()) {
        WindowWidthClass.COMPACT -> 200.dp
        WindowWidthClass.MEDIUM -> 300.dp
        WindowWidthClass.EXPANDED -> 420.dp
    }
}
