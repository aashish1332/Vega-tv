package tv.vega.tv.data.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            currentCoroutineContext().ensureActive()
            val resp = client(10).newCall(req).execute()
            currentCoroutineContext().ensureActive()
            if (resp.code == 403 || resp.code == 429) throw ProviderRateLimited()
            require(resp.isSuccessful) { "Manifest fetch failed: HTTP ${resp.code}" }
            val body = resp.body?.string().orEmpty()
            resp.close()
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

    /**
     * Fetch every module, validate the set, and only then write it.
     *
     * The old implementation wrote each file as it arrived. A timeout on
     * `stream.js` after `meta.js` had been replaced left a provider running
     * new metadata against old stream code — and a provider whose manifest
     * version had *not* changed (so the next pass never retried) could stay in
     * that state indefinitely. Now a failed or half-propagated fetch leaves the
     * previous working install untouched.
     */
    suspend fun downloadModules(source: ProviderSource, ext: ProviderExtension) =
        withContext(Dispatchers.IO) {
            val required = REQUIRED_MODULES
            val optional = OPTIONAL_MODULES
            val base = ProviderUrls.filesUrl(source.url, ext.value, ext.path)
            val got = LinkedHashMap<String, String>()
            for (kind in required) {
                currentCoroutineContext().ensureActive()
                got[kind] = downloadModule(base, kind, ext, required = true)!!
            }
            val absent = ArrayList<String>(2)
            for (kind in optional) {
                currentCoroutineContext().ensureActive()
                val code = downloadModule(base, kind, ext, required = false)
                if (code == null) absent += kind else got[kind] = code
            }
            // A source that is mid-publish can answer with one module from the
            // new version and the rest from the old one. Reject the set instead
            // of committing a provider that cannot work.
            validateDownloadedModuleSet(got, ext.value, ext.version)
            currentCoroutineContext().ensureActive()
            for ((kind, code) in got) {
                store.writeModuleCode(ext.value, source.author, kind, code)
            }
            for (kind in absent) {
                store.deleteModuleCode(ext.value, source.author, kind)
            }
            currentCoroutineContext().ensureActive()
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

    /**
     * One module with the mobile app's retry rule: a version-stamp mismatch is
     * usually a CDN still serving the previous publish, so fetch it once more
     * with a different cache key. A required file that is still missing or
     * unsynchronised aborts the whole install.
     */
    private suspend fun downloadModule(
        base: String,
        kind: String,
        ext: ProviderExtension,
        required: Boolean,
    ): String? {
        var mismatch: ProviderModuleOutOfSync? = null
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val version = java.net.URLEncoder.encode(ext.version, "UTF-8")
            val url = "$base/$kind.js?v=$version&t=${System.currentTimeMillis()}-$attempt"
            val req = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .build()
            val call = client(15).newCall(req)
            call.execute().use { resp ->
                currentCoroutineContext().ensureActive()
                if (!resp.isSuccessful) {
                    // Optional files are allowed to be gone: upstream deleted
                    // the parser, and the stale local copy must go with it.
                    if (!required && (resp.code == 404 || resp.code == 410)) return null
                    throw ProviderModuleMissing("$kind: HTTP ${resp.code}")
                }
                val code = resp.body?.string().orEmpty()
                require(code.isNotBlank()) { "$kind module is empty" }
                require(code.length < 2 * 1024 * 1024) { "$kind module exceeds 2MB" }
                try {
                    validateDownloadedModule(code, ext.value, ext.version, kind)
                    return code
                } catch (e: ProviderModuleOutOfSync) {
                    // Retry once; the second failure is reported to the caller.
                    mismatch = e
                }
            }
        }
        throw mismatch ?: ProviderModuleMissing("$kind: empty response")
    }

    class ProviderRateLimited : Exception("GitHub rate limit exceeded. Wait a few minutes and retry.")
    class ProviderModuleMissing(msg: String) : Exception(msg)
}
