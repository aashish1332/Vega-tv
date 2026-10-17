package tv.vega.tv.data.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import tv.vega.tv.data.net.NetClients

data class TmdbMeta(
    val title: String = "",
    val overview: String = "",
    val rating: String = "",
    val year: String = "",
    val genres: List<String> = emptyList(),
    val backdrop: String = "",
    val poster: String = "",
)

/**
 * Cinemeta-style enrich (mirrors web useEnhancedMetadata + tmdb story fetch):
 * direct TMDB API with the user's key (same TmdbApiKeyPreference setting).
 * No key → no enrich (graceful, provider data stands alone).
 */
object TmdbApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val client get() = NetClients.http

    suspend fun enrich(imdbId: String, type: String, apiKey: String): TmdbMeta? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() || imdbId.isBlank()) return@withContext null
            runCatching {
                val findUrl = "https://api.themoviedb.org/3/find/$imdbId?api_key=$apiKey&external_source=imdb_id"
                val body = get(findUrl)
                val obj = json.parseToJsonElement(body).jsonObject
                val key = if (type.contains("series", true) || type.contains("tv", true)) "tv_results" else "movie_results"
                val arr = obj[key]?.jsonArray ?: obj["movie_results"]?.jsonArray
                val first = arr?.firstOrNull()?.jsonObject ?: return@runCatching null
                val mediaType = if (key == "tv_results") "tv" else "movie"
                val id = first["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val det = json.parseToJsonElement(get("https://api.themoviedb.org/3/$mediaType/$id?api_key=$apiKey")).jsonObject
                fun str(vararg ks: String) = ks.firstNotNullOfOrNull { det[it]?.jsonPrimitive?.contentOrNull }.orEmpty()
                val title = str("name", "title")
                val date = str("first_air_date", "release_date")
                val vote = det["vote_average"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                TmdbMeta(
                    title = title,
                    overview = str("overview"),
                    rating = if (vote != null && vote > 0) String.format("%.1f/10", vote) else "",
                    year = date.take(4),
                    genres = det["genres"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull } ?: emptyList(),
                    backdrop = det["backdrop_path"]?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/original$it" }.orEmpty(),
                    poster = det["poster_path"]?.jsonPrimitive?.contentOrNull?.let { "https://image.tmdb.org/t/p/w500$it" }.orEmpty(),
                )
            }.getOrNull()
        }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { r ->
            require(r.isSuccessful) { "TMDB HTTP ${r.code}" }
            return r.body?.string().orEmpty()
        }
    }
}
