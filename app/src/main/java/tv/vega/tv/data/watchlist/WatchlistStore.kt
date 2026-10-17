package tv.vega.tv.data.watchlist

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.watchlistStore by preferencesDataStore("watchlist")

@Serializable
data class WatchItem(
    val title: String = "",
    val poster: String = "",
    val link: String = "",
    val provider: String = "",
    val updatedAt: Long = 0L,
)

/** Port of web WatchListStorage: dedup by link, MRU order. */
class WatchlistStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("watchlist_json")

    val items: Flow<List<WatchItem>> =
        context.watchlistStore.data.map { p ->
            p[key]?.let { runCatching { json.decodeFromString<List<WatchItem>>(it) }.getOrNull() }
                ?: emptyList()
        }

    suspend fun isIn(link: String): Boolean = items.first().any { it.link == link }

    suspend fun toggle(title: String, poster: String, link: String, provider: String): Boolean {
        var added = false
        context.watchlistStore.edit { p ->
            val cur = p[key]?.let { runCatching { json.decodeFromString<MutableList<WatchItem>>(it) }.getOrNull() } ?: mutableListOf()
            if (cur.any { it.link == link }) {
                cur.removeAll { it.link == link }
                added = false
            } else {
                cur.add(0, WatchItem(title, poster, link, provider, System.currentTimeMillis()))
                added = true
            }
            p[key] = json.encodeToString(cur)
        }
        return added
    }

    suspend fun remove(link: String) {
        context.watchlistStore.edit { p ->
            val cur = p[key]?.let { runCatching { json.decodeFromString<MutableList<WatchItem>>(it) }.getOrNull() } ?: mutableListOf()
            cur.removeAll { it.link == link }
            p[key] = json.encodeToString(cur)
        }
    }

    suspend fun replaceAll(list: List<WatchItem>) {
        context.watchlistStore.edit { it[key] = json.encodeToString(list) }
    }
}
