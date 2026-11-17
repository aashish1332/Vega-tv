package tv.vega.tv.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Upstream `Post.aspectRatio?: number | string`. Several providers return
 * `aspectRatio: 16 / 9` (a JSON number), which failed strict String decoding
 * (`Expected quotation mark`). Accept both shapes and keep the numeric value
 * as its literal text; [tv.vega.tv.ui.common.parseAspectRatio] parses either a
 * "16:9"/"16/9" ratio or a bare float.
 */
object StringOrNumberSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringOrNumber", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        val el = json.decodeJsonElement()
        return when {
            el is kotlinx.serialization.json.JsonNull -> ""
            el is JsonPrimitive -> el.content
            else -> el.toString()
        }
    }
}

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
    @Serializable(with = StringOrNumberSerializer::class)
    val aspectRatio: String? = null,
    val borderRadius: Float? = null,
    val tag: String? = null,
    val cornerTag: String? = null,
    val badge: String? = null,
    /**
     * "author:value" of the provider this row was listed by, set by the
     * repository when results from several providers are mixed. Display code
     * must use [provider] instead; this is the handoff that points the app's
     * active provider at the origin before opening the post.
     *
     * Transient: it is app state, not part of the provider JSON contract.
     */
    @Transient
    val origin: String = "",
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

/**
 * Upstream providers/types.ts `StreamDrm`.
 *
 * New provider releases attach this to a `getStream` result (inline ClearKey
 * for live DASH, or a Widevine/PlayReady license endpoint). Without the field
 * the keys were silently dropped and Media3 reported
 * `ERROR_CODE_DRM_SCHEME_UNSUPPORTED` on a stream that was perfectly playable.
 * Nullable collections: the JSON is provider-authored, and a `null` where an
 * object is expected must not fail the whole stream list.
 */
@Serializable
data class StreamDrm(
    /** `clearkey`, `widevine`, `playready` or `fairplay`. */
    val type: String = "",
    val licenseServer: String? = null,
    /** License-request headers, separate from the stream/manifest headers. */
    val headers: Map<String, String>? = null,
    /** 32-char hex key ID -> 32-char hex key. */
    val clearKeys: Map<String, String>? = null,
    val multiDrm: Boolean? = null,
    val contentId: String? = null,
    val certificateUrl: String? = null,
    val base64Certificate: Boolean? = null,
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
    val drm: StreamDrm? = null,
    val skip: List<SkipInterval> = emptyList(),
)

/**
 * Identity of one played rendition across sessions.
 *
 * The URL is deliberately excluded when a server/quality label exists: the
 * same mirror gets a fresh signed URL on every resolve, while the label pair
 * stays stable. Entries with no labels fall back to the URL so two anonymous
 * mirrors never collapse into one.
 */
fun VegaStream.identityKey(): String {
    val server = server.trim().lowercase()
    val quality = quality?.trim()?.lowercase().orEmpty()
    return if (server.isNotEmpty() || quality.isNotEmpty()) {
        "$server|$quality"
    } else {
        link.trim()
    }
}

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
    @Serializable(with = StringOrNumberSerializer::class)
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
    // "author:value" of the provider this row came from. Blank when the home
    // page shows a single provider; set while mixing so opening a post (or
    // "See all") can first point the app's active provider at the origin.
    val providerRef: String = "",
)
