package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.R
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.StringUtils
import timber.log.Timber

/**
 * Asks before replacing the DXVK default with [wrapper].
 *
 * Same prompt Doom uses when a launch option selects a renderer this container cannot run.
 * DXVK stays in place until the user accepts. "Keep mine" is remembered and not asked again.
 * A wrapper that is already something else (vkd3d, wined3d, cnc-ddraw) is left alone.
 */
class DxWrapperFix(
    private val wrapper: String,
) : GameFix, LaunchArgConflictFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean = true

    override fun findConflict(container: Container): LaunchArgConflict? {
        val current = StringUtils.parseIdentifier(container.dxWrapper)
        if (!current.isDxvk()) return null
        if (container.getExtra(KEPT_WRAPPER_EXTRA) == current) return null
        return LaunchArgConflict(
            found = "DXVK",
            replacement = "VKD3D",
            titleRes = R.string.dx_wrapper_conflict_title,
            messageRes = R.string.dx_wrapper_conflict_message,
        )
    }

    override fun resolveConflict(container: Container, conflict: LaunchArgConflict, accept: Boolean) {
        if (accept) {
            container.dxWrapper = wrapper
            Timber.tag("GameFixes").i("Switched DX wrapper to '$wrapper'")
        } else {
            val current = StringUtils.parseIdentifier(container.dxWrapper)
            container.putExtra(KEPT_WRAPPER_EXTRA, current)
            Timber.tag("GameFixes").i("Keeping DX wrapper '$current' at the user's request")
        }
        container.saveData()
    }

    private companion object {
        const val KEPT_WRAPPER_EXTRA = "gameFixKeptDxWrapper"
    }
}

class KeyedDxWrapperFix(
    override val gameSource: GameSource,
    override val gameId: String,
    wrapper: String,
) : KeyedGameFix, GameFix by DxWrapperFix(wrapper), LaunchArgConflictFix by DxWrapperFix(wrapper)

private fun String.isDxvk(): Boolean = this == "dxvk" || startsWith("dxvk-")
