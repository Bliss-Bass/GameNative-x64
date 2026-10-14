package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material.icons.rounded.WineBar
import app.gamenative.ui.icons.Steam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.ui.data.SteamSourceCompatStore
import app.gamenative.ui.theme.PluviaColors
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.ProtonTier
import app.gamenative.utils.SteamCompatCategory

/**
 * One icon per confirmation source, in a single row across the card.
 * GameNative is this app's own reports. Proton, Deck, and SteamOS are only shown for Steam apps.
 * The disc color is the rating; the glyph is the source.
 */
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
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (includeGameNative) {
            val (tint, background) = gameNativeColors(gameNative, colors)
            SourceMark(
                icon = Icons.Rounded.SportsEsports,
                description = markDescription(
                    R.string.compat_source_gamenative,
                    gameNativeLabel(gameNative),
                ),
                tint = tint,
                background = background,
            )
        }
        if (steamAppId > 0) {
            val proton = external?.protonTier
            val deck = external?.deck
            val steamOs = external?.steamOs
            SourceMark(
                icon = Icons.Rounded.WineBar,
                description = markDescription(R.string.compat_source_proton, proton.protonLabel()),
                tint = proton?.protonTint(colors) ?: colors.compatibilityUnknown,
                background = proton?.protonBackground(colors) ?: colors.compatibilityUnknownBackground,
            )
            SourceMark(
                icon = Icons.Rounded.VideogameAsset,
                description = markDescription(
                    R.string.compat_source_deck,
                    deck?.steamLabel() ?: R.string.library_compatibility_unknown,
                ),
                tint = deck?.steamTint(colors) ?: colors.compatibilityUnknown,
                background = deck?.steamBackground(colors) ?: colors.compatibilityUnknownBackground,
            )
            SourceMark(
                icon = Icons.Filled.Steam,
                description = markDescription(
                    R.string.compat_source_steamos,
                    steamOs?.steamLabel() ?: R.string.library_compatibility_unknown,
                ),
                tint = steamOs?.steamTint(colors) ?: colors.compatibilityUnknown,
                background = steamOs?.steamBackground(colors) ?: colors.compatibilityUnknownBackground,
            )
        }
    }
}

@Composable
private fun markDescription(source: Int, status: Int): String =
    "${stringResource(source)} ${stringResource(status)}"

@Composable
private fun SourceMark(
    icon: ImageVector,
    description: String,
    tint: Color,
    background: Color,
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun gameNativeLabel(status: GameCompatibilityStatus?): Int = when (status) {
    GameCompatibilityStatus.COMPATIBLE,
    GameCompatibilityStatus.GPU_COMPATIBLE,
    -> R.string.library_compatible
    GameCompatibilityStatus.NOT_COMPATIBLE -> R.string.library_not_compatible
    else -> R.string.library_compatibility_unknown
}

private fun gameNativeColors(
    status: GameCompatibilityStatus?,
    colors: PluviaColors,
): Pair<Color, Color> = when (status) {
    GameCompatibilityStatus.COMPATIBLE,
    GameCompatibilityStatus.GPU_COMPATIBLE,
    -> colors.compatibilityGood to colors.compatibilityGoodBackground
    GameCompatibilityStatus.NOT_COMPATIBLE -> colors.compatibilityBad to colors.compatibilityBadBackground
    else -> colors.compatibilityUnknown to colors.compatibilityUnknownBackground
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

private fun ProtonTier.protonTint(colors: PluviaColors): Color = when (this) {
    ProtonTier.PLATINUM, ProtonTier.GOLD -> colors.compatibilityGood
    ProtonTier.SILVER, ProtonTier.BRONZE -> colors.compatibilityPartial
    ProtonTier.BORKED -> colors.compatibilityBad
    ProtonTier.PENDING, ProtonTier.UNKNOWN -> colors.compatibilityUnknown
}

private fun ProtonTier.protonBackground(colors: PluviaColors): Color = when (this) {
    ProtonTier.PLATINUM, ProtonTier.GOLD -> colors.compatibilityGoodBackground
    ProtonTier.SILVER, ProtonTier.BRONZE -> colors.compatibilityPartialBackground
    ProtonTier.BORKED -> colors.compatibilityBadBackground
    ProtonTier.PENDING, ProtonTier.UNKNOWN -> colors.compatibilityUnknownBackground
}

private fun SteamCompatCategory.steamLabel(): Int = when (this) {
    SteamCompatCategory.VERIFIED -> R.string.compat_steam_verified
    SteamCompatCategory.PLAYABLE -> R.string.compat_steam_playable
    SteamCompatCategory.UNSUPPORTED -> R.string.compat_steam_unsupported
    SteamCompatCategory.UNKNOWN -> R.string.library_compatibility_unknown
}

private fun SteamCompatCategory.steamTint(colors: PluviaColors): Color = when (this) {
    SteamCompatCategory.VERIFIED -> colors.compatibilityGood
    SteamCompatCategory.PLAYABLE -> colors.compatibilityPartial
    SteamCompatCategory.UNSUPPORTED -> colors.compatibilityBad
    SteamCompatCategory.UNKNOWN -> colors.compatibilityUnknown
}

private fun SteamCompatCategory.steamBackground(colors: PluviaColors): Color = when (this) {
    SteamCompatCategory.VERIFIED -> colors.compatibilityGoodBackground
    SteamCompatCategory.PLAYABLE -> colors.compatibilityPartialBackground
    SteamCompatCategory.UNSUPPORTED -> colors.compatibilityBadBackground
    SteamCompatCategory.UNKNOWN -> colors.compatibilityUnknownBackground
}
