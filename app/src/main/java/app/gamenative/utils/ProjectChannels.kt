package app.gamenative.utils

import android.net.Uri
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Where users are sent for source, help, issue reports and updates.
 *
 * A build with a PROJECT_REPO (the x86_64 port) uses that GitHub repository for all of them.
 * Upstream's Discord, donation pitches and support relay serve a different build, so they stay
 * in place for upstream flavors and are skipped here.
 */
object ProjectChannels {
    private const val UPSTREAM_SUPPORT_URL = "https://discord.gg/2hKv4VfZfE"
    private const val ISSUE_TEMPLATE = "bug_report.yml"

    val isOwnProject: Boolean
        get() = BuildConfig.PROJECT_REPO.isNotEmpty()

    val sourceUrl: String
        get() = if (isOwnProject) "https://github.com/${BuildConfig.PROJECT_REPO}" else Constants.Misc.GITHUB_LINK

    /** Help and support entry points: the issue chooser, or upstream's Discord. */
    val supportUrl: String
        get() = if (isOwnProject) "$sourceUrl/issues/new/choose" else UPSTREAM_SUPPORT_URL

    val releasesUrl: String
        get() = "$sourceUrl/releases/latest"

    /** Upstream's tip, membership and paywall prompts. */
    val showsDonations: Boolean
        get() = !isOwnProject

    /**
     * A new issue on the bug report form, filled in with what the app already knows. The user
     * still submits it from their own GitHub account, so replies reach a real person.
     */
    fun newIssueUrl(issueText: String, gameName: String, extraInfo: String = ""): String {
        val title = if (gameName.isNotBlank()) "[Bug]: $gameName" else "[Bug]: "
        val fields = linkedMapOf(
            "title" to title,
            "what-happened" to issueText,
            "game" to gameName,
            "gnx64-version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            "details" to extraInfo,
        )
        val query = fields.filterValues { it.isNotBlank() }
            .entries.joinToString("&") { (key, value) -> "$key=${Uri.encode(value)}" }
        return "$sourceUrl/issues/new?template=$ISSUE_TEMPLATE&$query"
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val VERSION_CODE_IN_NOTES = Regex("""\(code (\d+)\)""")

    /**
     * The latest GitHub release, when it is newer than this build. CI writes the release's
     * versionCode into its notes as "(code N)", which is what is compared.
     */
    suspend fun checkForRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!isOwnProject) return@withContext null
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.PROJECT_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Release check failed: HTTP ${response.code}")
                    return@withContext null
                }
                val release = JSONObject(response.body?.string() ?: return@withContext null)
                val code = VERSION_CODE_IN_NOTES.find(release.optString("body"))
                    ?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
                val tag = release.optString("tag_name")
                Timber.i("Release check: latest %s (code %d), running %d", tag, code, BuildConfig.VERSION_CODE)
                if (code <= BuildConfig.VERSION_CODE) return@withContext null
                UpdateInfo(
                    updateAvailable = true,
                    versionCode = code,
                    versionName = tag.removePrefix("v"),
                    downloadUrl = release.optString("html_url", releasesUrl),
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Release check failed")
            null
        }
    }
}
