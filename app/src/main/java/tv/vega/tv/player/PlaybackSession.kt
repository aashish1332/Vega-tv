package tv.vega.tv.player

import tv.vega.tv.data.model.EpisodeItem
import tv.vega.tv.data.model.VegaInfo

object PlaybackSession {
    // Cheap handoff without parcelables (weak devices, large episode lists).
    var pendingLink: String = ""
    var pendingTitle: String = ""
    var pendingPoster: String = ""
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

    fun set(
        title: String,
        posterUrl: String,
        episodes: List<EpisodeItem>,
        index: Int,
        info: VegaInfo? = null,
    ) {
        this.title = title
        this.posterUrl = posterUrl
        this.episodes = episodes
        this.startIndex = if (episodes.isEmpty()) 0 else index.coerceIn(episodes.indices)
        this.resumePositionMs = 0L
        this.info = info
    }
}
