package tv.vega.tv.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import tv.vega.tv.data.net.NetClients

data class ReleaseInfo(
    val tag: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    /** First `.apk` asset of the release, when one was uploaded. */
    val apkUrl: String? = null,
    val apkName: String = "",
    val apkSize: Long = 0L,
)

/**
 * GitHub release check against this project's own repository.
 *
 * The web/mobile app checks vega-org/vega-app; a TV build pointing there
 * would offer the phone APK as an "update", which is why the home banner
 * used to name versions this app can never run. Releases here carry the TV
 * APK itself (see [ApkUpdater]), so "download and install" ends with the
 * app updating itself rather than a browser page.
 */
object UpdateApi {
    private const val REPO = "aashish1332/Vega-tv"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        NetClients.http.newCall(req).execute().use { r ->
            if (r.code == 403 || r.code == 429) error("Rate limited, try later")
            require(r.isSuccessful) { "HTTP ${r.code}" }
            val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            val apk = o["assets"]?.jsonArray.orEmpty()
                .mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                .firstOrNull { a ->
                    a["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", true) == true
                }
            ReleaseInfo(
                tag = o["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                body = o["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(1500),
                htmlUrl = o["html_url"]?.jsonPrimitive?.contentOrNull
                    ?: "https://github.com/$REPO/releases/latest",
                apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.contentOrNull,
                apkName = apk?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                apkSize = apk?.get("size")?.jsonPrimitive?.longOrNull ?: 0L,
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
