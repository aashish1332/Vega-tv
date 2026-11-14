package tv.vega.tv.data.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tv.vega.tv.data.model.VegaStream
import tv.vega.tv.data.model.identityKey
import tv.vega.tv.player.QualityLink

private val Context.historyStore by preferencesDataStore("continue_watching")

@Serializable
data class WatchEntry(
    val mediaId: String = "",
    val title: String = "",
    val episodeTitle: String = "",
    val posterUrl: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = 0L,
    // Rebuild keys (same as web continueWatching item identity).
    val link: String = "",
    val provider: String = "",
    val episodeIndex: Int = 0,
    // "movie"/"tv"/"anime" from the provider. Continue Watching can start the
    // saved link immediately with the right stream contract, without a meta
    // round trip before the player appears.
    val mediaType: String = "",
    /**
     * Stable identity of the title/episode. Server and quality changes rewrite
     * the playable link, so the link itself can never be the row identity:
     * using it made every re-watch create another Continue Watching card.
     */
    val historyKey: String = "",
    /**
     * Every server/quality the last resolve returned. Persisted so returning
     * to a title still offers the whole list instead of only whichever server
     * happened to be playing when the viewer left.
     */
    val streams: List<VegaStream> = emptyList(),
    /** Movie quality cards (whole alternate links), restored with the entry. */
    val qualityLinks: List<QualityLink> = emptyList(),
    /**
     * Server+quality that was actually playing. Fresh resolve links rotate,
     * so the label pair — not the URL — is what lets playback continue on the
     * same server after a resume.
     */
    val lastStreamKey: String = "",
)

/**
 * Continue-watching capped at 30 (same as web) to bound memory on weak TVs.
 */
class WatchHistoryStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val itemsKey = stringPreferencesKey("items_json")

    /**
     * A player exit or a stale sync can finish its history write just after
     * the viewer removed the card. Remember every identity briefly so that a
     * late upsert cannot resurrect the row they just deleted.
     */
    private val deletedKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun isRecentlyDeleted(entry: WatchEntry, historyKey: String): Boolean {
        val now = System.currentTimeMillis()
        return sequenceOf(entry.mediaId, entry.link, historyKey).any { key ->
            if (key.isBlank()) return@any false
            val at = deletedKeys[key] ?: return@any false
            if (now - at <= DELETE_WINDOW_MS) true else {
                deletedKeys.remove(key, at)
                false
            }
        }
    }

    val items: Flow<List<WatchEntry>> =
        context.historyStore.data.map { prefs ->
            val raw = prefs[itemsKey]?.let {
                runCatching { json.decodeFromString<List<WatchEntry>>(it) }.getOrNull()
            } ?: emptyList()
            // Rows written before [historyKey] existed can contain several
            // cards for one title (one per server/quality). Collapse them on
            // read; the next upsert persists the cleaned list.
            dedupe(raw)
        }

    suspend fun upsert(entry: WatchEntry) {
        val key = entry.historyKey.ifBlank { keyOf(entry) }
        if (isRecentlyDeleted(entry, key)) return
        context.historyStore.edit { prefs ->
            // Re-check inside the serialized transform: a delete can land
            // between the check above and this edit being applied.
            if (isRecentlyDeleted(entry, key)) return@edit
            val cur = prefs[itemsKey]?.let {
                runCatching { json.decodeFromString<MutableList<WatchEntry>>(it) }.getOrNull()
            } ?: mutableListOf()
            // One row per title/episode, even if the playable link changed.
            val same = cur.filter {
                keyOf(it) == key ||
                    it.mediaId == entry.mediaId ||
                    (it.link.isNotBlank() && it.link == entry.link &&
                        it.episodeIndex == entry.episodeIndex)
            }
            // The new resolve is authoritative for links; an empty list (for
            // example a save from an old build) must not erase what we have.
            val streams = LinkedHashMap<String, VegaStream>()
            entry.streams.forEach { streams[it.identityKey()] = it }
            same.forEach { old ->
                old.streams.forEach { streams.putIfAbsent(it.identityKey(), it) }
            }
            val qualityLinks = entry.qualityLinks.ifEmpty {
                same.firstOrNull { it.qualityLinks.isNotEmpty() }?.qualityLinks.orEmpty()
            }
            cur.removeAll(same)
            cur.add(
                0,
                entry.copy(
                    historyKey = key,
                    streams = streams.values.toList(),
                    qualityLinks = qualityLinks,
                ),
            )
            prefs[itemsKey] = json.encodeToString(cur.take(MAX_ENTRIES))
        }
    }

    suspend fun remove(mediaId: String, link: String = "", historyKey: String = "") {
        val now = System.currentTimeMillis()
        if (mediaId.isNotBlank()) deletedKeys[mediaId] = now
        if (link.isNotBlank()) deletedKeys[link] = now
        if (historyKey.isNotBlank()) deletedKeys[historyKey] = now
        context.historyStore.edit { prefs ->
            val cur = prefs[itemsKey]?.let {
                runCatching { json.decodeFromString<MutableList<WatchEntry>>(it) }.getOrNull()
            } ?: mutableListOf()
            cur.removeAll {
                it.mediaId == mediaId ||
                    (link.isNotBlank() && it.link == link) ||
                    (historyKey.isNotBlank() && keyOf(it) == historyKey)
            }
            prefs[itemsKey] = json.encodeToString(cur)
        }
    }

    /**
     * Newest row wins; older duplicates only contribute stream/quality entries
     * the newest one does not already know about. Order stays MRU.
     */
    private fun dedupe(raw: List<WatchEntry>): List<WatchEntry> {
        if (raw.size < 2) return raw.map { withIdentity(it) }
        val groups = LinkedHashMap<String, MutableList<WatchEntry>>()
        raw.sortedByDescending { it.updatedAt }.forEach { e ->
            groups.getOrPut(keyOf(e)) { mutableListOf() }.add(e)
        }
        return groups.map { (key, rows) ->
            val base = rows.first()
            val streams = LinkedHashMap<String, VegaStream>()
            rows.forEach { row ->
                row.streams.forEach { streams.putIfAbsent(it.identityKey(), it) }
            }
            val qualityLinks = rows.firstOrNull { it.qualityLinks.isNotEmpty() }
                ?.qualityLinks.orEmpty()
            base.copy(
                historyKey = key,
                streams = streams.values.toList(),
                qualityLinks = qualityLinks,
            )
        }.take(MAX_ENTRIES)
    }

    private fun withIdentity(e: WatchEntry): WatchEntry =
        if (e.historyKey.isNotBlank()) e else e.copy(historyKey = keyOf(e))

    companion object {
        private const val DELETE_WINDOW_MS = 8_000L
        private const val MAX_ENTRIES = 30

        /**
         * Stable title/episode identity. Deliberately excludes the playable
         * link: providers rotate URLs per session, mirrors differ per server,
         * and movie quality cards are separate links for the same film.
         *
         * The media type is excluded too: it is blank for rows saved before
         * metadata resolved, and including it would keep an old row and a new
         * one for the same episode from merging.
         */
        fun keyOf(
            provider: String,
            title: String,
            episodeTitle: String,
            episodeIndex: Int,
        ): String = listOf(
            norm(provider),
            norm(title),
            norm(episodeTitle),
            episodeIndex.toString(),
        ).joinToString("|")

        fun keyOf(e: WatchEntry): String = e.historyKey.ifBlank {
            keyOf(e.provider, e.title, e.episodeTitle, e.episodeIndex)
        }

        private fun norm(value: String): String =
            value.lowercase().replace(WHITESPACE, " ").trim()

        private val WHITESPACE = Regex("""\s+""")

        fun progressFraction(e: WatchEntry): Float =
            if (e.durationMs > 0) (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) else 0f

        fun isWatched(e: WatchEntry): Boolean = progressFraction(e) > 0.85f
    }
}
