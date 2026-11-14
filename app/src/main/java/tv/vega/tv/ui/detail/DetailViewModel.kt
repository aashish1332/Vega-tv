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
import tv.vega.tv.player.qualityScore
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.episodeNumber

data class ResolvedSeason(
    val title: String,
    val episodes: List<EpisodeItem>,
    val seasonKey: String = "",
    val seasonLabel: String = "",
    val seasonNumber: Int? = null,
    /** True until the per-season episode fetch for this row has landed. */
    val pending: Boolean = false,
)

/** One season tab ("S1", "S2", …) and the provider entries that belong to it. */
data class SeasonGroup(
    val key: String,
    val label: String,
    val number: Int?,
)

data class DetailData(
    val info: VegaInfo,
    val seasons: List<ResolvedSeason>,
    val seasonGroups: List<SeasonGroup> = emptyList(),
)

/**
 * Season number from the names providers actually publish: "Season 1", "S01",
 * "1st Season", or a bare "2". Returns null for specials/collections so they
 * stay distinct instead of being folded into an unrelated tab.
 */
private val SEASON_NUMBER = Regex(
    """(?i)(?:\bseason\s*0*(\d{1,3})\b|\bs\s*0*(\d{1,3})\b|\b0*(\d{1,3})\s*(?:st|nd|rd|th)?\s*season\b)""",
)
private val BARE_SEASON_NUMBER = Regex("""^\s*0*(\d{1,3})\s*$""")

private fun seasonNumberOf(title: String): Int? {
    val fromPhrase = SEASON_NUMBER.find(title)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull { it.isNotBlank() }
        ?.toIntOrNull()
    if (fromPhrase != null) return fromPhrase
    return BARE_SEASON_NUMBER.find(title)?.groupValues?.get(1)?.toIntOrNull()
}

private data class SeasonIdentity(
    val key: String,
    val label: String,
    val number: Int?,
)

private fun identityOf(season: tv.vega.tv.data.model.SeasonEntry): SeasonIdentity {
    val title = season.title.trim()
    val number = seasonNumberOf(title)
    return if (number != null) {
        SeasonIdentity("season:$number", "S$number", number)
    } else {
        val label = title.ifBlank { "All" }
        SeasonIdentity("title:" + label.lowercase(), label, null)
    }
}

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

    /**
     * Stop a load the viewer has moved on from. Called right before playback:
     * a show's season resolution can take seconds after the detail page is
     * already on screen, and those provider calls would compete with the video
     * for the box's CPU and the network the moment Play is pressed.
     */
    fun cancelPending() {
        loadJob?.cancel()
    }

    fun load() {
        _wafUrl.value = null
        loadJob?.cancel()
        // Captured now, not inside the coroutine: a second card tapped while
        // this one is still resolving overwrites the global pending handoff,
        // and reading it later would load the other title's page.
        val link = PlaybackSession.pendingLink
        val providerRef = PlaybackSession.pendingProvider
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            if (link.isBlank()) {
                _state.value = UiState.Error("Nothing selected")
                return@launch
            }
            runCatching {
                // The link belongs to the provider it was listed under, which
                // on a mixed Home page is not necessarily the active one.
                if (providerRef.isNotBlank()) runCatching {
                    ServiceLocator.providers.activate(providerRef)
                }
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
                // A movie's linkList entries are its quality renditions, not
                // seasons. Highest resolution/size first, so the page (and the
                // Play button, which takes the first card) starts on the best
                // copy the provider offers.
                val movie = tv.vega.tv.player.isMovieInfo(enriched)
                val ordered = if (movie) {
                    enriched.linkList.sortedByDescending { season ->
                        qualityScore(
                            season.quality?.trim().orEmpty()
                                .ifBlank { season.title.trim() }
                                .ifBlank { season.directLinks.firstOrNull()?.title.orEmpty() },
                        )
                    }
                } else {
                    enriched.linkList
                }
                val emptyTitle = if (movie) "Qualities" else "Episodes"
                // Shape-2 movies name the rendition in `quality`, not `title`.
                val headerOf: (tv.vega.tv.data.model.SeasonEntry) -> String = { season ->
                    season.title.trim()
                        .ifBlank { season.quality?.trim().orEmpty() }
                        .ifBlank { emptyTitle }
                }
                // Providers commonly publish one linkList entry per
                // season+quality ("Season 1 · 1080p", "Season 1 · 720p", …).
                // Rendering every entry as its own row produced 25 rows for a
                // five-season show. Keep each entry (its links are distinct)
                // but tag it with the season tab it belongs to; the screen shows
                // only the selected tab.
                val identities = ordered.map { identityOf(it) }
                val groups = if (movie) {
                    emptyList()
                } else {
                    identities.distinctBy { it.key }
                        .sortedWith(
                            compareBy(
                                { it.number == null },
                                { it.number ?: Int.MAX_VALUE },
                                { it.label.lowercase() },
                            ),
                        )
                        .map { SeasonGroup(it.key, it.label, it.number) }
                }
                val showSeasonPicker = groups.size > 1
                fun rowTitle(index: Int, season: tv.vega.tv.data.model.SeasonEntry): String {
                    val base = headerOf(season)
                    if (!showSeasonPicker) return base
                    // Inside a season tab the quality is the useful row label;
                    // repeating "Season 1" on every row hides which copy is
                    // which. Entries without a quality keep their own title.
                    return season.quality?.trim().orEmpty().ifBlank { base }
                }
                val seasons = ordered.mapIndexed { index, season ->
                    val id = if (movie) SeasonIdentity("", "", null) else identities[index]
                    ResolvedSeason(
                        title = rowTitle(index, season),
                        episodes = emptyList(),
                        seasonKey = id.key,
                        seasonLabel = id.label,
                        seasonNumber = id.number,
                        pending = true,
                    )
                }
                _state.value = UiState.Success(DetailData(enriched, seasons, groups))
                val resolved = seasons.map { it }.toMutableList()
                val jobs = ordered.mapIndexed { index, season ->
                    async {
                        val raw = season.directLinks.mapIndexed { i, d ->
                            // A rendition card with no title of its own still
                            // has a name: the season's quality label. Without
                            // it the card would read "Episode 1".
                            val title = if (movie) {
                                d.title.trim().ifBlank {
                                    season.quality?.trim().orEmpty()
                                        .ifBlank { season.title.trim() }
                                }
                            } else {
                                d.title
                            }
                            EpisodeItem(title = title, link = d.link, image = d.image, number = i + 1)
                        }.ifEmpty {
                            val url = season.episodesLink.orEmpty()
                            if (url.isBlank()) emptyList()
                            else runCatching {
                                val pm = ServiceLocator.providers
                                val a = pm.active() ?: return@runCatching emptyList<EpisodeItem>()
                                pm.getEpisodes(a, url)
                            }.getOrDefault(emptyList()).mapIndexed { i, e -> e.copy(number = i + 1) }
                        }
                        val eps = if (movie) {
                            // Quality cards: best-first, matching the player's
                            // Quality list.
                            raw.sortedByDescending { qualityScore(it.title) }
                        } else {
                            // Providers list newest-first often enough that the
                            // first card used to be the last episode. Numbered
                            // titles are sorted by their real number; titles
                            // without one keep the provider's order.
                            raw.sortedBy { episodeNumber(it).toIntOrNull() ?: it.number }
                        }
                        val id = if (movie) SeasonIdentity("", "", null) else identities[index]
                        resolved[index] = ResolvedSeason(
                            title = rowTitle(index, season),
                            episodes = eps,
                            seasonKey = id.key,
                            seasonLabel = id.label,
                            seasonNumber = id.number,
                        )
                        // Publish each season as it arrives. Guarded by the
                        // cancellation check below so a load the user has
                        // already walked away from does not write over the
                        // result of the retry they are now watching.
                        if (isActive) {
                            _state.value = UiState.Success(DetailData(enriched, resolved.toList(), groups))
                        }
                    }
                }
                jobs.awaitAll()
                DetailData(enriched, resolved.toList(), groups)
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
