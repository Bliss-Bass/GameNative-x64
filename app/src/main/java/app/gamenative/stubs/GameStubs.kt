package app.gamenative.stubs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import app.gamenative.data.GameSource
import app.gamenative.utils.createAdaptiveIconBitmap
import app.gamenative.utils.loadGameArtwork
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import timber.log.Timber

/**
 * What an installed game's all-apps entry is made of.
 *
 * The entry launches the game the same way a pinned shortcut does, through LAUNCH_GAME, so what
 * differs from the Linux side is only what the stub carries: the game's id and the store it came
 * from instead of a command, and artwork fetched over the network instead of an icon read out of
 * the userland.
 */
object GameStubs {

    /** Read by the stub's LaunchActivity, which turns the pair back into a LAUNCH_GAME intent. */
    private const val META_APP_ID = "app.gamenative.stub.APP_ID"
    private const val META_GAME_SOURCE = "app.gamenative.stub.GAME_SOURCE"

    /**
     * What a game's entry is filed under.
     *
     * Scoped by store: ids are only unique within one, and two stores can both hold a game 570.
     */
    fun entryIdFor(gameId: Int, source: GameSource): String = "${source.name.lowercase()}_$gameId"

    fun packageNameFor(gameId: Int, source: GameSource): String =
        Stubs.packageNameFor(entryIdFor(gameId, source))

    fun isSupported(context: Context): Boolean = Stubs.isSupported(context)

    /**
     * What the stub was built from, so a later pass can tell whether it is still right.
     *
     * The artwork is summarised by where it came from rather than by its bytes: fetching every
     * game's image to hash it would put the reconcile pass on the network, and a store that
     * changes the artwork changes the address. Every candidate counts, not just the one used, so
     * that a game which had nothing and later gains real artwork is noticed.
     */
    fun contentFingerprint(label: String, artwork: List<String>): String {
        val summary = (listOf(label) + artwork).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(summary.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Gives a game an entry in the all-apps list, and remembers having done so.
     *
     * Also the path a renamed game or new artwork takes: the version code rises above the recorded
     * one, which is what lets the install replace the stub already there.
     */
    suspend fun add(
        context: Context,
        gameId: Int,
        source: GameSource,
        label: String,
        artwork: List<String>,
    ): Result<Unit> {
        val entryId = entryIdFor(gameId, source)
        val packageName = packageNameFor(gameId, source)
        val versionCode = (StubRegistry.games.of(context, entryId)?.versionCode ?: 0) + 1

        return runCatching {
            Stubs.build(
                context = context,
                packageName = packageName,
                label = label,
                versionCode = versionCode,
                metadata = mapOf(
                    META_APP_ID to gameId.toString(),
                    META_GAME_SOURCE to source.name,
                ),
                iconPng = iconPng(context, label, artwork),
            ).getOrThrow()
        }
            .mapCatching { apk -> Stubs.install(context, apk).getOrThrow() }
            .onSuccess {
                StubRegistry.games.record(
                    context,
                    StubRegistry.Record(
                        entryId = entryId,
                        packageName = packageName,
                        versionCode = versionCode,
                        fingerprint = contentFingerprint(label, artwork),
                    ),
                )
            }
    }

    /**
     * Brings the entry published for one game back in step, if there is one.
     *
     * Per game rather than over the whole library: this runs where a game's name, artwork and
     * installed state are already in hand, so it costs nothing to check, and a library-wide pass
     * would have to resolve every game to answer the same question.
     *
     * Only ever takes back or repairs, never adds. Publishing is the user's decision, and a game
     * whose entry they removed should not have one reappear because it is still installed.
     */
    suspend fun reconcile(
        context: Context,
        gameId: Int,
        source: GameSource,
        installed: Boolean,
        label: String,
        artwork: List<String>,
    ) {
        val entryId = entryIdFor(gameId, source)
        val record = StubRegistry.games.of(context, entryId) ?: return

        runCatching {
            // Removed by the user in Settings. Their decision stands, and forgetting it here is
            // what stops a later change to the game from quietly reinstalling it.
            if (record.packageName !in Stubs.installed(context, listOf(record.packageName))) {
                Timber.i("[GameStubs]: %s is gone from the device, forgetting it", record.packageName)
                StubRegistry.games.forget(context, entryId)
                return
            }

            if (!installed) {
                Timber.i("[GameStubs]: %s is no longer installed, taking its entry back", entryId)
                remove(context, entryId, record.packageName)
                return
            }

            if (contentFingerprint(label, artwork) != record.fingerprint) {
                Timber.i("[GameStubs]: %s changed, republishing its entry", entryId)
                add(context, gameId, source, label, artwork)
                    .onFailure { Timber.w(it, "[GameStubs]: could not republish %s", entryId) }
            }
        }.onFailure { Timber.w(it, "[GameStubs]: could not reconcile %s", entryId) }
    }

    suspend fun isInstalled(context: Context, gameId: Int, source: GameSource): Boolean {
        val packageName = packageNameFor(gameId, source)
        return packageName in Stubs.installed(context, listOf(packageName))
    }

    /**
     * Takes back the entry recorded for [entryId].
     *
     * The record is dropped only once the stub is known to be gone, so that a refused prompt does
     * not leave a package behind that nothing remembers.
     */
    suspend fun remove(context: Context, entryId: String, packageName: String): Result<Unit> =
        Stubs.remove(context, packageName).map { gone ->
            if (gone) StubRegistry.games.forget(context, entryId)
        }

    /**
     * A game's icon as a square PNG, from the first of [artwork] that yields an image.
     *
     * Squared here rather than left to the launcher: store artwork is wide, and an adaptive icon
     * is not something a generated package can declare, so the tile has to be baked in.
     *
     * Tried in order because how well a game is illustrated varies by where it came from: Steam
     * gives an icon on its CDN, a custom game gives a local file extracted from its executable,
     * and Epic, GOG or Amazon may give an empty string. Rather than refuse an entry for the last
     * of those, the name is drawn on a plain tile -- an entry the user can find and launch beats
     * no entry over a missing image.
     */
    private suspend fun iconPng(context: Context, label: String, artwork: List<String>): ByteArray {
        val image = artwork.firstNotNullOfOrNull { loadGameArtwork(context, it) }
        val tile = if (image != null) createAdaptiveIconBitmap(context, image) else lettered(context, label)

        return ByteArrayOutputStream()
            .also { tile.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
    }

    /** A tile carrying [label]'s first letter, for a game with no artwork anywhere. */
    private fun lettered(context: Context, label: String): Bitmap {
        val size = (108f * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        val letter = label.trim().firstOrNull()?.uppercase() ?: "?"

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Offset from the centre by the glyph's own extents, since drawText places the baseline.
        val metrics = paint.fontMetrics
        val baseline = size / 2f - (metrics.ascent + metrics.descent) / 2f

        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(BACKGROUND)
                drawText(letter, size / 2f, baseline, paint)
            }
        }
    }

    /** Behind a lettered tile. Matches the launcher's own placeholder tone rather than shouting. */
    private const val BACKGROUND = 0xFF37474F.toInt()
}
