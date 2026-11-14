package tv.vega.tv.data.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
)

/**
 * Continue-watching capped at 30 (same as web) to bound memory on weak TVs.
 */
class WatchHistoryStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val itemsKey = stringPreferencesKey("items_json")

    /**
     * A player exit or a stale sync can finish its history write just after
     * the viewer removed the card. Remember both identities briefly so that
     * late upsert cannot resurrect the row they just deleted.
     */
    private val deletedKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun isRecentlyDeleted(entry: WatchEntry): Boolean {
        val now = System.currentTimeMillis()
        return listOf(entry.mediaId, entry.link).any { key ->
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
            prefs[itemsKey]?.let { runCatching { json.decodeFromString<List<WatchEntry>>(it) }.getOrNull() }
                ?: emptyList()
        }

    suspend fun upsert(entry: WatchEntry) {
        if (isRecentlyDeleted(entry)) return
        context.historyStore.edit { prefs ->
            // Re-check inside the serialized transform: a delete can land
            // between the check above and this edit being applied.
            if (isRecentlyDeleted(entry)) return@edit
            val cur = prefs[itemsKey]?.let {
                runCatching { json.decodeFromString<MutableList<WatchEntry>>(it) }.getOrNull()
            } ?: mutableListOf()
            // Remove existing entries that match by mediaId OR by link+episodeIndex
            // This prevents duplicates when resuming from Continue Watching
            cur.removeAll { 
                it.mediaId == entry.mediaId || 
                (it.link.isNotBlank() && it.link == entry.link && it.episodeIndex == entry.episodeIndex)
            }
            cur.add(0, entry)
            prefs[itemsKey] = json.encodeToString(cur.take(30))
        }
    }

    suspend fun remove(mediaId: String, link: String = "") {
        val now = System.currentTimeMillis()
        if (mediaId.isNotBlank()) deletedKeys[mediaId] = now
        if (link.isNotBlank()) deletedKeys[link] = now
        context.historyStore.edit { prefs ->
            val cur = prefs[itemsKey]?.let {
                runCatching { json.decodeFromString<MutableList<WatchEntry>>(it) }.getOrNull()
            } ?: mutableListOf()
            cur.removeAll { it.mediaId == mediaId || (link.isNotBlank() && it.link == link) }
            prefs[itemsKey] = json.encodeToString(cur)
        }
    }

    companion object {
        private const val DELETE_WINDOW_MS = 8_000L

        fun progressFraction(e: WatchEntry): Float =
            if (e.durationMs > 0) (e.positionMs.toFloat() / e.durationMs).coerceIn(0f, 1f) else 0f

        fun isWatched(e: WatchEntry): Boolean = progressFraction(e) > 0.85f
    }
}
