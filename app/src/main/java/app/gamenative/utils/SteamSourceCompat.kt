package app.gamenative.utils

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

enum class ProtonTier {
    PLATINUM,
    GOLD,
    SILVER,
    BRONZE,
    BORKED,
    PENDING,
    UNKNOWN,
}

/** Steam Deck and SteamOS categories: 0 unknown, 1 unsupported, 2 playable, 3 verified. */
enum class SteamCompatCategory {
    UNKNOWN,
    UNSUPPORTED,
    PLAYABLE,
    VERIFIED,
    ;

    companion object {
        fun fromCode(code: Int): SteamCompatCategory = when (code) {
            1 -> UNSUPPORTED
            2 -> PLAYABLE
            3 -> VERIFIED
            else -> UNKNOWN
        }
    }
}

/**
 * Desktop x86 Proton ratings for one Steam app.
 * ProtonDB is community Proton on PC. Deck and SteamOS come from Valve.
 */
data class SteamSourceCompat(
    val protonTier: ProtonTier = ProtonTier.UNKNOWN,
    val deck: SteamCompatCategory = SteamCompatCategory.UNKNOWN,
    val steamOs: SteamCompatCategory = SteamCompatCategory.UNKNOWN,
    val fetchedAt: Long = 0L,
)

/**
 * Loads ProtonDB and Steam Deck/SteamOS ratings for Steam app ids.
 * Results are cached for a week. A failed request is remembered only for this process.
 */
object SteamSourceCompatService {
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val CACHE_FILE = "steam-source-compat.json"
    private const val USER_AGENT = "GameNative-x64"

    private val failedThisProcess = ConcurrentHashMap.newKeySet<Int>()

    fun loadFresh(context: Context, appIds: Collection<Int>): Map<Int, SteamSourceCompat> {
        if (appIds.isEmpty()) return emptyMap()
        val wanted = appIds.toSet()
        val now = System.currentTimeMillis()
        return readCache(context).filter { (id, value) ->
            id in wanted && now - value.fetchedAt < TTL_MS
        }
    }

    suspend fun fetchMissing(context: Context, appIds: Collection<Int>): Map<Int, SteamSourceCompat> =
        withContext(Dispatchers.IO) {
            val fresh = loadFresh(context, appIds)
            val missing = appIds.distinct().filter { it > 0 && it !in fresh && it !in failedThisProcess }
            if (missing.isEmpty()) return@withContext emptyMap()

            val gate = Semaphore(4)
            val fetched = coroutineScope {
                missing.map { appId ->
                    async {
                        gate.withPermit { fetchOne(appId) }
                    }
                }.awaitAll().filterNotNull()
            }
            if (fetched.isNotEmpty()) writeCache(context, fetched)
            fetched.associate { it.first to it.second }
        }

    internal fun parseProtonTier(body: String): ProtonTier {
        val tier = runCatching { JSONObject(body).optString("tier") }.getOrDefault("")
        return when (tier.lowercase()) {
            "platinum" -> ProtonTier.PLATINUM
            "gold" -> ProtonTier.GOLD
            "silver" -> ProtonTier.SILVER
            "bronze" -> ProtonTier.BRONZE
            "borked" -> ProtonTier.BORKED
            "pending" -> ProtonTier.PENDING
            else -> ProtonTier.UNKNOWN
        }
    }

    internal fun parseSteamCategories(body: String): Pair<SteamCompatCategory, SteamCompatCategory> {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return SteamCompatCategory.UNKNOWN to SteamCompatCategory.UNKNOWN
        if (json.optInt("success", 0) != 1) return SteamCompatCategory.UNKNOWN to SteamCompatCategory.UNKNOWN
        val results = json.optJSONObject("results") ?: return SteamCompatCategory.UNKNOWN to SteamCompatCategory.UNKNOWN
        return SteamCompatCategory.fromCode(results.optInt("resolved_category", 0)) to
            SteamCompatCategory.fromCode(results.optInt("steamos_resolved_category", 0))
    }

    private fun fetchOne(appId: Int): Pair<Int, SteamSourceCompat>? {
        val proton = runCatching { parseProtonTier(get(protonUrl(appId))) }.getOrElse { error ->
            Timber.tag("SteamSourceCompat").w(error, "ProtonDB failed for %d", appId)
            null
        }
        val steam = runCatching { parseSteamCategories(get(steamUrl(appId))) }.getOrElse { error ->
            Timber.tag("SteamSourceCompat").w(error, "Steam Deck rating failed for %d", appId)
            null
        }
        if (proton == null && steam == null) {
            failedThisProcess.add(appId)
            return null
        }
        val (deck, steamOs) = steam ?: (SteamCompatCategory.UNKNOWN to SteamCompatCategory.UNKNOWN)
        return appId to SteamSourceCompat(
            protonTier = proton ?: ProtonTier.UNKNOWN,
            deck = deck,
            steamOs = steamOs,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body?.string().orEmpty()
        }
    }

    private fun protonUrl(appId: Int) = "https://www.protondb.com/api/v1/reports/summaries/$appId.json"

    private fun steamUrl(appId: Int) =
        "https://store.steampowered.com/saleaction/ajaxgetdeckappcompatibilityreport?nAppID=$appId&l=english"

    private fun cacheFile(context: Context) = File(context.cacheDir, CACHE_FILE)

    private fun readCache(context: Context): Map<Int, SteamSourceCompat> {
        val file = cacheFile(context)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            buildMap {
                for (key in root.keys()) {
                    val id = key.toIntOrNull() ?: continue
                    val item = root.optJSONObject(key) ?: continue
                    put(
                        id,
                        SteamSourceCompat(
                            protonTier = runCatching { ProtonTier.valueOf(item.optString("proton")) }.getOrDefault(ProtonTier.UNKNOWN),
                            deck = SteamCompatCategory.fromCode(item.optInt("deck", 0)),
                            steamOs = SteamCompatCategory.fromCode(item.optInt("steamos", 0)),
                            fetchedAt = item.optLong("at", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeCache(context: Context, fetched: List<Pair<Int, SteamSourceCompat>>) {
        val merged = readCache(context).toMutableMap()
        fetched.forEach { (id, value) -> merged[id] = value }
        val root = JSONObject()
        merged.forEach { (id, value) ->
            root.put(
                id.toString(),
                JSONObject()
                    .put("proton", value.protonTier.name)
                    .put("deck", value.deck.ordinal)
                    .put("steamos", value.steamOs.ordinal)
                    .put("at", value.fetchedAt),
            )
        }
        cacheFile(context).writeText(root.toString())
    }
}
