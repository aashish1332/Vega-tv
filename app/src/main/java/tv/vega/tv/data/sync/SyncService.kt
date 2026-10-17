package tv.vega.tv.data.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tv.vega.tv.data.history.WatchEntry
import tv.vega.tv.data.watchlist.WatchItem
import java.io.File
import java.util.UUID

private val Context.syncStore by preferencesDataStore("sync")

/**
 * Shared-folder sync port (same .vega-sync/vega-<deviceId>.json format as
 * the phone app): history + watchlist merge LWW with tombstone
 * suppression. Weak-TV: all IO off main, single debounce publisher.
 */
class SyncService(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val app = context.applicationContext
    private val deviceKey = stringPreferencesKey("device_id")
    private val revKey = longPreferencesKey("revision")
    private val rootKey = stringPreferencesKey("shared_root_uri")
    private val tombsKey = stringPreferencesKey("tombstones_json")

    // Injected lazily to avoid cycles (set from ServiceLocator.init).
    var history: (suspend () -> List<WatchEntry>)? = null
    var watchlist: (suspend () -> List<WatchItem>)? = null
    var applyHistory: (suspend (List<WatchEntry>) -> Unit)? = null
    var applyWatchlist: (suspend (List<WatchItem>) -> Unit)? = null

    suspend fun deviceId(): String {
        val cur = app.syncStore.data.map { it[deviceKey] }.first()
        if (!cur.isNullOrBlank()) return cur
        val fresh = UUID.randomUUID().toString()
        app.syncStore.edit { it[deviceKey] = fresh }
        return fresh
    }

    suspend fun sharedRoot(): String? =
        app.syncStore.data.map { it[rootKey] }.first()

    suspend fun setSharedRoot(uri: String?) {
        app.syncStore.edit { if (uri == null) it.remove(rootKey) else it[rootKey] = uri }
    }

    private suspend fun tombs(): MutableList<Tombstone> =
        app.syncStore.data.map { p ->
            p[tombsKey]?.let { runCatching { json.decodeFromString<MutableList<Tombstone>>(it) }.getOrNull() } ?: mutableListOf()
        }.first()

    suspend fun addTombstone(t: Tombstone) {
        val cur = tombs()
        cur.removeAll { it.kind == t.kind && (it.id == t.id || (it.mediaKey.isNotBlank() && it.mediaKey == t.mediaKey)) }
        cur.add(t)
        app.syncStore.edit { it[tombsKey] = json.encodeToString(cur.takeLast(200)) }
    }

    private fun syncDir(): File = File(app.filesDir, SYNC_DIR).apply { mkdirs() }

    private suspend fun bumpRevision(): Long {
        var r = 0L
        app.syncStore.edit {
            r = (it[revKey] ?: 0L) + 1
            it[revKey] = r
        }
        return r
    }

    /** Build + publish our manifest (debounced by callers). */
    suspend fun publish() = withContext(Dispatchers.IO) {
        val did = deviceId()
        val h = history?.invoke().orEmpty().take(50).map {
            SyncedHistory(
                id = it.mediaId, title = it.title, poster = it.posterUrl, provider = it.provider,
                link = it.link, duration = it.durationMs, currentTime = it.positionMs,
                episodeTitle = it.episodeTitle, episode = it.episodeIndex, updatedAt = it.updatedAt,
            )
        }
        val w = watchlist?.invoke().orEmpty().map {
            SyncedWatchItem(it.title, it.poster, it.link, it.provider, it.updatedAt)
        }
        val m = SyncManifest(
            deviceId = did, revision = bumpRevision(), generatedAt = System.currentTimeMillis(),
            history = h, watchlist = w, tombstones = tombs(),
        )
        File(syncDir(), "vega-$did.json").writeText(json.encodeToString(m))
    }

    /** Read all manifests (local + shared SAF root) and merge. */
    suspend fun syncFromSharedFolder() = withContext(Dispatchers.IO) {
        val files = mutableListOf<File>()
        syncDir().listFiles { f -> f.name.startsWith("vega-") && f.extension == "json" }?.let { files.addAll(it) }
        // Shared SAF root manifests are copied locally first (cheap, small JSON).
        val root = sharedRoot()
        if (!root.isNullOrBlank()) {
            runCatching {
                val tree = DocumentFile.fromTreeUri(app, android.net.Uri.parse(root)) ?: return@runCatching
                val dir = tree.findFile(SYNC_DIR) ?: return@runCatching
                dir.listFiles().filter { it.name?.startsWith("vega-") == true }.forEach { doc ->
                    runCatching {
                        app.contentResolver.openInputStream(doc.uri)?.use { ins ->
                            val tmp = File(syncDir(), doc.name ?: return@forEach)
                            // Keep newest per device file by lastModified.
                            if (!tmp.exists() || (doc.lastModified() > tmp.lastModified())) {
                                tmp.outputStream().use { ins.copyTo(it) }
                            }
                        }
                    }
                }
            }
        }
        val manifests = files.mapNotNull { runCatching { json.decodeFromString<SyncManifest>(it.readText()) }.getOrNull() }
            .filter { it.schemaVersion == SYNC_SCHEMA }
        if (manifests.isEmpty()) return@withContext

        val tombs = manifests.flatMap { it.tombstones } + tombs()
        fun suppressed(kind: String, id: String, key: String, updated: Long): Boolean =
            tombs.any { it.kind == kind && (it.id == id || (key.isNotBlank() && it.mediaKey == key)) && it.deletedAt >= updated }

        // History: top-30 by recency across devices → continue watching.
        val hist = manifests.flatMap { it.history }
            .filter { !suppressed("history", it.id, "", it.updatedAt) }
            .sortedByDescending { it.updatedAt }.take(30)
        if (hist.isNotEmpty()) {
            applyHistory?.invoke(
                hist.map {
                    WatchEntry(
                        mediaId = it.id.ifBlank { it.link }, title = it.title, episodeTitle = it.episodeTitle,
                        posterUrl = it.poster, positionMs = it.currentTime, durationMs = it.duration,
                        updatedAt = it.updatedAt, link = it.link, provider = it.provider, episodeIndex = it.episode,
                    )
                },
            )
        }

        // Watchlist: replace with newest full list (same as web applyRemoteWatchList).
        val newestWl = manifests.maxByOrNull { it.revision }?.watchlist
        if (newestWl != null) {
            val filtered = newestWl.filter { !suppressed("watchlist", it.link, "", it.updatedAt) }
            applyWatchlist?.invoke(filtered.map { WatchItem(it.title, it.poster, it.link, it.provider, it.updatedAt) })
        }

        // Republish merged view.
        publish()
    }
}
