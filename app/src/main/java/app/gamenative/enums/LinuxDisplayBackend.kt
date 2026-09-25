package app.gamenative.enums

import app.gamenative.R

/**
 * Which X transport hosts freeform Linux apps.
 *
 * Android X (Winlator + DRI3) is the default: lower latency, no RFB encode. Xtigervnc is the
 * older RFB path kept as a compatibility fallback when Android X misbehaves for an app.
 */
enum class LinuxDisplayBackend(
    val key: String,
    val titleRes: Int,
    val summaryRes: Int,
) {
    ANDROID_X(
        key = "android_x",
        titleRes = R.string.linux_display_backend_android_x,
        summaryRes = R.string.linux_display_backend_android_x_summary,
    ),
    XTIGERVNC(
        key = "xtigervnc",
        titleRes = R.string.linux_display_backend_xtigervnc,
        summaryRes = R.string.linux_display_backend_xtigervnc_summary,
    ),
    ;

    companion object {
        val DEFAULT = ANDROID_X

        fun fromKey(key: String?): LinuxDisplayBackend =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
