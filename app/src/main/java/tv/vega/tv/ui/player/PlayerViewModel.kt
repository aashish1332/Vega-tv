package tv.vega.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.model.VegaStream
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.ui.common.UiState

class PlayerViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<VegaStream>>>(UiState.Loading)
    val state: StateFlow<UiState<List<VegaStream>>> = _state
    private val _wafUrl = MutableStateFlow<String?>(null)
    val wafUrl: StateFlow<String?> = _wafUrl

    private var index: Int = 0

    /**
     * Which server the menu highlights, and therefore which one is playing.
     *
     * Assigning to it from outside — the server list — records a deliberate
     * choice, which [load] then preserves across a retry. The automatic
     * best-quality pick goes through [autoSelect] instead, so it does not look
     * like something the user asked for.
     */
    var selectedIndex: Int
        get() = index
        set(v) { index = v; userPicked = true }

    /** Set the selection without claiming the user made it. */
    private fun autoSelect(i: Int) { index = i }

    /** True once the user has chosen a server themselves. */
    private var userPicked: Boolean = false

    /** Read inside `load()`, which is where the automatic choice is made. */
    private var preferBest: Boolean = true

    init { load() }

    /** Advance to next episode (mirrors web handleNextEpisode). */
    fun next() {
        if (PlaybackSession.startIndex + 1 < PlaybackSession.episodes.size) {
            PlaybackSession.startIndex += 1
            PlaybackSession.resumePositionMs = 0L
            // A new episode is a new choice: the server picked for the last one
            // says nothing about this one, and its rendition list can differ.
            userPicked = false
            index = 0
            load()
        }
    }
    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            _wafUrl.value = null
            val ep = PlaybackSession.episodes.getOrNull(PlaybackSession.startIndex)
            val link = ep?.link ?: PlaybackSession.pendingLink
            if (link.isBlank()) {
                _state.value = UiState.Error("Nothing to play")
                return@launch
            }
            runCatching {
                val excluded: Set<String> = runCatching {
                    ServiceLocator.settings.excludedQualities.first()
                }.getOrDefault(emptySet())
                preferBest = runCatching {
                    ServiceLocator.settings.preferBestQuality.first()
                }.getOrDefault(true)
                val raw = ServiceLocator.repository.streams(link, typeOf(ep))
                val playable = raw.filter { it.link.isNotBlank() }
                // A provider can hand back an entry with no URL — a login-walled
                // or region-blocked rendition. Leaving it in the list meant the
                // player rendered a black screen with no spinner and no error,
                // because ExoPlayer had nothing to open. Drop those here so the
                // rest of the list is usable, and name the reason when *every*
                // entry was like that: "No streams" for a provider that returned
                // four is far less useful than saying none of them were playable.
                if (playable.isEmpty() && raw.isNotEmpty()) {
                    throw IllegalStateException(
                        "Provider returned ${raw.size} stream(s) but none had a playable link — " +
                            "it likely still needs a sign-in for this title.",
                    )
                }
                playable.filter { s -> excluded.none { q -> (s.quality ?: "").contains(q, true) } }
                    .ifEmpty { playable }
            }.onSuccess { streams ->
                when {
                    streams.isEmpty() -> autoSelect(0)
                    userPicked -> index = index.coerceIn(0, streams.lastIndex)
                    preferBest -> autoSelect(bestIndex(streams))
                    else -> index = index.coerceIn(0, streams.lastIndex)
                }
                _state.value = UiState.Success(streams)
            }
                .onFailure { e ->
                    if (e is tv.vega.tv.data.providers.ProviderManager.WafRequired) {
                        _wafUrl.value = e.url.ifBlank { null }
                        _state.value = UiState.Error("Challenge required at ${e.host.ifBlank { "provider" }}")
                    } else {
                        _state.value = UiState.Error(e.message ?: "No streams")
                    }
                }
        }
    }

    /**
     * Providers branch on `type` — the mobile app passes `"movie"`, `"tv"` and
     * `"anime"` depending on what the user came from. The port hardcoded
     * `"movie"` for every episode, so a series' stream call was asking a provider
     * to resolve a film and getting back either nothing or the wrong source.
     */
    private fun typeOf(ep: tv.vega.tv.data.model.EpisodeItem?): String = when {
        PlaybackSession.info?.type?.isNotBlank() == true ->
            PlaybackSession.info?.type?.lowercase()?.replace(" ", "").orEmpty()
        ep != null -> "tv"
        else -> "movie"
    }
}

/**
 * Index of the best stream, ranked by the *media* parameters rather than by the
 * label.
 *
 * The label is unreliable and the URL is not: a provider's `quality` is whatever
 * its author typed, and plenty of them write only `"auto"` or nothing, while the
 * link carries the real rendition. So resolution is read from the URL first
 * (`1080p` / `1920x1080` / `hd1080`) and the label is only consulted when the
 * URL says nothing, and height is what decides — bitrate on these hosts varies
 * freely for the same resolution.
 *
 * A tie is broken by the *last* entry, because providers conventionally list
 * their renditions ascending.
 */
internal fun bestIndex(streams: List<VegaStream>): Int {
    var best = 0
    var bestH = -1
    streams.forEachIndexed { i, s ->
        val h = streamHeight(s)
        if (h >= bestH) { bestH = h; best = i }
    }
    return best
}

private val RES_LABEL = Regex("""\b(\d{3,4})\s?[pi]?\b""", RegexOption.IGNORE_CASE)
private val RES_URL = Regex("""(4k|2160|1080|720|480|360)""", RegexOption.IGNORE_CASE)
private val RES_SIZE = Regex("""\b(3840|1920|1280|854|640|426)x(2160|1080|720|480|360)\b""")

private fun streamHeight(s: VegaStream): Int {
    RES_SIZE.find(s.link)?.let { return it.groupValues[2].toIntOrNull() ?: 0 }
    RES_URL.find(s.link)?.let { return it.groupValues[1].toIntOrNull()?.let(::urlHeight) ?: 0 }
    s.quality?.let { q ->
        RES_LABEL.find(q)?.let { return it.groupValues[1].toIntOrNull() ?: 0 }
    }
    // HLS master playlists keep the ladder in the URL path more often than not
    // (…/master.m3u8, …/720/index.m3u8), so the label check above already covers
    // the common shape; an unrecognised stream must not be ranked as 0 and lose
    // to a genuinely low rendition, hence a small floor rather than 0.
    return 1
}

private fun urlHeight(n: Int): Int = when (n) {
    2160 -> 2160
    1080 -> 1080
    720 -> 720
    480 -> 480
    360 -> 360
    else -> 0
}
