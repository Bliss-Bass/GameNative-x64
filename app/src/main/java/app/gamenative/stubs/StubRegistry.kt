package app.gamenative.stubs

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * What we have given an entry in Android's all-apps list, and what it looked like at the time.
 *
 * Kept here rather than read back from the installed packages because a stub is invisible to us:
 * the ROM's installer owns it, and Android hides packages an app has not declared an interest in.
 *
 * [suppress] records entry ids the user took out of the drawer (or uninstalled from Settings).
 * Auto-publish skips those so a removed entry does not reappear on the next scan; an explicit
 * "Add to app list" clears the suppression.
 *
 * One instance per kind of entry, in its own file. A single store would mean each reconciler
 * seeing the other's records as entries that no longer exist, and taking them back.
 */
class StubRegistry private constructor(private val path: String) {

    @Serializable
    data class Record(
        /** What this stub stands for: a desktop entry, a game. Unique within one registry. */
        val entryId: String,
        val packageName: String,
        /** Raised on every reinstall, since the package manager rejects an install that does not. */
        val versionCode: Int,
        /** What the stub was built from, so a changed label or icon can be noticed. */
        val fingerprint: String,
    )

    @Volatile
    private var cache: Map<String, Record>? = null

    @Volatile
    private var suppressedCache: Set<String>? = null

    fun all(context: Context): List<Record> = load(context).values.toList()

    fun of(context: Context, entryId: String): Record? = load(context)[entryId]

    fun isSuppressed(context: Context, entryId: String): Boolean = entryId in loadSuppressed(context)

    @Synchronized
    fun record(context: Context, record: Record) {
        // Publishing again means the user (or auto-publish) wants it; clear any prior removal.
        saveSuppressed(context, loadSuppressed(context) - record.entryId)
        save(context, load(context) + (record.entryId to record))
    }

    @Synchronized
    fun forget(context: Context, entryId: String) {
        val current = load(context)
        if (entryId !in current) return
        save(context, current - entryId)
    }

    @Synchronized
    fun suppress(context: Context, entryId: String) {
        saveSuppressed(context, loadSuppressed(context) + entryId)
    }

    @Synchronized
    fun unsuppress(context: Context, entryId: String) {
        val current = loadSuppressed(context)
        if (entryId !in current) return
        saveSuppressed(context, current - entryId)
    }

    @Synchronized
    fun clear(context: Context) {
        save(context, emptyMap())
        saveSuppressed(context, emptySet())
    }

    private fun load(context: Context): Map<String, Record> {
        cache?.let { return it }

        val file = file(context)
        val loaded = if (!file.isFile) {
            emptyMap()
        } else {
            runCatching { json.decodeFromString<List<Record>>(file.readText()).associateBy { it.entryId } }
                .onFailure { Timber.w(it, "[StubRegistry]: could not read %s, starting over", file.name) }
                .getOrDefault(emptyMap())
        }

        cache = loaded
        return loaded
    }

    private fun save(context: Context, records: Map<String, Record>) {
        cache = records

        val file = file(context)
        runCatching {
            file.parentFile?.mkdirs()
            // Through a temporary file: a half-written registry would look like a device with no
            // stubs, and reconciliation would then leave real ones behind for good.
            val temporary = File(file.parentFile, "${file.name}.new")
            temporary.writeText(json.encodeToString(records.values.toList()))
            check(temporary.renameTo(file)) { "could not replace ${file.name}" }
        }.onFailure { Timber.e(it, "[StubRegistry]: could not save %s", file.name) }
    }

    private fun loadSuppressed(context: Context): Set<String> {
        suppressedCache?.let { return it }

        val file = suppressedFile(context)
        val loaded = if (!file.isFile) {
            emptySet()
        } else {
            runCatching { json.decodeFromString<List<String>>(file.readText()).toSet() }
                .onFailure { Timber.w(it, "[StubRegistry]: could not read %s, starting over", file.name) }
                .getOrDefault(emptySet())
        }
        suppressedCache = loaded
        return loaded
    }

    private fun saveSuppressed(context: Context, ids: Set<String>) {
        suppressedCache = ids
        val file = suppressedFile(context)
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.new")
            temporary.writeText(json.encodeToString(ids.toList()))
            check(temporary.renameTo(file)) { "could not replace ${file.name}" }
        }.onFailure { Timber.e(it, "[StubRegistry]: could not save %s", file.name) }
    }

    private fun file(context: Context) = File(context.filesDir, path)

    private fun suppressedFile(context: Context): File {
        val primary = file(context)
        return File(primary.parentFile, "${primary.nameWithoutExtension}-suppressed.json")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Entries for Linux applications. The path predates the split, and devices hold records. */
        val linux = StubRegistry("linux/stubs.json")

        /** Entries for installed games. */
        val games = StubRegistry("stubs/games.json")
    }
}
