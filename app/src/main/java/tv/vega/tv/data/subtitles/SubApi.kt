package tv.vega.tv.data.subtitles

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import tv.vega.tv.data.net.NetClients
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

@Serializable
data class SubResult(
    val lang: String = "",
    val title: String = "",
    val downloadUrl: String = "",
    val detail: String = "",
)

/**
 * OpenSubtitles REST search (same endpoint + VLSub UA as web
 * SearchSubtitles.tsx). Downloads are gunzipped to cache for ExoPlayer.
 */
object SubApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = NetClients.http

    suspend fun search(title: String, lang: String = "eng"): List<SubResult> =
        withContext(Dispatchers.IO) {
            val q = title.trim().ifBlank { return@withContext emptyList() }
            val url = "https://rest.opensubtitles.org/search/query-${java.net.URLEncoder.encode(q, "utf-8")}/sublanguageid-$lang"
            val req = Request.Builder().url(url).header("x-user-agent", "VLSub 0.10.2").build()
            val body = client.newCall(req).execute().use { r ->
                require(r.isSuccessful) { "HTTP ${r.code}" }
                r.body?.string().orEmpty()
            }
            // Loose parse: array of {ISO639, MovieName, SubFileName, SubDownloadLink, ...}.
            runCatching {
                json.decodeFromString<List<Map<String, String>>>(body).map {
                    SubResult(
                        lang = it["ISO639"].orEmpty(),
                        title = listOf(it["MovieName"].orEmpty(), it["SubFileName"].orEmpty()).filter { s -> s.isNotBlank() }.joinToString(" · "),
                        downloadUrl = it["SubDownloadLink"].orEmpty(),
                        detail = listOf(it["SeriesSeason"].orEmpty(), it["SeriesEpisode"].orEmpty()).filter { s -> s.isNotBlank() }.joinToString(" "),
                    )
                }.filter { it.downloadUrl.isNotBlank() }.take(20)
            }.getOrDefault(emptyList())
        }

    /** Download (+gunzip) a subtitle to cache, returning the file uri string. */
    suspend fun fetchToCache(ctx: android.content.Context, r: SubResult): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(r.downloadUrl).header("x-user-agent", "VLSub 0.10.2").build()
            val bytes = client.newCall(req).execute().use { resp ->
                require(resp.isSuccessful) { "HTTP ${resp.code}" }
                resp.body?.bytes() ?: error("Empty subtitle")
            }
            val raw = runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
            }.getOrElse { bytes }
            val name = "sub_${System.currentTimeMillis()}.vtt"
            val f = java.io.File(ctx.cacheDir, "subs/$name").apply { parentFile?.mkdirs() }
            f.writeBytes(raw)
            android.net.Uri.fromFile(f).toString()
        }
}
