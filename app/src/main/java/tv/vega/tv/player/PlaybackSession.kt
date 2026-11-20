package tv.vega.tv.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import tv.vega.tv.data.model.EpisodeItem
import tv.vega.tv.data.model.SeasonEntry
import tv.vega.tv.data.model.VegaInfo
import tv.vega.tv.data.model.VegaStream

object PlaybackSession {
    private val _playerActive = MutableStateFlow(false)
    val playerActiveFlow: StateFlow<Boolean> = _playerActive

    /** True while the player screen is alive; background provider work checks it. */
    var playerActive: Boolean
        get() = _playerActive.value
        set(value) {
            _playerActive.value = value
        }

    private val _playbackRequested = MutableStateFlow(false)
    val playbackRequestedFlow: StateFlow<Boolean> = _playbackRequested

    /**
     * Set the instant a card or Continue Watching entry is opened, before the
     * route changes. Feed refreshes, provider updates and the hidden provider
     * WebView must stop as soon as the user commits to watching, not when the
     * player screen finally composes.
     */
    var playbackRequested: Boolean
        get() = _playbackRequested.value
        set(value) {
            _playbackRequested.value = value
        }

    // Cheap handoff without parcelables (weak devices, large episode lists).
    var pendingLink: String = ""
    var pendingTitle: String = ""
    var pendingPoster: String = ""
    var pendingType: String = ""
    var pendingEpisodeTitle: String = ""
    /**
     * Legacy Continue Watching fallback. Older builds saved the post/detail
     * page URL in [pendingLink]; this keeps that page so a failed getStream can
     * resolve it through getMeta once and retry with a real stream link.
     * Cleared as soon as [set] starts a normal detail playback.
     */
    var pendingFallbackLink: String = ""
    // "author:value" (or just the provider value) the pending link belongs to.
    // A mixed Home page can show rows from several providers; Detail must
    // activate this provider before resolving the link, or the same URL is
    // asked of the wrong site and a different title comes back.
    var pendingProvider: String = ""
    // External subtitles added mid-session (search/file). Player merges them
    // into the MediaItem and rebuilds on change (position preserved).
    var externalSubs: List<ExternalSub> = emptyList()
    var subsRevision: Int = 0
    // Cumulative subtitle delay applied to the external files (positive = the
    // subtitles were moved later in time). The files themselves carry the
    // shift; this is the running total shown in the UI and undone by Reset.
    var subDelayMs: Long = 0L

    var title: String = ""
    var posterUrl: String = ""
    var episodes: List<EpisodeItem> = emptyList()
    var startIndex: Int = 0
    var resumePositionMs: Long = 0L
    var info: VegaInfo? = null
    // For movies the provider often models each quality as its own "season"
    // entry instead of as a stream rendition. Those links are alternatives to
    // the currently playing item, so the player's Quality tab lists them.
    var qualityAlternates: List<QualityLink> = emptyList()
    /**
     * The server/quality list the player is actually showing. History saves
     * this whole list, not just the playing link, so a resume can offer every
     * server instead of only the last one.
     */
    var streams: List<VegaStream> = emptyList()
    /**
     * Streams restored from a Continue Watching row. They are merged with the
     * fresh provider resolve (fresh links win) and used as a fallback when the
     * provider call fails, so an expiring mirror URL never strands a resume.
     */
    var savedStreams: List<VegaStream> = emptyList()
    /** Server+quality that was playing; fresh URLs may differ. */
    var preferredStreamKey: String = ""
    /** Updated by the player for the stream actually on screen. */
    var playingStreamKey: String = ""

    fun set(
        title: String,
        posterUrl: String,
        episodes: List<EpisodeItem>,
        index: Int,
        info: VegaInfo? = null,
        qualityAlternates: List<QualityLink> = emptyList(),
    ) {
        val chosen = if (episodes.isEmpty()) 0 else index.coerceIn(episodes.indices)
        // A movie's "episode cards" are quality renditions of the same film,
        // not a playlist. Keeping them as episodes made Prev/Next and auto-next
        // jump between 4K and 1080p copies; the rendition list belongs in
        // [qualityAlternates], which switches the one playing link in place.
        val qualityDeck = isMovieInfo(info) && episodes.size > 1
        this.title = title
        this.posterUrl = posterUrl
        // A normal detail playback supersedes any legacy resume fallback.
        this.pendingFallbackLink = ""
        this.episodes = if (qualityDeck) listOf(episodes[chosen]) else episodes
        this.startIndex = if (qualityDeck) 0 else chosen
        this.resumePositionMs = 0L
        this.info = info
        this.pendingType = info?.type.orEmpty()
        this.pendingEpisodeTitle = ""
        this.qualityAlternates = qualityAlternates
        this.streams = emptyList()
        this.savedStreams = emptyList()
        this.preferredStreamKey = ""
        this.playingStreamKey = ""
    }
}

/**
 * Resolution tier of a provider quality card, coarse enough to rank mixed
 * wording ("4K", "2160p", "UHD") the same.
 */
fun qualityTier(label: String): Int {
    val l = label.lowercase()
    // No trailing word boundary: "1080p60" and "4k60" are resolutions too.
    // Highest tier wins, so "4K (upscaled from 1080p)" is still 4K.
    return when {
        Regex("""\b(?:8k|4320)""").containsMatchIn(l) -> 6
        Regex("""\b(?:4k|2160|uhd)""").containsMatchIn(l) -> 5
        Regex("""\b(?:1440|qhd|2k)""").containsMatchIn(l) -> 4
        Regex("""\b(?:1080|fhd|full ?hd)""").containsMatchIn(l) -> 3
        Regex("""\b720""").containsMatchIn(l) -> 2
        Regex("""\b(?:576|480|360)""").containsMatchIn(l) -> 1
        else -> 0
    }
}

/**
 * True for a rendition above 1080p (4K or 8K).
 *
 * A 1080p television cannot show it any better than its 1080p copy, so the
 * pickers hide these unless the panel exposes a 4K display mode.
 */
fun is4kQuality(label: String): Boolean = qualityTier(label) >= 5

/**
 * Provider renditions this panel should offer, best first.
 *
 * Hiding every 4K entry would leave a title with no choice at all when the
 * provider only published 4K copies, so the original list is returned in that
 * case. [keepLink] is the rendition already playing: it stays visible even when
 * it is 4K, because a picker that omits what is on screen reads as broken.
 */
fun playableQualityLinks(
    links: List<QualityLink>,
    keepLink: String = "",
): List<QualityLink> {
    if (links.isEmpty() || tv.vega.tv.data.DisplayCapability.supports4k()) return links
    val visible = links.filterNot { is4kQuality(it.label) }
    if (visible.isEmpty()) return links
    if (keepLink.isBlank() || visible.any { it.link == keepLink }) return visible
    val playing = links.firstOrNull { it.link == keepLink } ?: return visible
    return (visible + playing).sortedByDescending { qualityScore(it.label) }
}

/** Size in megabytes named by a card ("80.2GB", "1.4 GiB", "700MB"), or 0. */
fun qualitySizeMb(label: String): Int {
    val m = Regex("""(\d+(?:[.,]\d+)?)\s*(tb|tib|gb|gib|mb|mib)""", RegexOption.IGNORE_CASE)
        .find(label) ?: return 0
    val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
    val unit = m.groupValues[2].lowercase()
    return when {
        unit.startsWith("t") -> (value * 1_000_000).toInt()
        unit.startsWith("g") -> (value * 1_000).toInt()
        else -> value.toInt()
    }
}

/** Bigger is better: resolution dominates, file size breaks ties. */
fun qualityScore(label: String): Int =
    qualityTier(label) * 1_000_000 + qualitySizeMb(label).coerceIn(0, 999_999)

/** One provider-named rendition of the same title, as a whole alternate link. */
@Serializable
data class QualityLink(val label: String, val link: String)

/** "S01E02" / "Episode 12" — a title that names an episode, not a rendition. */
private val EPISODE_TITLE =
    Regex("""(?i)\b(?:s\d{1,3}\s*[._\- ]?\s*e\d{1,3}|episode\s*\d{1,4}|ep\s*\d{1,4})\b""")

/**
 * Whether a season entry's direct links are a movie's quality cards.
 *
 * Some providers ship a movie as one "season" whose direct links are renditions
 * named by quality ("4K HDR REMUX … 80.2GB"), with no `type` set. Two or more
 * titles that name a rendition and not an episode are the signal; an episode
 * list ("S01E02", "Episode 3") never qualifies.
 */
private fun looksLikeQualityCards(season: SeasonEntry): Boolean {
    if (!season.episodesLink.isNullOrBlank()) return false
    val links = season.directLinks
    if (links.size < 2) return false
    return links.count { d ->
        val title = d.title.trim()
        title.isNotBlank() &&
            !EPISODE_TITLE.containsMatchIn(title) &&
            (qualityTier(title) > 0 || qualitySizeMb(title) > 0)
    } >= 2
}

/**
 * A movie, including the providers that leave `type` blank. Shows are only
 * treated as movies when every entry is a clearly-labelled rendition.
 */
fun isMovieInfo(info: VegaInfo?): Boolean {
    if (info == null) return false
    if (info.type.equals("movie", true)) return true
    if (info.type.isNotBlank()) return false
    val list = info.linkList
    return when {
        list.isEmpty() -> false
        list.size == 1 -> looksLikeQualityCards(list[0])
        else -> list.all { it.episodesLink.isNullOrBlank() && it.directLinks.size == 1 } &&
            list.all { !it.quality.isNullOrBlank() }
    }
}

/**
 * Movie quality cards → player Quality entries.
 *
 * Providers model a movie's renditions in two shapes, and the viewer should see
 * the same card titles the detail page shows ("4K HDR REMUX HEVC H265 IMAX
 * 80.2GB"), not a bare "2160p":
 *
 *  1. One season whose direct links are the quality cards (the usual shape).
 *  2. Several seasons, each quality label in `quality`/`title` with one link.
 *
 * Shows are excluded by the provider's own `type`; when the type is missing,
 * only the movie shape (no episode lists and a quality label on every entry)
 * qualifies, so "Season 2" is never mistaken for a rendition. The result is
 * ordered best-first: resolution tier, then file size.
 */
fun qualityLinksOf(info: VegaInfo?): List<QualityLink> {
    if (info == null) return emptyList()
    val list = info.linkList
    if (list.isEmpty()) return emptyList()
    if (!isMovieInfo(info)) return emptyList()

    // Shape 1: the detail page's cards live in directLinks.
    val fromCards = list.flatMap { season ->
        val seasonLabel = season.quality?.trim().orEmpty().ifBlank { season.title.trim() }
        season.directLinks.mapNotNull { d ->
            val link = d.link.trim()
            if (link.isBlank()) return@mapNotNull null
            val label = richQualityLabel(d.title, seasonLabel)
            if (label.isBlank()) null else QualityLink(label, link)
        }
    }
    // Shape 2: one rendition per season entry.
    val base = fromCards.ifEmpty {
        list.mapNotNull { season ->
            val link = season.directLinks.firstOrNull()?.link?.trim().orEmpty()
            val label = season.quality?.trim().orEmpty()
                .ifBlank { season.title.trim() }
                .ifBlank { season.directLinks.firstOrNull()?.title?.trim().orEmpty() }
            if (link.isBlank() || label.isBlank()) null else QualityLink(label, link)
        }
    }
    return base.distinctBy { it.link }
        .sortedWith(
            compareByDescending<QualityLink> { qualityTier(it.label) }
                .thenByDescending { qualitySizeMb(it.label) },
        )
}

/**
 * Prefer whichever of the card title and the season label actually names a
 * rendition; a generic card title ("Download") must not hide "4K HDR … 80GB".
 */
private fun richQualityLabel(card: String, season: String): String {
    val c = card.trim()
    val s = season.trim()
    if (c.isBlank()) return s
    if (s.isBlank()) return c
    val cRich = qualityTier(c) > 0 || qualitySizeMb(c) > 0
    val sRich = qualityTier(s) > 0 || qualitySizeMb(s) > 0
    return when {
        cRich -> c
        sRich -> s
        else -> c
    }
}
