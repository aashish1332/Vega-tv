package tv.vega.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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

    /**
     * Why the *next* episode failed to resolve. Unlike [state], this never
     * replaces what is on screen: the finished episode and its controls stay
     * up and the message rides on top, so a provider hiccup between episodes
     * cannot strand the viewer on an error screen.
     */
    private val _nextError = MutableStateFlow<String?>(null)
    val nextError: StateFlow<String?> = _nextError

    /** Wording for [nextError]: an episode jump and a quality switch differ. */
    private val _nextErrorTitle = MutableStateFlow("Couldn't start next episode")
    val nextErrorTitle: StateFlow<String> = _nextErrorTitle

    /**
     * Non-null from the instant Prev/Next/a quality card is pressed until the
     * new streams resolve. Drives the loading pill (the jump used to show no
     * feedback for seconds) and gates auto-next: the outgoing player still
     * reports ENDED while the next episode resolves, which used to restart the
     * countdown for episode 3 on top of the correct episode-2 load.
     */
    private val _switching = MutableStateFlow<String?>(null)
    val switching: StateFlow<String?> = _switching

    /** An advancing load is in flight; a second one must not skip an episode. */
    private var resolvingNext: Boolean = false

    /** Which way the last episode jump went; [retryAdvance] repeats it. */
    private var lastStep: Int = 1

    /**
     * Quality-switch bookkeeping. A switch rewrites the current item's link;
     * if the new link fails to resolve, [switchBackLink] puts the playing one
     * back and [switchTargetLink] lets Try again repeat the attempt.
     */
    private var switchBackLink: String? = null
    private var switchTargetLink: String? = null

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

    /**
     * Move to the source a playback error chose, without recording it as a
     * deliberate pick: Retry should still resolve to the provider's best
     * server rather than the last one that happened to fail.
     */
    fun selectFailover(i: Int) { index = i }

    /** True once the user has chosen a server themselves. */
    private var userPicked: Boolean = false

    /** Read inside `load()`, which is where the automatic choice is made. */
    private var preferBest: Boolean = true

    /**
     * Height of the TV panel, set by [PlayerScreen] before the provider
     * resolves. Auto-selection uses it to avoid opening a 4K rendition on a
     * 1080p panel: decoding 2160p here costs 4× the memory bandwidth for no
     * visible pixels and is a common source of dropped frames on TV SoCs.
     */
    var maxPlaybackHeight: Int = 0

    init { load() }

    /** Advance to the next episode (mirrors web handleNextEpisode). */
    fun next() = step(1)

    /** Go back one episode; a no-op on the first one. */
    fun prev() = step(-1)

    /** Repeat the jump that failed, from the notice's Retry. */
    fun retryAdvance() {
        // A failed quality switch is repeated as a switch, not as an episode
        // jump: the previous jump direction belongs to an older action.
        switchTargetLink?.let { link ->
            switchLink(link)
            return
        }
        step(lastStep)
    }

    /**
     * Switch the current item to another provider-named rendition (movies
     * expose their quality cards as separate links, not as stream variants).
     *
     * The episode list is shared with the session, so the chosen link replaces
     * the current item's link rather than becoming a second entry: Prev/Next,
     * progress and auto-next keep pointing at the same title. Playback resumes
     * at the current position because the player keeps it across the stream
     * swap.
     */
    fun switchLink(link: String) {
        if (link.isBlank() || resolvingNext) return
        val i = PlaybackSession.startIndex
        val ep = PlaybackSession.episodes.getOrNull(i)
        val current = ep?.link ?: PlaybackSession.pendingLink
        if (current == link) return
        switchBackLink = current
        switchTargetLink = link
        if (ep == null) {
            PlaybackSession.pendingLink = link
        } else {
            PlaybackSession.episodes = PlaybackSession.episodes.toMutableList().also {
                it[i] = ep.copy(link = link)
            }
        }
        userPicked = false
        index = 0
        _switching.value = "Switching quality…"
        load(advancing = true, step = 0)
    }

    private fun step(delta: Int) {
        if (resolvingNext) return
        val target = PlaybackSession.startIndex + delta
        if (target !in PlaybackSession.episodes.indices) return
        lastStep = delta
        _switching.value =
            if (delta > 0) "Loading next episode…" else "Loading previous episode…"
        // A new episode jump supersedes any pending quality retry.
        switchBackLink = null
        switchTargetLink = null
        PlaybackSession.startIndex = target
        PlaybackSession.resumePositionMs = 0L
        // A new episode is a new choice: the server picked for the last one
        // says nothing about this one, and its rendition list can differ.
        userPicked = false
        index = 0
        load(advancing = true, step = delta)
    }

    /** Dismiss the auto-next failure notice without touching playback. */
    fun dismissNextError() {
        _nextError.value = null
    }

    /** Retry after a full-screen error: back to the loading screen. */
    fun load() {
        _nextError.value = null
        switchBackLink = null
        switchTargetLink = null
        _switching.value = null
        load(advancing = false)
    }

    /**
     * [advancing] means auto-next / MEDIA_NEXT: the finished episode stays on
     * screen while the next one resolves, and a resolve failure becomes a
     * notice instead of replacing playback with an error screen.
     */
    private fun load(advancing: Boolean, step: Int = 0) {
        if (advancing && resolvingNext) return
        if (advancing) resolvingNext = true
        viewModelScope.launch {
            if (!advancing) _state.value = UiState.Loading
            _wafUrl.value = null
            // Restored if an advancing load fails, so a retry does not skip an
            // episode and the highlighted server does not jump.
            val prevIndex = index
            val prevUserPicked = userPicked
            val ep = PlaybackSession.episodes.getOrNull(PlaybackSession.startIndex)
            val link = ep?.link ?: PlaybackSession.pendingLink
            if (link.isBlank()) {
                fail(advancing, step, prevIndex, prevUserPicked, "Nothing to play", null)
                return@launch
            }
            runCatching {
                val excluded: Set<String> = runCatching {
                    ServiceLocator.settings.excludedQualities.first()
                }.getOrDefault(emptySet())
                preferBest = runCatching {
                    ServiceLocator.settings.preferBestQuality.first()
                }.getOrDefault(true)
                // Continue Watching can now skip the pre-player meta call. The
                // saved entry remembers its provider, so activate it before
                // getStream rather than resolving the link on whatever provider
                // happened to be active on Home.
                if (PlaybackSession.pendingProvider.isNotBlank()) {
                    try {
                        ServiceLocator.providers.activate(PlaybackSession.pendingProvider)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
                val raw = resolveStreams(link, ep)
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
            }.onSuccess { resolved ->
                // Pixeldrain is the one host on these providers that streams a
                // whole file without a redirect/ad chain, so it is both the
                // fastest to start and the least likely to rebuffer. Put its
                // entries first and let the automatic pick stay inside them.
                val streams = preferPixeldrain(resolved)
                if (advancing && streams.isEmpty()) {
                    fail(
                        advancing, step, prevIndex, prevUserPicked,
                        "Provider returned no streams for the next episode.", null,
                    )
                    return@onSuccess
                }
                if (advancing) resolvingNext = false
                // The new source resolved: the switch (or episode jump) is
                // committed, so nothing needs restoring.
                switchBackLink = null
                switchTargetLink = null
                _nextError.value = null
                _switching.value = null
                when {
                    streams.isEmpty() -> autoSelect(0)
                    userPicked -> index = index.coerceIn(0, streams.lastIndex)
                    preferBest -> {
                        // After ordering, the Pixeldrain block is a prefix, so
                        // an index inside it is already an index into `streams`.
                        val pool = streams.takeWhile(::isPixeldrain).ifEmpty { streams }
                        autoSelect(bestIndexForDisplay(pool, maxPlaybackHeight))
                    }
                    else -> index = index.coerceIn(0, streams.lastIndex)
                }
                _state.value = UiState.Success(streams)
            }
                .onFailure { e ->
                    val message = if (e is tv.vega.tv.data.providers.ProviderManager.WafRequired) {
                        "Challenge required at ${e.host.ifBlank { "provider" }}"
                    } else {
                        e.message ?: "No streams"
                    }
                    fail(advancing, step, prevIndex, prevUserPicked, message, e)
                }
        }
    }

    /**
     * [advancing] failures are non-destructive: the episode index and server
     * choice go back to what the viewer was already watching, and the reason
     * surfaces as a dismissible notice on top of the finished episode.
     */
    private fun fail(
        advancing: Boolean,
        step: Int,
        prevIndex: Int,
        prevUserPicked: Boolean,
        message: String,
        e: Throwable?,
    ) {
        // Both failure paths finish with the old media still on screen; the
        // loading pill must not outlive the attempt.
        _switching.value = null
        if (advancing) {
            resolvingNext = false
            // Undo the jump in the direction it went (next moved +1, prev −1).
            PlaybackSession.startIndex -= step
            // A failed quality switch also puts the playing link back, so the
            // chip keeps naming what is actually on screen. switchTargetLink
            // stays set so Try again repeats the switch.
            switchBackLink?.let { back ->
                val i = PlaybackSession.startIndex
                val cur = PlaybackSession.episodes.getOrNull(i)
                if (cur != null) {
                    PlaybackSession.episodes = PlaybackSession.episodes.toMutableList()
                        .also { it[i] = cur.copy(link = back) }
                } else {
                    PlaybackSession.pendingLink = back
                }
                switchBackLink = null
            }
            index = prevIndex
            userPicked = prevUserPicked
            _nextErrorTitle.value =
                if (step == 0 && switchTargetLink != null) {
                    "Couldn't switch quality"
                } else if (step == 0) {
                    "Couldn't start playback"
                } else {
                    "Couldn't start next episode"
                }
            _nextError.value = message
            return
        }
        if (e is tv.vega.tv.data.providers.ProviderManager.WafRequired) {
            _wafUrl.value = e.url.ifBlank { null }
        }
        _state.value = UiState.Error(message)
    }

    /**
     * Resolve streams for [link], healing Continue Watching rows written by an
     * older build.
     *
     * Those rows stored the post/detail page as the playback identity, so
     * getStream was asked to resolve a page the provider only accepts as the
     * input to getMeta. When the saved row carries a post page and has no
     * episode list, resolve getMeta once, rebuild the real playable link, and
     * retry. The happy path costs exactly one getStream.
     */
    private suspend fun resolveStreams(
        link: String,
        ep: tv.vega.tv.data.model.EpisodeItem?,
    ): List<VegaStream> {
        return try {
            ServiceLocator.repository.streams(link, typeOf(ep))
        } catch (e: CancellationException) {
            throw e
        } catch (e: tv.vega.tv.data.providers.ProviderManager.WafRequired) {
            throw e
        } catch (original: Exception) {
            val fallback = PlaybackSession.pendingFallbackLink
            if (ep != null || fallback.isBlank()) throw original
            val info = try {
                ServiceLocator.repository.meta(fallback)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                throw original
            }
            if (tv.vega.tv.player.isMovieInfo(info)) {
                val direct = tv.vega.tv.player.qualityLinksOf(info)
                    .firstOrNull()?.link?.takeIf { it.isNotBlank() } ?: throw original
                PlaybackSession.info = info
                PlaybackSession.qualityAlternates = tv.vega.tv.player.qualityLinksOf(info)
                PlaybackSession.pendingLink = direct
                PlaybackSession.pendingFallbackLink = ""
                PlaybackSession.pendingType = info.type.ifBlank { PlaybackSession.pendingType }
                return ServiceLocator.repository.streams(direct, typeOf(null))
            }
            // Series: rebuild the episode deck, then pick the saved episode by
            // title (the index is per-season and cannot be trusted alone).
            val wanted = PlaybackSession.pendingEpisodeTitle.trim()
            var pickedSeason: List<tv.vega.tv.data.model.EpisodeItem>? = null
            var pickedIndex = -1
            for (season in info.linkList) {
                val url = season.episodesLink?.takeIf { it.isNotBlank() } ?: continue
                val active = ServiceLocator.providers.active() ?: break
                val list = try {
                    ServiceLocator.providers.getEpisodes(active, url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    continue
                }
                if (list.isEmpty()) continue
                val byTitle = if (wanted.isBlank()) -1 else list.indexOfFirst {
                    it.title.trim().equals(wanted, true) ||
                        it.title.contains(wanted, true) ||
                        wanted.contains(it.title.trim(), true)
                }
                if (byTitle >= 0) {
                    pickedSeason = list
                    pickedIndex = byTitle
                    break
                }
                if (pickedSeason == null && wanted.isBlank()) {
                    pickedSeason = list
                    pickedIndex = PlaybackSession.startIndex.coerceIn(0, list.lastIndex)
                }
            }
            val deck = pickedSeason ?: throw original
            val picked = deck[pickedIndex]
            if (picked.link.isBlank()) throw original
            PlaybackSession.episodes = deck
            PlaybackSession.startIndex = pickedIndex
            PlaybackSession.pendingEpisodeTitle = picked.title
            PlaybackSession.info = info
            PlaybackSession.qualityAlternates = emptyList()
            PlaybackSession.pendingLink = picked.link
            PlaybackSession.pendingFallbackLink = ""
            PlaybackSession.pendingType = info.type.ifBlank { PlaybackSession.pendingType }
            ServiceLocator.repository.streams(picked.link, typeOf(picked))
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
        PlaybackSession.pendingType.isNotBlank() ->
            PlaybackSession.pendingType.lowercase().replace(" ", "")
        ep != null -> "tv"
        else -> "movie"
    }
}

/** Host of a stream link, or "" when it is not a parseable URL. */
private fun streamHost(link: String): String =
    runCatching { java.net.URI(link).host.orEmpty() }.getOrDefault("")

/**
 * A stream served directly by Pixeldrain.
 *
 * Providers often list several mirrors for the same rendition. Pixeldrain
 * answers HTTP range requests directly from its CDN with no redirect chain,
 * no interstitial and no per-request ad script, which on a TV box is the
 * difference between instant playback and a spinner that fills a 30s buffer;
 * the other hosts commonly named by these providers are redirectors.
 */
internal fun isPixeldrain(s: VegaStream): Boolean =
    streamHost(s.link).contains("pixeldrain.", ignoreCase = true)

/**
 * Stable partition: Pixeldrain mirrors first, everything else in its original
 * order. Stable so a provider's own ascending-quality order survives inside
 * each group, and so server indexes do not shuffle between episodes.
 */
internal fun preferPixeldrain(streams: List<VegaStream>): List<VegaStream> =
    if (streams.none(::isPixeldrain)) streams
    else streams.sortedBy { if (isPixeldrain(it)) 0 else 1 }

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

/**
 * Pick the best rendition that the panel can actually show.
 *
 * A provider may offer 4K next to 1080p; choosing 4K on a 1080p TV decodes
 * four times the pixels, which is enough to starve a mid-range TV SoC even
 * when the network keeps up. If every offered rendition is above the panel,
 * take the smallest one (least bandwidth) rather than the largest. Unknown
 * heights (streamHeight's floor) only win when nothing is identifiable.
 */
internal fun bestIndexForDisplay(streams: List<VegaStream>, maxHeight: Int): Int {
    if (maxHeight <= 0) return bestIndex(streams)
    var capped = -1
    var cappedH = -1
    var above = -1
    var aboveH = Int.MAX_VALUE
    streams.forEachIndexed { i, s ->
        val h = streamHeight(s)
        when {
            h in 2..maxHeight -> if (h >= cappedH) { cappedH = h; capped = i }
            h > maxHeight -> if (h < aboveH) { aboveH = h; above = i }
        }
    }
    if (capped >= 0) return capped
    if (above >= 0) return above
    return bestIndex(streams)
}

private val RES_LABEL = Regex("""\b(\d{3,4})\s?[pi]?\b""", RegexOption.IGNORE_CASE)
private val RES_URL = Regex("""(4k|2160|1080|720|480|360)""", RegexOption.IGNORE_CASE)
private val RES_SIZE = Regex("""\b(3840|1920|1280|854|640|426)x(2160|1080|720|480|360)\b""")

internal fun streamHeight(s: VegaStream): Int {
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
