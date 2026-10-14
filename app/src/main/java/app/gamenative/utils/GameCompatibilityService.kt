package app.gamenative.utils

import android.content.Context
import androidx.compose.ui.graphics.Color
import app.gamenative.BuildConfig
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * Service for fetching game compatibility information from GameNative API.
 */
object GameCompatibilityService {
    private const val API_BASE_URL = "https://api.gamenative.app/api/game-runs"
    private val httpClient = Net.http

    /**
     * Data class for API request.
     */
    data class GameCompatibilityRequest(
        val gameNames: List<String>,
        val gpuName: String
    )

    /**
     * Data class for API response per game.
     */
    data class GameCompatibilityResponse(
        val gameName: String,
        val totalPlayableCount: Int,
        val gpuPlayableCount: Int,
        val avgRating: Float,
        val hasBeenTried: Boolean,
        val isNotWorking: Boolean
    )

    /**
     * Compatibility message with text and color.
     */
    data class CompatibilityMessage(
        val text: String,
        val color: Color
    )

    /**
     * Gets user-friendly compatibility message based on compatibility response.
     * Uses totalPlayableCount and gpuPlayableCount to determine the message.
     */
    /**
     * Play reports are not split by CPU architecture. On x86_64, totals and
     * "not working" flags are mostly ARM phone runs, so a badge is shown only
     * when this GPU itself has playable runs.
     */
    fun statusFor(
        response: GameCompatibilityResponse,
        host: HostCpu = HostCpu.current(),
    ): GameCompatibilityStatus {
        if (host.isX86_64) {
            return if (response.gpuPlayableCount > 0) {
                GameCompatibilityStatus.GPU_COMPATIBLE
            } else {
                GameCompatibilityStatus.UNKNOWN
            }
        }
        return when {
            response.isNotWorking -> GameCompatibilityStatus.NOT_COMPATIBLE
            !response.hasBeenTried -> GameCompatibilityStatus.UNKNOWN
            response.gpuPlayableCount > 0 -> GameCompatibilityStatus.GPU_COMPATIBLE
            response.totalPlayableCount > 0 -> GameCompatibilityStatus.COMPATIBLE
            else -> GameCompatibilityStatus.UNKNOWN
        }
    }

    fun getCompatibilityMessageFromResponse(context: Context, response: GameCompatibilityResponse): CompatibilityMessage {
        val host = HostCpu.current()
        return when {
            response.gpuPlayableCount > 0 ->
                CompatibilityMessage(context.getString(R.string.best_config_exact_gpu_match), Color.Green)
            !host.isX86_64 && response.totalPlayableCount > 0 ->
                CompatibilityMessage(context.getString(R.string.best_config_fallback_match), Color.Yellow)
            !host.isX86_64 && response.isNotWorking ->
                CompatibilityMessage(context.getString(R.string.library_not_compatible), Color.Red)
            else ->
                CompatibilityMessage(context.getString(R.string.library_compatibility_unknown), Color.Gray)
        }
    }

    /**
     * Fetches compatibility information for a batch of games.
     * Returns a map of game name to compatibility response, or null on error.
     */
    suspend fun fetchCompatibility(
        gameNames: List<String>,
        gpuName: String
    ): Map<String, GameCompatibilityResponse>? = withContext(Dispatchers.IO) {
        if (gameNames.isEmpty()) {
            return@withContext emptyMap()
        }

        try {
            val requestBody = JSONObject().apply {
                put("gameNames", org.json.JSONArray(gameNames))
                put("gpuName", gpuName)
                // Modern build can't run glibc containers — server should weight
                // compatibility responses against bionic-only configs when true.
                put("modernBuild", BuildConfig.MODERN_ANDROID)
                put("arch", if (HostCpu.current().isX86_64) "x86_64" else "arm64")
            }

            val attestation = KeyAttestationHelper.getAttestationFields("https://api.gamenative.app")
            if (attestation != null) {
                requestBody.put("nonce", attestation.first)
                requestBody.put("attestationChain", org.json.JSONArray(attestation.second))
            }

            val mediaType = "application/json".toMediaType()
            val bodyString = requestBody.toString()
            val body = bodyString.toRequestBody(mediaType)

            val integrityToken = PlayIntegrity.requestToken(bodyString.toByteArray())

            val requestBuilder = Request.Builder()
                .url(API_BASE_URL)
                .post(body)
                .header("Content-Type", "application/json")
            if (integrityToken != null) {
                requestBuilder.header("X-Integrity-Token", integrityToken)
            }
            val request = requestBuilder.build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag("GameCompatibilityService")
                        .w("API request failed - HTTP ${response.code}")
                    return@withContext null
                }

                val responseBody = response.body?.string() ?: return@withContext null
                val jsonResponse = JSONObject(responseBody)

                val result = mutableMapOf<String, GameCompatibilityResponse>()
                val keys = jsonResponse.keys()

                while (keys.hasNext()) {
                    val gameName = keys.next()
                    val gameData = jsonResponse.getJSONObject(gameName)

                    val compatibilityResponse = GameCompatibilityResponse(
                        gameName = gameName,
                        totalPlayableCount = gameData.optInt("totalPlayableCount", 0),
                        gpuPlayableCount = gameData.optInt("gpuPlayableCount", 0),
                        avgRating = gameData.optDouble("avgRating", 0.0).toFloat(),
                        hasBeenTried = gameData.optBoolean("hasBeenTried", false),
                        isNotWorking = gameData.optBoolean("isNotWorking", false)
                    )

                    result[gameName] = compatibilityResponse
                }

                Timber.tag("GameCompatibilityService")
                    .d("Fetched compatibility for ${result.size} games")
                result
            }
        } catch (e: Exception) {
            Timber.tag("GameCompatibilityService")
                .e(e, "Error fetching compatibility data: ${e.message}")
            null
        }
    }
}

