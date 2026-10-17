package tv.vega.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.vega.tv.data.history.WatchHistoryStore
import tv.vega.tv.data.providers.ProviderManager
import tv.vega.tv.data.repo.VegaRepository
import tv.vega.tv.data.settings.AppSettings
import tv.vega.tv.data.sync.SyncService
import tv.vega.tv.data.watchlist.WatchlistStore

object ServiceLocator {
    lateinit var repository: VegaRepository
        private set
    lateinit var history: WatchHistoryStore
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var providers: ProviderManager
        private set
    lateinit var watchlist: WatchlistStore
        private set
    lateinit var sync: SyncService
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return
        val app = context.applicationContext
        history = WatchHistoryStore(app)
        settings = AppSettings(app)
        providers = ProviderManager(app)
        watchlist = WatchlistStore(app)
        sync = SyncService(app).also { s ->
            s.history = { history.items.first() }
            s.watchlist = { watchlist.items.first() }
            s.applyHistory = { list ->
                val cur = history.items.first().associateBy { it.mediaId }
                // Oldest first so newest ends on top (upsert is MRU).
                list.sortedBy { it.updatedAt }.forEach { r ->
                    val local = cur[r.mediaId]
                    if (local == null || r.updatedAt > local.updatedAt) history.upsert(r)
                }
            }
            s.applyWatchlist = { watchlist.replaceAll(it) }
        }
        repository = VegaRepository(app, providers)
    }

    /** Fire-and-forget sync triggers (Home focus, post-playback, edits). */
    fun syncSoon() {
        if (!::sync.isInitialized) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { sync.publish() }
        }
    }

    fun syncNow() {
        if (!::sync.isInitialized) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { sync.syncFromSharedFolder() }
        }
    }
}
