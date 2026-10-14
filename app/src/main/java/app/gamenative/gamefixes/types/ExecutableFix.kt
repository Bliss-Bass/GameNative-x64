package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import timber.log.Timber

/**
 * Launches [executable] in place of the game's default [replaces].
 *
 * Only applies while the container is unset or still on [replaces], so an executable the user
 * picked themselves is left alone. Paths are relative to the game's install directory.
 */
class ExecutableFix(
    private val executable: String,
    private val replaces: String,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            val current = container.executablePath.trim()
            if (current.isNotEmpty() && normalize(current) != normalize(replaces)) return true

            container.executablePath = executable
            container.saveData()
            Timber.tag("GameFixes").i("Launching '$executable' instead of '$replaces' for game $gameId")
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to set executable '$executable' for game $gameId")
            false
        }
    }

    private fun normalize(path: String) = path.replace('\\', '/').lowercase()
}
