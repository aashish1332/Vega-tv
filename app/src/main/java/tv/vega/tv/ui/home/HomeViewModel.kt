package tv.vega.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.vega.tv.AppVisibility
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.history.WatchEntry
import tv.vega.tv.data.model.HomeRow
import tv.vega.tv.data.providers.ProviderExtension
import tv.vega.tv.data.providers.WafCoordinator
import tv.vega.tv.data.repo.CatalogResult
import tv.vega.tv.data.repo.FamilyFilter
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.ui.common.UiState

private const val FEED_REFRESH_INTERVAL_MS = 60_000L

class HomeViewModel : ViewModel() {
    private data class CachedFeed(
        val result: CatalogResult,
        val updatedAt: Long,
    )

    private data class FeedTarget(
        val ref: String,
        val name: String,
    )

    private val _state = MutableStateFlow<UiState<CatalogResult>>(UiState.Loading)
    val state: StateFlow<UiState<CatalogResult>> = _state

    private val _continue = MutableStateFlow<List<WatchEntry>>(emptyList())
    val continueWatching: StateFlow<List<WatchEntry>> = _continue

    /** Installed providers; the sidebar only lists them when >1. */
    private val _installed = MutableStateFlow<List<ProviderExtension>>(emptyList())
    val installedProviders: StateFlow<List<ProviderExtension>> = _installed

    /** "" = the default mixed view, else an "author:value" ref. */
    private val _filter = MutableStateFlow("")
    val providerFilter: StateFlow<String> = _filter

    /** True while a user-triggered refresh is in flight (spinner on the button). */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val feedCache = LinkedHashMap<String, CachedFeed>()
    private var activeRef: String? = null
    private var mixedOrderRefs: List<String> = emptyList()
    private var autoCursor = 0
    private var cacheEpoch = 0
    private var feedsKnown = false
    private var homeVisible = false
    private var pendingFeedLoad = false

    /** The in-flight catalog fetch; at most one may exist. */
    private var loadJob: Job? = null

    /** The provider switch in flight: cancelled when another one is picked. */
    private var switchJob: Job? = null

    /** One provider feed at a time; paused unless Home is visible and idle. */
    private var autoRefreshJob: Job? = null

    /** Installed-provider/sidebar refresh; cancelled with the feed on Play. */
    private var installedJob: Job? = null

    /** Launch-time provider update; never allowed to delay or outlive playback. */
    private var startupJob: Job? = null

    /** A six-hour manifest/download pass, cancellable the moment Play is pressed. */
    private var updateJob: Job? = null

    init {
        // Never put a manifest/module update on the launch path. The first
        // catalog read starts immediately from the installed provider; once
        // Home has painted and the feed job is idle, the throttled six-hour
        // check runs. If the check really installed something, refresh the
        // rows silently — the viewer never waited on it.
        startupJob = viewModelScope.launch {
            while (isActive && (
                    !homeVisible ||
                        loadJob?.isActive == true ||
                        PlaybackSession.playerActive ||
                        PlaybackSession.playbackRequested
                    )
            ) {
                delay(250)
            }
            if (!isActive || !homeVisible ||
                PlaybackSession.playerActive || PlaybackSession.playbackRequested
            ) {
                return@launch
            }
            // Let the first frame and focus settle before touching the disk.
            delay(1_200)
            if (PlaybackSession.playerActive || PlaybackSession.playbackRequested) {
                return@launch
            }
            val report = try {
                ServiceLocator.providers.checkForUpdates()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A provider source being unreachable must never stop Home.
                return@launch
            }
            if (report.updated > 0 &&
                homeVisible &&
                !PlaybackSession.playerActive &&
                !PlaybackSession.playbackRequested
            ) {
                fetch(silent = true, spinner = false)
            }
        }
        // Long-running sessions still get the six-hour check. Skip it while a
        // player is active or the user has committed to a title: a module
        // download must not compete with resolving or decoding.
        viewModelScope.launch {
            while (isActive) {
                delay(6 * 60 * 60 * 1000L)
                if (PlaybackSession.playerActive || PlaybackSession.playbackRequested) continue
                updateJob = viewModelScope.launch {
                    try {
                        ServiceLocator.providers.checkForUpdates(force = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        // One collector for the life of the view model. Starting it from
        // load() added another collector on every refresh.
        viewModelScope.launch {
            combine(
                ServiceLocator.history.items,
                ServiceLocator.settings.familyFilter,
            ) { items, familyOn ->
                if (familyOn) items.filter { FamilyFilter.isAllowed(it.title) } else items
            }.collect { _continue.value = it }
        }
        // Home is alive while Settings is open. Rebuild the feed as soon as the
        // toggle changes so returning to Home does not show filtered-away cards
        // or keep hiding them after the filter is turned off.
        viewModelScope.launch {
            ServiceLocator.settings.familyFilter.drop(1).collect {
                cacheEpoch++
                feedCache.clear()
                feedsKnown = false
                fetch(silent = false, spinner = false)
            }
        }
        viewModelScope.launch {
            combine(
                PlaybackSession.playerActiveFlow,
                PlaybackSession.playbackRequestedFlow,
                AppVisibility.foreground,
            ) { playing, requested, foreground -> !playing && !requested && foreground }
                .distinctUntilChanged()
                .collect { allowed ->
                    if (allowed) {
                        resumeFeedWork()
                    } else {
                        // Keep the hidden provider page warm while a detail
                        // resolve may be about to need it. Tear it down only
                        // when playback has really begun (or the app left the
                        // foreground); PlayerViewModel's own resolve is then
                        // allowed to finish first.
                        val teardown = PlaybackSession.playerActive ||
                            !AppVisibility.isForeground
                        pauseFeedWork(teardown)
                    }
                }
        }
    }

    fun load() = fetch(silent = false, spinner = false)

    /** Sidebar Refresh: keep the visible rows, show progress on the button. */
    fun refresh() = fetch(silent = true, spinner = true)

    /**
     * Feed refresh only runs while Home is actually on screen. Browsing a
     * detail page is not playback, but its metadata/season calls share the
     * same provider runtime and must not queue behind a background feed scan.
     */
    fun setHomeVisible(visible: Boolean) {
        if (homeVisible == visible) return
        homeVisible = visible
        if (visible) {
            // A Detail page that the viewer backed out of never became a
            // player. Returning to Home is the definitive end of that intent.
            PlaybackSession.playbackRequested = false
            resumeFeedWork()
        } else {
            // Leaving Home (detail, search, catalog, player) must stop feed
            // work immediately. A provider call already in flight would
            // otherwise share the single WebView/network with the screen the
            // viewer is opening. Keep the runtime warm for that screen.
            pauseFeedWork(teardown = false)
        }
    }

    /**
     * Stop a catalog fetch when the viewer opens a title.
     *
     * Home stays in the back stack while a film plays, so a refresh started
     * moments before Play kept issuing provider requests under the video. The
     * rows already on screen stay; only the in-flight work is dropped.
     */
    fun cancelLoad() {
        cacheEpoch++
        val jobs = listOfNotNull(autoRefreshJob, loadJob, switchJob, installedJob)
        val wasLoading = jobs.any { it.isActive }
        loadJob = null
        switchJob = null
        installedJob = null
        stopAutoRefresh()
        jobs.forEach { it.cancel() }
        // A launch-time update or a six-hour pass is network + disk work that
        // has no business running under the player or a detail resolve.
        val wasStarting = startupJob?.isActive == true
        startupJob?.cancel()
        startupJob = null
        updateJob?.cancel()
        updateJob = null
        if (wasLoading || wasStarting) pendingFeedLoad = true
        _refreshing.value = false
        // Do not destroy the provider WebView here: a normal card goes to the
        // detail page, which needs it immediately. The playbackRequested /
        // playerActive collector tears it down at the moment playback begins.
    }

    /**
     * Re-read the installed providers without touching the catalog rows. Home
     * calls this when it becomes visible again: a provider may have been
     * installed or removed on the Providers screen, and rebuilding the whole
     * grid just to update the sidebar would move focus under the viewer.
     */
    fun refreshInstalledOnly() {
        installedJob = viewModelScope.launch {
            val epoch = cacheEpoch
            refreshInstalled()
            if (epoch != cacheEpoch) return@launch
            pruneFeedCache()
            maintainMixedOrder()
            if (_filter.value.isNotBlank() && !isInstalled(_filter.value)) {
                _filter.value = ""
                persistProviderFilter("")
                if (epoch != cacheEpoch) return@launch
            }
            // A load or provider switch already owns the feed; do not stack a
            // second one on top of it. On the very first Home composition
            // neither exists, and the state is still Loading: falling through
            // to load() is what starts the initial catalog fetch. The old
            // `!initialLoadStarted` guard here returned before that, so the
            // skeleton spun forever until the viewer pressed Refresh.
            if (loadJob?.isActive == true || switchJob?.isActive == true) {
                return@launch
            }
            // Any non-Success state (first run, a failed fetch, or the Loading
            // left by a previous visibility pass) needs a real load. Publishing
            // the visible cache here instead could leave `_state` on Loading
            // with no job behind it — the permanent spinner again.
            if (_state.value !is UiState.Success) {
                load()
                return@launch
            }
            if (_installed.value.isNotEmpty() && (
                    feedCache.isEmpty() ||
                        (_filter.value.isNotBlank() && !feedCache.containsKey(_filter.value))
                    )
            ) {
                load()
                return@launch
            }
            publishVisible()
            startAutoRefreshIfAllowed()
        }
    }

    private fun fetch(silent: Boolean, spinner: Boolean) {
        // A launch-time checkForUpdates() can finish after the viewer already
        // pressed Play from Continue Watching. Never start a feed scan under
        // playback (or under another screen); resume it when Home is on screen.
        if (PlaybackSession.playerActive || PlaybackSession.playbackRequested || !homeVisible) {
            pendingFeedLoad = true
            return
        }
        pendingFeedLoad = false
        stopAutoRefresh()
        switchJob?.cancel()
        switchJob = null
        loadJob?.cancel()
        val epoch = ++cacheEpoch
        loadJob = viewModelScope.launch {
            if (spinner) _refreshing.value = true
            try {
                if (!silent) {
                    // The persisted scope decides the view even after process death.
                    readProviderFilter()?.let { _filter.value = it }
                }
                ServiceLocator.providers.ensureSeeded()
                refreshInstalled()
                // A selection whose provider was uninstalled falls back to All.
                if (_filter.value.isNotBlank() && !isInstalled(_filter.value)) {
                    _filter.value = ""
                    persistProviderFilter("")
                }
                if (_filter.value.isNotBlank()) activate(_filter.value)
                resetMixedOrder()
                // A refresh must not blank the screen: the skeleton is only for
                // the very first load, and rows swap in place after that.
                if (!silent && _state.value !is UiState.Success) _state.value = UiState.Loading
                val targets = refreshOrder()
                if (targets.isEmpty()) {
                    if (epoch == cacheEpoch) {
                        _state.value = UiState.Success(noProvidersResult())
                    }
                } else {
                    for (target in targets) {
                        val feed = ServiceLocator.repository.catalogRows(target.ref) { partial ->
                            if (epoch == cacheEpoch) {
                                val prev = feedCache[target.ref]
                                feedCache[target.ref] =
                                    CachedFeed(partial, prev?.updatedAt ?: 0L)
                                if (!silent) publishVisible()
                            }
                        }
                        if (epoch != cacheEpoch) return@launch
                        storeFeed(target.ref, feed)
                        if (!silent) publishVisible()
                    }
                    if (silent) publishVisible()
                }
                // Shared-folder sync on Home focus (mirrors web focus sync).
                runCatching { ServiceLocator.sync.syncFromSharedFolder() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A failed refresh keeps the last good page; the viewer is
                // likely mid-browse, not staring at an error screen.
                if (epoch == cacheEpoch && _state.value !is UiState.Success) {
                    _state.value = UiState.Error(e.message ?: "Failed to load")
                }
            } finally {
                if (spinner && epoch == cacheEpoch) _refreshing.value = false
                if (epoch == cacheEpoch) startAutoRefreshIfAllowed()
            }
        }
    }

    private suspend fun refreshInstalled() {
        val installed = try {
            ServiceLocator.providers.installed()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (installed != null) _installed.value = installed.filter { it.value.isNotBlank() }
        try {
            activeRef = ServiceLocator.providers.active()?.let { refOf(it.ext) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Keep the last active ref; transient storage failure is not a provider change.
        }
        feedsKnown = _installed.value.isNotEmpty()
    }

    /**
     * Sidebar selection: "" is All, otherwise an installed provider's ref.
     * Picking a provider also moves the app's active provider, so details,
     * search and playback resolve against the source being browsed.
     */
    fun setProviderFilter(ref: String) {
        if (_filter.value == ref) return
        _filter.value = ref
        cacheEpoch++
        switchJob?.cancel()
        loadJob?.cancel()
        loadJob = null
        stopAutoRefresh()
        _refreshing.value = false
        if (ref.isBlank()) resetMixedOrder()
        _state.value = visibleResult()?.let { UiState.Success(it) } ?: UiState.Loading
        switchJob = viewModelScope.launch {
            persistProviderFilter(ref)
            // Rapid switches must end with the last pick active, so check
            // cancellation before every effectful step.
            if (!isActive) return@launch
            activate(ref)
            if (!isActive) return@launch
            refreshInstalled()
            if (!isActive) return@launch
            if (ref.isNotBlank() && !isInstalled(ref)) {
                _filter.value = ""
                persistProviderFilter("")
                publishVisible()
                switchJob = null
                startAutoRefreshIfAllowed()
                return@launch
            }
            if (ref.isBlank()) {
                resetMixedOrder()
                publishVisible()
                if (visibleResult() == null && _installed.value.isNotEmpty()) {
                    switchJob = null
                    load()
                    return@launch
                }
            } else {
                val target = feedTargets().firstOrNull { it.ref == ref }
                val stale = feedCache[ref]?.let {
                    System.currentTimeMillis() - it.updatedAt >= FEED_REFRESH_INTERVAL_MS
                } ?: true
                if (target != null && stale) {
                    try {
                        refreshProvider(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
            switchJob = null
            startAutoRefreshIfAllowed()
        }
    }

    /**
     * Point the app's active provider at [ref]; no-op for All/blank. Used when
     * a post opened from the mixed view must resolve against its origin.
     */
    suspend fun activate(ref: String) {
        if (ref.isBlank()) return
        val ext = try {
            ServiceLocator.providers.installed().firstOrNull { refOf(it) == ref }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (ext == null) return
        try {
            ServiceLocator.providers.setActive(ext)
            activeRef = ref
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    /** DataStore reads/writes must not turn a cancelled switch into a stale publish. */
    private suspend fun readProviderFilter(): String? = try {
        ServiceLocator.settings.providerFilter.first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private suspend fun persistProviderFilter(ref: String) {
        try {
            ServiceLocator.settings.setProviderFilter(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    private fun refreshOrder(): List<FeedTarget> {
        val targets = feedTargets()
        val selected = _filter.value
        return if (selected.isBlank()) {
            targets
        } else {
            targets.sortedByDescending { it.ref == selected }
        }
    }

    private fun feedTargets(): List<FeedTarget> {
        val installed = _installed.value
        if (installed.isEmpty()) return emptyList()
        val active = activeRef
        val ordered = if (installed.size > 1 && active != null) {
            installed.sortedByDescending { refOf(it) == active }
        } else {
            installed
        }
        return ordered.map {
            FeedTarget(refOf(it), it.display_name.ifBlank { it.value })
        }
    }

    private fun resetMixedOrder() {
        mixedOrderRefs = feedTargets().map { it.ref }
    }

    private fun maintainMixedOrder() {
        if (mixedOrderRefs.isEmpty()) {
            resetMixedOrder()
            return
        }
        val installedRefs = _installed.value.map { refOf(it) }
        val installedSet = installedRefs.toHashSet()
        val kept = mixedOrderRefs.filter { it in installedSet }
        val keptSet = kept.toHashSet()
        mixedOrderRefs = kept + installedRefs.filter { it !in keptSet }
    }

    private fun mixedTargets(): List<FeedTarget> {
        val targets = feedTargets()
        if (targets.size < 2) return targets
        val byRef = targets.associateBy { it.ref }
        val ordered = mixedOrderRefs.mapNotNull { byRef[it] }
        val orderedSet = ordered.mapTo(HashSet<String>()) { it.ref }
        return ordered + targets.filter { it.ref !in orderedSet }
    }

    private fun visibleResult(): CatalogResult? {
        val filter = _filter.value
        return if (filter.isBlank()) buildMixedResult() else feedCache[filter]?.result
    }

    private fun buildMixedResult(): CatalogResult? {
        val targets = mixedTargets()
        if (targets.isEmpty()) return noProvidersResult()
        if (targets.size == 1) return feedCache[targets.first().ref]?.result
        if (targets.none { feedCache.containsKey(it.ref) }) return null
        val seenTitles = HashSet<String>()
        val rows = mutableListOf<HomeRow>()
        for (target in targets) {
            val feed = feedCache[target.ref] ?: continue
            for (row in feed.result.rows) {
                val posts = row.posts.filter {
                    seenTitles.add(FamilyFilter.dedupeKey(it.title))
                }
                if (posts.isEmpty() && row.error == null) continue
                rows += HomeRow(
                    title = "${target.name} \u00B7 ${row.title}",
                    filter = row.filter,
                    posts = posts,
                    error = row.error,
                    providerRef = target.ref,
                )
            }
        }
        if (rows.isEmpty()) {
            val diagnostic = targets.asSequence()
                .mapNotNull { feedCache[it.ref]?.result?.diagnostic }
                .firstOrNull()
            return CatalogResult(
                diagnostic = diagnostic ?: "Provider returned an empty feed.",
            )
        }
        val allFailed = rows.all { it.posts.isEmpty() && it.error != null }
        return CatalogResult(
            rows = rows,
            diagnostic = if (allFailed) "No posts could be loaded. ${rows.first().error}" else null,
        )
    }

    private fun publishVisible() {
        val result = visibleResult() ?: return
        val current = _state.value
        if (current is UiState.Success && current.data == result) return
        _state.value = UiState.Success(result)
    }

    private suspend fun refreshProvider(target: FeedTarget) {
        val epoch = cacheEpoch
        val feed = ServiceLocator.repository.catalogRows(target.ref)
        if (epoch != cacheEpoch) return
        storeFeed(target.ref, feed)
        if (_filter.value.isBlank() || _filter.value == target.ref) {
            publishVisible()
        }
    }

    private fun storeFeed(ref: String, result: CatalogResult) {
        val prev = feedCache[ref]
        feedCache[ref] = if (result.rows.isEmpty() &&
            prev?.result?.rows?.isNotEmpty() == true
        ) {
            CachedFeed(prev.result, System.currentTimeMillis())
        } else {
            CachedFeed(result, System.currentTimeMillis())
        }
    }

    private fun pruneFeedCache() {
        if (feedCache.isEmpty()) return
        val refs = _installed.value.mapTo(HashSet<String>()) { refOf(it) }
        feedCache.keys.retainAll(refs)
    }

    private fun isInstalled(ref: String): Boolean =
        _installed.value.any { refOf(it) == ref }

    private fun resumeFeedWork() {
        if (!homeVisible || !AppVisibility.isForeground ||
            PlaybackSession.playerActive || PlaybackSession.playbackRequested
        ) return
        if (pendingFeedLoad) {
            pendingFeedLoad = false
            load()
        } else {
            startAutoRefreshIfAllowed()
        }
    }

    private fun startAutoRefreshIfAllowed() {
        if (!homeVisible || !feedsKnown || autoRefreshJob?.isActive == true) return
        if (PlaybackSession.playerActive || PlaybackSession.playbackRequested ||
            !AppVisibility.isForeground
        ) return
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(FEED_REFRESH_INTERVAL_MS)
                while (isActive && (
                        PlaybackSession.playerActive ||
                            PlaybackSession.playbackRequested ||
                            !AppVisibility.isForeground ||
                            WafCoordinator.pending.value != null ||
                            loadJob?.isActive == true ||
                            switchJob?.isActive == true
                        )
                ) {
                    delay(1_000)
                }
                if (!isActive) break
                val targets = feedTargets()
                if (targets.isEmpty()) continue
                val target = targets[autoCursor % targets.size]
                autoCursor++
                val epoch = cacheEpoch
                val feed = try {
                    ServiceLocator.repository.catalogRows(target.ref)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                if (epoch == cacheEpoch && feed != null) {
                    storeFeed(target.ref, feed)
                    if (_filter.value.isBlank() || _filter.value == target.ref) {
                        publishVisible()
                    }
                }
            }
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    private fun pauseFeedWork(teardown: Boolean) {
        cacheEpoch++
        val jobs = listOfNotNull(autoRefreshJob, loadJob, switchJob, installedJob)
        val wasLoading = jobs.any { it.isActive }
        autoRefreshJob = null
        loadJob = null
        switchJob = null
        installedJob = null
        jobs.forEach { it.cancel() }
        val wasStarting = startupJob?.isActive == true
        startupJob?.cancel()
        startupJob = null
        updateJob?.cancel()
        updateJob = null
        if (wasLoading || wasStarting) pendingFeedLoad = true
        _refreshing.value = false
        if (!teardown) return
        // Join first: the player's own provider resolve may hold the single
        // WebView mutex. suspendForPlayback then either destroys now or marks
        // destroy-after-invoke, so it can never cut that resolve in half.
        viewModelScope.launch {
            jobs.forEach { it.join() }
            ServiceLocator.providers.js.suspendForPlayback()
        }
    }

    private fun noProvidersResult(): CatalogResult = CatalogResult(
        diagnostic = "No provider is installed. Add one on the Providers screen " +
            "(for example @Zenda-Cross), then install a provider from the list.",
    )

    private fun refOf(ext: ProviderExtension): String = "${ext.source.author}:${ext.value}"
}
