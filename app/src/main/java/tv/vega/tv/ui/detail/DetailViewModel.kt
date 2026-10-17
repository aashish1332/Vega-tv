package tv.vega.tv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.model.EpisodeItem
import tv.vega.tv.data.model.VegaInfo
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.ui.common.UiState

data class ResolvedSeason(
    val title: String,
    val episodes: List<EpisodeItem>,
)

data class DetailData(
    val info: VegaInfo,
    val seasons: List<ResolvedSeason>,
)

class DetailViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState<DetailData>>(UiState.Loading)
    val state: StateFlow<UiState<DetailData>> = _state
    private val _wafUrl = MutableStateFlow<String?>(null)
    val wafUrl: StateFlow<String?> = _wafUrl

    init { load() }

    /**
     * The in-flight load, so a second `load()` (the user pressed Retry, or
     * solved a challenge and the screen reloaded) cancels the first.
     *
     * Without this both coroutines run to completion and whichever finishes
     * *last* writes `_state` — so a slow first load can overwrite the result of
     * the retry the user is currently watching, and the screen shows stale data
     * with no way to tell.
     */
    private var loadJob: Job? = null

    fun load() {
        _wafUrl.value = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            val link = PlaybackSession.pendingLink
            if (link.isBlank()) {
                _state.value = UiState.Error("Nothing selected")
                return@launch
            }
            runCatching {
                val info = ServiceLocator.repository.meta(link)
                // TMDB enrich when the user set a key (mirrors web
                // useEnhancedMetadata; provider data stands alone otherwise).
                val enriched = runCatching {
                    val key = ServiceLocator.settings.snapshotKey()
                    val imdb = info.imdbId.orEmpty()
                    if (key.isBlank() || imdb.isBlank() || info.populateMeta == false) info
                    else tv.vega.tv.data.tmdb.TmdbApi.enrich(imdb, info.type, key)?.let { m ->
                        info.copy(
                            title = info.title.ifBlank { m.title },
                            synopsis = info.synopsis.ifBlank { m.overview },
                            rating = info.rating?.ifBlank { m.rating } ?: m.rating.ifBlank { null },
                            image = info.image.ifBlank { m.backdrop.ifBlank { m.poster } },
                            poster = info.poster?.ifBlank { m.poster } ?: m.poster.ifBlank { null },
                            tags = (info.tags + m.genres).distinct().take(6),
                        )
                    } ?: info
                }.getOrDefault(info)
                // Same as web SeasonList: resolve episodesLink per season in
                // parallel, keep directLinks as-is. Failures keep the season
                // with whatever resolved (never drop the whole page).
                //
                // A show with ten seasons and `episodesLink` on every one of them
                // is ten separate sandboxed network calls, and `awaitAll` on the
                // lot means the page shows *nothing at all* — not the backdrop,
                // not the title, not the Play button — until the slowest season
                // finishes. Worse, every one of those calls takes the invoke
                // mutex, so they run one at a time: ten seasons could be a
                // several-minute blank screen on a weak box. The header is
                // published as soon as `meta` returns and each season fills in
                // when it lands, which is also what the mobile app does.
                val seasons = enriched.linkList.map { season ->
                    ResolvedSeason(season.title.ifBlank { "Episodes" }, emptyList())
                }
                _state.value = UiState.Success(DetailData(enriched, seasons))
                val resolved = seasons.map { it }.toMutableList()
                val jobs = enriched.linkList.mapIndexed { index, season ->
                    async {
                        val eps = season.directLinks.mapIndexed { i, d ->
                            EpisodeItem(title = d.title, link = d.link, image = d.image, number = i + 1)
                        }.ifEmpty {
                            val url = season.episodesLink.orEmpty()
                            if (url.isBlank()) emptyList()
                            else runCatching {
                                val pm = ServiceLocator.providers
                                val a = pm.active() ?: return@runCatching emptyList<EpisodeItem>()
                                pm.getEpisodes(a, url)
                            }.getOrDefault(emptyList()).mapIndexed { i, e -> e.copy(number = i + 1) }
                        }
                        resolved[index] = ResolvedSeason(season.title.ifBlank { "Episodes" }, eps)
                        // Publish each season as it arrives. Guarded by the
                        // cancellation check below so a load the user has
                        // already walked away from does not write over the
                        // result of the retry they are now watching.
                        if (isActive) _state.value = UiState.Success(DetailData(enriched, resolved.toList()))
                    }
                }
                jobs.awaitAll()
                DetailData(enriched, resolved.toList())
            }.onSuccess { st ->
                // The progressive publish above already drew this; re-emitting is
                // harmless and covers the case where nothing was in flight.
                _state.value = UiState.Success(st)
            }
                .onFailure { e ->
                    // A cancelled load is the user having moved on, not a
                    // failure to report — see the same guard in SearchViewModel.
                    if (e is CancellationException) return@launch
                    if (e is tv.vega.tv.data.providers.ProviderManager.WafRequired) {
                        _wafUrl.value = e.url.ifBlank { null }
                        _state.value = UiState.Error("Challenge required at ${e.host.ifBlank { "provider" }}")
                    } else {
                        _state.value = UiState.Error(e.message ?: "Failed to load")
                    }
                }
        }
    }
}
