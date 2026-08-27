package app.gamenative.utils

import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import com.posthog.PostHog

/**
 * Gates GameNative remote telemetry for Bliss ax86 port builds.
 *
 * Set [BuildConfig.BLISS_PORT_DEBUG] on port flavors (modernX64). When that flag is
 * present, PostHog, game-run API submissions, and automatic exit-feedback prompts are
 * skipped. Upstream release flavors leave the flag false and behave normally.
 */
object Telemetry {

    /** True on ax86 port builds; false on stock GameNative product flavors. */
    val isPortDebugBuild: Boolean
        get() = BuildConfig.BLISS_PORT_DEBUG

    /** PostHog events — allowed when not a port build and user has not opted out. */
    val usageAnalyticsEnabled: Boolean
        get() = !isPortDebugBuild && PrefManager.usageAnalyticsEnabled

    /** GameNative game-run / compatibility API — allowed when not a port build. */
    val compatibilityReportsEnabled: Boolean
        get() = !isPortDebugBuild

    fun capture(event: String, properties: Map<String, Any?>? = null) {
        if (!usageAnalyticsEnabled) return
        if (properties != null) {
            PostHog.capture(event = event, properties = properties)
        } else {
            PostHog.capture(event = event)
        }
    }

    inline fun ifUsageAnalyticsEnabled(block: () -> Unit) {
        if (usageAnalyticsEnabled) block()
    }

    inline fun ifCompatibilityReportsEnabled(block: () -> Unit) {
        if (compatibilityReportsEnabled) block()
    }
}
