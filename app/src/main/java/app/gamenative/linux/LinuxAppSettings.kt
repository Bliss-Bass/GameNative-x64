package app.gamenative.linux

import android.content.Context
import app.gamenative.PrefManager
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/**
 * Settings a single Linux application overrides, keyed like its session ([LinuxSessions.keyFor]).
 *
 * Only what differs from the global preferences is stored, so an application with no entry here
 * follows those preferences as they change.
 */
object LinuxAppSettings {

    private const val FILE = "linux/app-settings.json"
    private const val SCALE_PERCENT = "scalePercent"

    private var cache: MutableMap<String, Int>? = null

    /** The scale [key] overrides the global one with, or null when it follows it. */
    @Synchronized
    fun scaleOverride(context: Context, key: String): Int? = load(context)[key]

    /** The scale [key] actually runs at. */
    fun scalePercent(context: Context, key: String): Int =
        scaleOverride(context, key) ?: PrefManager.linuxUiScalePercent

    /** Sets or, with null, clears [key]'s scale override. */
    @Synchronized
    fun setScaleOverride(context: Context, key: String, percent: Int?) {
        val scales = load(context)
        if (percent == null) scales.remove(key) else scales[key] = percent
        save(context, scales)
    }

    private fun load(context: Context): MutableMap<String, Int> {
        cache?.let { return it }
        val file = File(context.filesDir, FILE)
        val scales = mutableMapOf<String, Int>()
        runCatching {
            if (!file.isFile) return@runCatching
            val root = JSONObject(file.readText())
            for (key in root.keys()) {
                val app = root.optJSONObject(key) ?: continue
                if (app.has(SCALE_PERCENT)) scales[key] = app.getInt(SCALE_PERCENT)
            }
        }.onFailure { Timber.w(it, "[LinuxAppSettings]: could not read %s", file) }
        return scales.also { cache = it }
    }

    private fun save(context: Context, scales: Map<String, Int>) {
        val root = JSONObject()
        scales.forEach { (key, percent) -> root.put(key, JSONObject().put(SCALE_PERCENT, percent)) }
        val file = File(context.filesDir, FILE)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString(2))
            if (!tmp.renameTo(file)) throw java.io.IOException("rename failed")
        }.onFailure { Timber.w(it, "[LinuxAppSettings]: could not write %s", file) }
    }
}
