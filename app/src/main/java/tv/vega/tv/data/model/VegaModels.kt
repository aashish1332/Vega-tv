package tv.vega.tv.data.model

import kotlinx.serialization.Serializable

/**
 * Full parity with vega-app providers/types.ts. Field names match provider
 * JSON exactly so provider dist-module results decode without mapping.
 */
@Serializable
data class VegaPost(
    val title: String = "",
    val link: String = "",
    val image: String = "",
    val provider: String? = null,
    val aspectRatio: String? = null,
    val borderRadius: Float? = null,
    val tag: String? = null,
    val cornerTag: String? = null,
    val badge: String? = null,
)

@Serializable
data class SkipInterval(
    val title: String? = null,
    val from: Double = 0.0,
    val to: Double = 0.0,
)

@Serializable
data class SubtitleTrack(
    val title: String = "",
    val language: String = "",
    val type: String = "",
    val uri: String = "",
)

@Serializable
data class VegaStream(
    val server: String = "",
    val link: String = "",
    val type: String = "",
    val quality: String? = null,
    val tag: String? = null,
    val tags: List<String> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val skip: List<SkipInterval> = emptyList(),
)

@Serializable
data class DirectLink(
    val title: String = "",
    val link: String = "",
    val type: String? = null,
    val description: String? = null,
    val image: String? = null,
    val quickDownload: Boolean? = null,
    val skip: List<SkipInterval> = emptyList(),
)

@Serializable
data class SeasonEntry(
    val title: String = "",
    val quality: String? = null,
    val episodesLink: String? = null,
    val quickDownload: Boolean? = null,
    val directLinks: List<DirectLink> = emptyList(),
)

@Serializable
data class EpisodeItem(
    val id: String? = null,
    val title: String = "",
    val link: String = "",
    val sourceLink: String? = null,
    val description: String? = null,
    val image: String? = null,
    val quickDownload: Boolean? = null,
    val skip: List<SkipInterval> = emptyList(),
    // Local presentation helper, not from JSON.
    val number: Int = 0,
)

@Serializable
data class VegaInfo(
    val title: String = "",
    val image: String = "",
    val logo: String? = null,
    val poster: String? = null,
    val synopsis: String = "",
    val imdbId: String? = null,
    // Provider sends number|string; keep raw and read via tmdbIdString().
    val tmdbId: kotlinx.serialization.json.JsonElement? = null,
    val type: String = "",
    val quickDownload: Boolean? = null,
    val populateMeta: Boolean? = null,
    val webUrl: String? = null,
    val tags: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val rating: String? = null,
    val trailerUrl: String? = null,
    val linkList: List<SeasonEntry> = emptyList(),
)

@Serializable
data class VegaSubtitle(
    val url: String = "",
    val lang: String = "",
)

@Serializable
data class CatalogEntry(
    val title: String = "",
    val filter: String = "",
)

@Serializable
data class HomeRow(
    val title: String = "",
    val filter: String = "",
    val posts: List<VegaPost> = emptyList(),
    val error: String? = null,
)
