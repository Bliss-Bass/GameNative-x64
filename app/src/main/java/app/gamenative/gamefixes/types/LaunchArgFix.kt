package app.gamenative.gamefixes

import android.content.Context
import androidx.annotation.StringRes
import app.gamenative.R
import app.gamenative.data.GameSource
import timber.log.Timber
import com.winlator.container.Container

/**
 * Something in the container that keeps the game from starting, and the value to offer instead.
 * [titleRes] and [messageRes] are the dialog. [messageRes] takes the found value, then the replacement.
 */
data class LaunchArgConflict(
    val found: String,
    val replacement: String,
    @StringRes val titleRes: Int = R.string.launch_arg_conflict_title,
    @StringRes val messageRes: Int = R.string.launch_arg_conflict_message,
)

/** A fix that can spot user launch options it conflicts with, so the user can be asked to switch. */
interface LaunchArgConflictFix {
    fun findConflict(container: Container): LaunchArgConflict?

    /** Swaps the conflicting option for the replacement, or remembers that the user kept it. */
    fun resolveConflict(container: Container, conflict: LaunchArgConflict, accept: Boolean)
}

class LaunchArgFix(
    private val launchArgs: String,
    /** User launch options matching this are offered a switch to [launchArgs] before launch. */
    private val conflictingArgs: Regex? = null,
) : GameFix, LaunchArgConflictFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            val currentArgs = container.execArgs.trim()
            if (currentArgs.isNotEmpty()) {
                // Do not override / append when the user already configured custom launch args.
                return true
            }

            container.execArgs = launchArgs
            container.saveData()
            Timber.tag("GameFixes").i("Added launch args '$launchArgs' for game $gameId")
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to add launch args '$launchArgs' for game $gameId")
            false
        }
    }

    override fun findConflict(container: Container): LaunchArgConflict? {
        val pattern = conflictingArgs ?: return null
        val found = pattern.find(container.execArgs)?.value ?: return null
        if (container.getExtra(KEPT_CONFLICT_EXTRA) == found) return null
        return LaunchArgConflict(found, launchArgs)
    }

    override fun resolveConflict(container: Container, conflict: LaunchArgConflict, accept: Boolean) {
        val pattern = conflictingArgs ?: return
        if (accept) {
            var replaced = false
            container.execArgs = pattern
                .replace(container.execArgs) {
                    if (replaced) "" else launchArgs.also { replaced = true }
                }
                .replace(WHITESPACE, " ")
                .trim()
            Timber.tag("GameFixes").i("Replaced launch args '${conflict.found}' with '$launchArgs'")
        } else {
            container.putExtra(KEPT_CONFLICT_EXTRA, conflict.found)
            Timber.tag("GameFixes").i("Keeping launch args '${conflict.found}' at the user's request")
        }
        container.saveData()
    }

    private companion object {
        const val KEPT_CONFLICT_EXTRA = "gameFixKeptLaunchArgs"
        val WHITESPACE = Regex("\\s+")
    }
}

class KeyedLaunchArgFix private constructor(
    override val gameSource: GameSource,
    override val gameId: String,
    fix: LaunchArgFix,
) : KeyedGameFix, GameFix by fix, LaunchArgConflictFix by fix {
    constructor(
        gameSource: GameSource,
        gameId: String,
        launchArgs: String,
        conflictingArgs: Regex? = null,
    ) : this(gameSource, gameId, LaunchArgFix(launchArgs, conflictingArgs))
}
