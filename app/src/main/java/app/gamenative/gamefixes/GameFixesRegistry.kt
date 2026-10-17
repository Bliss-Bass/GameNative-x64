package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicService
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object GameFixesRegistry {
    private const val GAME_DRIVE_LETTER = "A"

    private val fixes: Map<Pair<GameSource, String>, GameFix> = listOf(
        GOG_Fix_1129934535,
        GOG_Fix_1141086411,
        GOG_Fix_1177610018,
        GOG_Fix_1453375253,
        GOG_Fix_1454315831,
        GOG_Fix_1454587428,
        GOG_Fix_1458058109,
        GOG_Fix_1589319779,
        GOG_Fix_1635627436,
        GOG_Fix_1787707874,
        GOG_Fix_1808582759,
        GOG_Fix_2147483047,
        STEAM_Fix_400,
        STEAM_Fix_22300,
        STEAM_Fix_22330,
        STEAM_Fix_22370,
        STEAM_Fix_22380,
        STEAM_Fix_22490,
        STEAM_Fix_312520,
        STEAM_Fix_379720,
        STEAM_Fix_413150,
        STEAM_Fix_413420,
        STEAM_Fix_752580,
        STEAM_Fix_1293830,
        STEAM_Fix_1637320,
        STEAM_Fix_1962700,
        STEAM_Fix_2868840,
        STEAM_Fix_3373660,
        STEAM_Fix_3602270,
        STEAM_Fix_4320000,
        STEAM_Fix_990080,
        EPIC_Fix_b1b4e0b67a044575820cb5e63028dcae,
        EPIC_Fix_dabb52e328834da7bbe99691e374cb84,
        EPIC_Fix_e345fdb9186645a48d30c3f85a8951dc,
        EPIC_Fix_59a0c86d02da42e8ba6444cb171e61bf,
        EPIC_Fix_864c7bc2c2394f7dbd1b534aa068ff56,
        EPIC_Fix_4c2659cf3411441dbc02ffc965643e47,
    ).associateBy { it.gameSource to it.gameId }

    private var fixesProvider: () -> Map<Pair<GameSource, String>, GameFix> = { fixes }

    fun applyFor(context: Context, appId: String, container: Container) {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return
        val catalogId = catalogIdFor(source, gameId) ?: return
        Timber.i("GameFixesRegistry: Applying fixes for game: $source $catalogId if available")
        val (installPath, installPathWindows) = resolvePaths(context, source, gameId) ?: return
        fixesProvider()[source to catalogId]?.apply(
            context,
            catalogId,
            installPath,
            installPathWindows,
            container,
        )
        // -safe is not a per-title fix. Any Source install that still has it
        // starts windowed, which is the dialog-beside-the-game case.
        SourceSafeModeFix.apply(context, catalogId, installPath, installPathWindows, container)
    }

    /** Returns the user launch option that keeps this game from starting, if the fix knows one. */
    fun findLaunchArgConflict(appId: String, container: Container): LaunchArgConflict? =
        launchArgConflictFixFor(appId)?.findConflict(container)

    fun resolveLaunchArgConflict(appId: String, container: Container, conflict: LaunchArgConflict, accept: Boolean) {
        launchArgConflictFixFor(appId)?.resolveConflict(container, conflict, accept)
    }

    private fun launchArgConflictFixFor(appId: String): LaunchArgConflictFix? {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return null
        val catalogId = catalogIdFor(source, gameId) ?: return null
        return fixesProvider()[source to catalogId]?.launchArgConflictFix()
    }

    private fun GameFix.launchArgConflictFix(): LaunchArgConflictFix? = when (this) {
        is LaunchArgConflictFix -> this
        is KeyedCompositeGameFix -> fixes.firstNotNullOfOrNull { it.launchArgConflictFix() }
        is CompositeGameFix -> fixes.firstNotNullOfOrNull { it.launchArgConflictFix() }
        else -> null
    }

    // EPIC auto-generates the id, so fixes are keyed by the catalog id instead.
    private fun catalogIdFor(source: GameSource, gameId: String): String? = when (source) {
        GameSource.EPIC -> EpicService.getEpicGameOf(gameId.toInt())?.catalogId
        else -> gameId
    }

    private fun resolvePaths(context: Context, source: GameSource, gameId: String): Pair<String, String>? {
        return when (source) {
            GameSource.GOG -> {
                val game = runBlocking(Dispatchers.IO) { GOGService.getGOGGameOf(gameId) } ?: return null
                if (!game.isInstalled) return null
                val path = game.installPath.ifEmpty { GOGConstants.getGameInstallPath(game.title) }
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            GameSource.STEAM -> {
                val path = SteamService.getAppDirPath(gameId.toInt())
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            GameSource.EPIC -> {
                val path = EpicService.getInstallPath(gameId.toInt()) ?: return null
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            else -> null
        }
    }

    /**
     * Test-only hook to override the game-fixes provider.
     * Not intended for production code paths.
     *
     * @param provider Fixes provider for tests; pass null to restore the default provider.
     */
    internal fun setFixesProviderForTests(provider: (() -> Map<Pair<GameSource, String>, GameFix>)?) {
        fixesProvider = provider ?: { fixes }
    }
}
