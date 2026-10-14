package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.ui.data.SteamSourceCompatStore
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.ProtonTier
import app.gamenative.utils.SteamCompatCategory

/**
 * One pill per confirmation source. GameNative is this app's own reports.
 * Proton, Deck, and SteamOS are only shown for Steam apps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceConfirmationBadges(
    gameNative: GameCompatibilityStatus?,
    steamAppId: Int,
    modifier: Modifier = Modifier,
    includeGameNative: Boolean = true,
) {
    if (steamAppId <= 0 && (!includeGameNative || gameNative == null)) return
    val external = if (steamAppId > 0) SteamSourceCompatStore.byAppId[steamAppId] else null
    val colors = PluviaTheme.colors
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (includeGameNative) {
            val (label, color) = gameNativeStyle(gameNative, colors)
            SourcePill(
                stringResource(R.string.compat_source_gamenative),
                stringResource(label),
                color,
            )
        }
        if (steamAppId > 0) {
            val proton = external?.protonTier
            val deck = external?.deck
            val steamOs = external?.steamOs
            SourcePill(
                stringResource(R.string.compat_source_proton),
                stringResource(proton.protonLabel()),
                proton?.protonColor(colors) ?: colors.compatibilityUnknown,
            )
            SourcePill(
                stringResource(R.string.compat_source_deck),
                stringResource(deck?.steamLabel() ?: R.string.library_compatibility_unknown),
                deck?.steamColor(colors) ?: colors.compatibilityUnknown,
            )
            SourcePill(
                stringResource(R.string.compat_source_steamos),
                stringResource(steamOs?.steamLabel() ?: R.string.library_compatibility_unknown),
                steamOs?.steamColor(colors) ?: colors.compatibilityUnknown,
            )
        }
    }
}

@Composable
private fun SourcePill(source: String, status: String, color: Color) {
    Text(
        text = "$source · $status",
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.22f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

private fun gameNativeStyle(
    status: GameCompatibilityStatus?,
    colors: app.gamenative.ui.theme.PluviaColors,
): Pair<Int, Color> = when (status) {
    GameCompatibilityStatus.COMPATIBLE,
    GameCompatibilityStatus.GPU_COMPATIBLE,
    -> R.string.library_compatible to colors.compatibilityGood
    GameCompatibilityStatus.NOT_COMPATIBLE -> R.string.library_not_compatible to colors.compatibilityBad
    else -> R.string.library_compatibility_unknown to colors.compatibilityUnknown
}

private fun ProtonTier?.protonLabel(): Int = when (this) {
    ProtonTier.PLATINUM -> R.string.compat_tier_platinum
    ProtonTier.GOLD -> R.string.compat_tier_gold
    ProtonTier.SILVER -> R.string.compat_tier_silver
    ProtonTier.BRONZE -> R.string.compat_tier_bronze
    ProtonTier.BORKED -> R.string.compat_tier_borked
    ProtonTier.PENDING -> R.string.compat_tier_pending
    ProtonTier.UNKNOWN, null -> R.string.library_compatibility_unknown
}

private fun ProtonTier.protonColor(colors: app.gamenative.ui.theme.PluviaColors): Color = when (this) {
    ProtonTier.PLATINUM, ProtonTier.GOLD -> colors.compatibilityGood
    ProtonTier.SILVER, ProtonTier.BRONZE -> colors.compatibilityPartial
    ProtonTier.BORKED -> colors.compatibilityBad
    ProtonTier.PENDING, ProtonTier.UNKNOWN -> colors.compatibilityUnknown
}

private fun SteamCompatCategory.steamLabel(): Int = when (this) {
    SteamCompatCategory.VERIFIED -> R.string.compat_steam_verified
    SteamCompatCategory.PLAYABLE -> R.string.compat_steam_playable
    SteamCompatCategory.UNSUPPORTED -> R.string.compat_steam_unsupported
    SteamCompatCategory.UNKNOWN -> R.string.library_compatibility_unknown
}

private fun SteamCompatCategory.steamColor(colors: app.gamenative.ui.theme.PluviaColors): Color = when (this) {
    SteamCompatCategory.VERIFIED -> colors.compatibilityGood
    SteamCompatCategory.PLAYABLE -> colors.compatibilityPartial
    SteamCompatCategory.UNSUPPORTED -> colors.compatibilityBad
    SteamCompatCategory.UNKNOWN -> colors.compatibilityUnknown
}
