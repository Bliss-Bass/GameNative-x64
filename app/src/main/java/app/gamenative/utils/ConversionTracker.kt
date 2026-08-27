package app.gamenative.utils

object ConversionTracker {

    fun featuredConversion(campaignId: String, actionType: String, appId: Int?, source: String) {
        if (!Telemetry.usageAnalyticsEnabled) return

        val properties = mutableMapOf<String, Any>(
            "campaign_id" to campaignId,
            "action_type" to actionType,
            "source" to source,
        )
        appId?.let { properties["app_id"] = it }

        Telemetry.capture(event = "featured_conversion", properties = properties)
    }
}
