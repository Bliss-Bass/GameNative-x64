package app.gamenative.gamefixes

import android.content.Context
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Source's `-safe` flag starts the game in a small window and ignores the
 * video config, so the console and any other window sit beside it. A
 * fullscreen choice in the menu does not survive the next launch until
 * `-safe` is gone.
 *
 * Detected when the install has a Source `gameinfo.txt` and the launch
 * options include a standalone `-safe`. The flag is removed. `-fullscreen`
 * is added unless the options already ask for a window or for fullscreen.
 */
object SourceSafeModeFix : GameFix {
    private val SAFE_FLAG = Regex("""(?i)(?:^|\s)-safe(?=\s|$)""")
    private val FULLSCREEN_FLAG = Regex("""(?i)(?:^|\s)-(?:fullscreen|full)(?=\s|$)""")
    private val WINDOWED_FLAG = Regex("""(?i)(?:^|\s)-(?:windowed|window|sw)(?=\s|$)""")
    private val WHITESPACE = Regex("""\s+""")

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        val args = container.execArgs.orEmpty()
        if (!SAFE_FLAG.containsMatchIn(args) || !isSourceInstall(installPath)) return true
        val withoutSafe = SAFE_FLAG.replace(args, " ").replace(WHITESPACE, " ").trim()
        val updated = if (WINDOWED_FLAG.containsMatchIn(withoutSafe) || FULLSCREEN_FLAG.containsMatchIn(withoutSafe)) {
            withoutSafe
        } else {
            "$withoutSafe -fullscreen".trim()
        }
        return try {
            container.execArgs = updated
            container.saveData()
            Timber.tag("GameFixes").i("Dropped -safe for Source game $gameId so it can stay fullscreen")
            true
        } catch (e: Exception) {
            Timber.tag("GameFixes").e(e, "Failed to drop -safe for Source game $gameId")
            false
        }
    }

    internal fun isSourceInstall(installPath: String): Boolean {
        val root = File(installPath)
        if (File(root, "gameinfo.txt").isFile) return true
        val children = root.listFiles() ?: return false
        return children.any { child -> child.isDirectory && File(child, "gameinfo.txt").isFile }
    }
}
