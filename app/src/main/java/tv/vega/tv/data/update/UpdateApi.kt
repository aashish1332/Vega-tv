package tv.vega.tv.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import tv.vega.tv.data.net.NetClients

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
)

/**
 * GitHub release check (mirrors web About update flow, minus APK install:
 * TV builds ship separately, so we notify + deep-link to releases instead
 * of installing the phone APK).
 */
object UpdateApi {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/vega-org/vega-app/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        NetClients.http.newCall(req).execute().use { r ->
            if (r.code == 403 || r.code == 429) error("Rate limited, try later")
            require(r.isSuccessful) { "HTTP ${r.code}" }
            val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            ReleaseInfo(
                tag = o["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                body = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(1500),
                htmlUrl = o["html_url"]?.jsonPrimitive?.contentOrNull
                    ?: "https://github.com/vega-org/vega-app/releases/latest",
            )
        }
    }

    /** Compare semantic tags ("v3.2.6" > "v3.2.5"); non-numeric → false. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(t: String) = t.trimStart('v', 'V').split(".", "-", "+")
            .map { it.toIntOrNull() ?: -1 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val d = (r.getOrElse(i) { -1 }) - (l.getOrElse(i) { -1 })
            if (d != 0) return d > 0
        }
        return false
    }
}
