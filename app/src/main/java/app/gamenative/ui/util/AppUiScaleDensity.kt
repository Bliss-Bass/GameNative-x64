package app.gamenative.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Multiplies Compose layout density by the user's [AppUiScale] setting so the whole tree
 * (dp and sp) grows or shrinks together. Does not change [Density.fontScale].
 */
@Composable
fun ProvideAppUiScale(content: @Composable () -> Unit) {
    val percent by AppUiScale.percentFlow.collectAsState()
    val base = LocalDensity.current
    val scaled = remember(percent, base) {
        val factor = AppUiScale.factor(percent)
        Density(
            density = base.density * factor,
            fontScale = base.fontScale,
        )
    }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}
