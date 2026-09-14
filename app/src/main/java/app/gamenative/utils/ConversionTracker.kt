package app.gamenative.utils

object ConversionTracker {

    fun featuredConversion(campaignId: String, actionType: String, appId: Int?, source: String) {
        val properties = mutableMapOf<String, Any>(
            "campaign_id" to campaignId,
            "action_type" to actionType,
            "source" to source,
        )
        appId?.let { properties["app_id"] = it }
        Telemetry.capture(event = "featured_conversion", properties = properties)
    }

    /** Fired when the booting splash hides, with how long the sponsor card was on screen. */
    fun bootAdShown(campaignId: String, dwellSeconds: Long) {
        Telemetry.capture(
            event = "boot_ad_shown",
            properties = mapOf(
                "campaign_id" to campaignId,
                "dwell_seconds" to dwellSeconds,
            ),
        )
    }
}
