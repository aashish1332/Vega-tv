package tv.vega.tv.data.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import tv.vega.tv.data.net.NetClients

/**
 * Network layer for provider extensions. Mirrors ExtensionManager.fetchManifest
 * + downloadProviderModules: manifest.json (10s, 24h cache) and
 * {dist/<value> | manifest path}/{posts,meta,stream,catalog}.js required +
 * {episodes,settings} optional (15s each). 2MB module cap like web sandbox.
 */
class ProviderApi(
    private val store: ExtensionStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Mobile's axios timeouts: 10s manifest, 15s per module file. */
    private fun client(timeoutSec: Long) =
        NetClients.http.newBuilder()
            .callTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            .build()

    suspend fun refreshManifest(source: ProviderSource, force: Boolean = false): List<ProviderExtension> =
        withContext(Dispatchers.IO) {
            if (!force && !store.manifestExpired(source.author)) {
                val cached = store.manifestCache(source.author)
                if (cached.isNotEmpty()) return@withContext cached
            }
            // Custom sources carry their own manifestUrl (".../custom.json"
            // also keeps the query string the user pasted).
            val manifestBase = source.manifestUrl ?: "${source.url}/manifest.json"
            val manifestUrl = if (force) {
                manifestBase + (if ('?' in manifestBase) "&" else "?") + "t=${System.currentTimeMillis()}"
            } else {
                manifestBase
            }
            val req = Request.Builder()
                .url(manifestUrl)
                .header("Cache-Control", if (force) "no-cache" else "max-age=3600")
                .build()
            val resp = client(10).newCall(req).execute()
            if (resp.code == 403 || resp.code == 429) throw ProviderRateLimited()
            require(resp.isSuccessful) { "Manifest fetch failed: HTTP ${resp.code}" }
            val body = resp.body?.string().orEmpty()
            require(body.length < 2 * 1024 * 1024) { "Manifest too large" }
            val list = json.decodeFromString<List<ProviderExtension>>(body)
                // A path that leaves the source folder is unsafe; skip that entry.
                .filter { e -> e.path.isNullOrEmpty() || ProviderUrls.isValidProviderPath(e.path) }
                .map {
                    it.copy(
                        source = ProviderSourceRef(source.author, source.url),
                        path = it.path?.takeIf { p -> p.isNotEmpty() },
                    )
                }
            store.setManifestCache(source.author, list)
            store.setAvailable(source.author, list.filter { !it.disabled })
            list
        }

    suspend fun downloadModules(source: ProviderSource, ext: ProviderExtension) =
        withContext(Dispatchers.IO) {
            val kinds = listOf("posts" to true, "meta" to true, "stream" to true, "catalog" to true, "episodes" to false, "settings" to false)
            val got = mutableMapOf<String, String?>()
            val base = ProviderUrls.filesUrl(source.url, ext.value, ext.path)
            for ((kind, required) in kinds) {
                val url = "$base/$kind.js?t=${System.currentTimeMillis()}"
                try {
                    val req = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
                    client(15).newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            if (required) throw ProviderModuleMissing("$kind: HTTP ${resp.code}")
                            got[kind] = null
                        } else {
                            val code = resp.body?.string().orEmpty()
                            require(code.length < 2 * 1024 * 1024) { "$kind module exceeds 2MB" }
                            got[kind] = code
                            store.writeModuleCode(ext.value, source.author, kind, code)
                        }
                    }
                } catch (e: ProviderModuleMissing) { throw e }
                catch (e: Exception) {
                    if (required) throw e
                    got[kind] = null
                }
            }
            store.putModule(
                ProviderModules(
                    value = ext.value, sourceAuthor = source.author, version = ext.version,
                    posts = got["posts"], meta = got["meta"], stream = got["stream"],
                    catalog = got["catalog"], episodes = got["episodes"], settings = got["settings"],
                    cachedAt = System.currentTimeMillis(),
                ),
            )
            store.install(ext)
        }

    class ProviderRateLimited : Exception("GitHub rate limit exceeded. Wait a few minutes and retry.")
    class ProviderModuleMissing(msg: String) : Exception(msg)
}
