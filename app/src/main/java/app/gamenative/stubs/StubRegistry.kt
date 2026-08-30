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
 * The record is also what makes adding an entry a decision the user makes once -- something we
 * have no record of is something they never asked for, and reconciliation leaves it alone.
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

    fun all(context: Context): List<Record> = load(context).values.toList()

    fun of(context: Context, entryId: String): Record? = load(context)[entryId]

    @Synchronized
    fun record(context: Context, record: Record) {
        save(context, load(context) + (record.entryId to record))
    }

    @Synchronized
    fun forget(context: Context, entryId: String) {
        val current = load(context)
        if (entryId !in current) return
        save(context, current - entryId)
    }

    @Synchronized
    fun clear(context: Context) = save(context, emptyMap())

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

    private fun file(context: Context) = File(context.filesDir, path)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Entries for Linux applications. The path predates the split, and devices hold records. */
        val linux = StubRegistry("linux/stubs.json")

        /** Entries for installed games. */
        val games = StubRegistry("stubs/games.json")
    }
}
