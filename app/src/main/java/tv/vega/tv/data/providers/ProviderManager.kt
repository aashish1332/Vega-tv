package tv.vega.tv.data.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import tv.vega.tv.data.model.CatalogEntry
import tv.vega.tv.data.model.EpisodeItem
import tv.vega.tv.data.model.SeasonEntry
import tv.vega.tv.data.model.VegaInfo
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.data.model.VegaStream
import tv.vega.tv.player.PlaybackSession

private const val TAG = "VegaProviderManager"

/**
 * Upstream aborts a stream fetch after 45s (`fetchStreamList`). The old 25s
 * budget killed providers that probe several servers, and it killed the call
 * before a provider could hand a Cloudflare challenge to the user.
 */
private const val GET_STREAM_TIMEOUT_MS = 45_000L

/** Upstream `updateCheckIntervalMs`: providers are refreshed at most 4x/day. */
private const val UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L

/** Failed passes retry sooner than a full six-hour cycle. */
private const val UPDATE_RETRY_MS = 15 * 60 * 1000L

/**
 * The UA providers get as `providerContext.commonHeaders['User-Agent']`.
 * The challenge WebView must present the same one: Cloudflare binds its
 * clearance cookie to the User-Agent, so solving the page with the stock
 * WebView UA would earn a cookie the provider's HTTP stack cannot use.
 */
internal const val COMMON_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"

/**
 * Kotlin port of web ProviderManager + ExtensionManager flows:
 * seed source → manifest → install modules → sandboxed invokes.
 * Same semantics: 45s stream timeout, array guards, disabled filtering.
 */
class ProviderManager(appContext: Context) {
    private val ctx = appContext.applicationContext
    val store = ExtensionStore(ctx)
    /**
     * The same store the sandbox reaches as `providerContext.kvStore`.
     *
     * Exposed because a provider's settings *are* kvStore entries — see
     * [tv.vega.tv.ui.settings.ProviderSettingsViewModel]. Writing them anywhere
     * else would save values the provider's own modules never read back.
     */
    val kv = ProviderKv(ctx)
    private val api = ProviderApi(store)
    val js = ProviderJs(ctx)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    data class Active(val ext: ProviderExtension, val source: ProviderSource)

    /**
     * Result of one automatic update pass. [checked] is the number of sources
     * whose manifest was fetched, not the number of providers.
     */
    data class UpdateReport(
        val checkedSources: Int,
        val updated: Int,
        val failed: Int,
        val skipped: Boolean = false,
    )

    private val updateMutex = Mutex()

    /**
     * Check every installed provider against its source manifest and download
     * newer modules, mirroring the mobile app's six-hour update pass.
     *
     * Throttled: a normal Home load within six hours returns immediately, so
     * startup stays fast. A failed pass only records a short retry window, so
     * one unreachable source cannot postpone every provider for six hours.
     */
    suspend fun checkForUpdates(force: Boolean = false): UpdateReport = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            ensureSeeded()
            val now = System.currentTimeMillis()
            val last = runCatching { store.lastUpdateCheck() }.getOrDefault(0L)
            val retryAt = runCatching { store.updateRetryAt() }.getOrDefault(0L)
            if (!force && (now < retryAt || now - last < UPDATE_INTERVAL_MS)) {
                return@withLock UpdateReport(checkedSources = 0, updated = 0, failed = 0, skipped = true)
            }
            val sources = store.sources()
            val installed = store.installed()
            var checked = 0
            var updated = 0
            var failed = 0
            var aborted = false
            for (source in sources) {
                // Cancellation is cooperative and a blocking manifest fetch
                // cannot be interrupted; check the real playback state as the
                // last line of defence before spending another request/disk
                // write while a film is starting.
                if (PlaybackSession.playerActive || PlaybackSession.playbackRequested) {
                    aborted = true
                    break
                }
                val available = runCatching {
                    api.refreshManifest(source, force = true)
                }.onFailure {
                    failed++
                    Log.w(TAG, "Update manifest failed for ${source.author}: ${it.message}")
                }.getOrNull() ?: continue
                checked++
                val latestByValue = available.associateBy { it.value }
                for (ext in installed.filter { it.source.author == source.author }) {
                    if (PlaybackSession.playerActive || PlaybackSession.playbackRequested) {
                        aborted = true
                        break
                    }
                    val latest = latestByValue[ext.value] ?: continue
                    if (latest.disabled) continue
                    if (!isNewerProviderVersion(latest.version, ext.version)) continue
                    runCatching {
                        // downloadModules also writes the installed provider
                        // metadata (including a renamed icon/path).
                        api.downloadModules(source, latest)
                    }.onSuccess {
                        updated++
                        Log.i(TAG, "Updated ${ext.value} ${ext.version} -> ${latest.version}")
                    }.onFailure {
                        failed++
                        Log.w(TAG, "Update failed for ${ext.value}: ${it.message}")
                    }
                }
                if (aborted) break
            }
            val finishedAt = System.currentTimeMillis()
            if (aborted) {
                // Playback interrupted this pass. Do not record it as a full
                // six-hour check; try again when the viewer has left the film.
                runCatching { store.setUpdateRetryAt(finishedAt + UPDATE_RETRY_MS) }
            } else if (failed == 0) {
                runCatching { store.setLastUpdateCheck(finishedAt) }
                runCatching { store.setUpdateRetryAt(0L) }
            } else {
                runCatching { store.setUpdateRetryAt(finishedAt + UPDATE_RETRY_MS) }
            }
            UpdateReport(checkedSources = checked, updated = updated, failed = failed)
        }
    }

    suspend fun active(): Active? = withContext(Dispatchers.IO) {
        val ref = store.activeRef() ?: return@withContext null
        val installed = store.installed()
        val ext = installed.firstOrNull { "${it.source.author}:${it.value}" == ref }
            ?: return@withContext null
        val src = store.sources().firstOrNull { it.author == ext.source.author }
            ?: return@withContext null
        Active(ext, src)
    }

    suspend fun setActive(ext: ProviderExtension) {
        store.setActiveRef("${ext.source.author}:${ext.value}")
    }

    /**
     * Point the active provider at [ref], which is either "author:value" or the
     * bare provider value (history/watchlist entries store the value alone).
     *
     * A post opened from a mixed Home page carries the provider it came from;
     * resolving its link against whatever provider happened to be active
     * returned a *different* title's detail page. No-op when [ref] is blank or
     * its provider is not installed.
     */
    suspend fun activate(ref: String) = withContext(Dispatchers.IO) {
        if (ref.isBlank()) return@withContext
        val installed = store.installed()
        val ext = installed.firstOrNull { "${it.source.author}:${it.value}" == ref }
            ?: installed.firstOrNull { it.value == ref }
            ?: return@withContext
        store.setActiveRef("${ext.source.author}:${ext.value}")
    }

    /** Every installed provider (the sidebar lists them for switching). */
    suspend fun installed(): List<ProviderExtension> = withContext(Dispatchers.IO) { store.installed() }

    /**
     * Mobile (ExtensionManager.initialize) seeds the official `Zenda-Cross`
     * source on a fresh install only: an empty-but-written list means the user
     * deliberately removed their sources and must stay empty. Idempotent and
     * cheap after the first call, so it is awaited on the paths that can run
     * before any user action (Home load, Providers screen).
     */
    private val seedLock = kotlinx.coroutines.sync.Mutex()
    private var seeded = false

    suspend fun ensureSeeded() {
        if (seeded) return
        seedLock.withLock {
            if (seeded) return
            withContext(Dispatchers.IO) {
                if (!store.sourcesKeyPresent()) {
                    runCatching {
                        val s = ProviderUrls.parse(ProviderUrls.DEFAULT_AUTHOR)
                        store.addSource(s.author, s.url)
                    }
                }
            }
            seeded = true
        }
    }

    // -- source management (the provider manager's own surface) --

    suspend fun sources(): List<ProviderSource> = withContext(Dispatchers.IO) { store.sources() }

    /** Parse and store a source, then pull its manifest. Returns the provider count. */
    suspend fun addSource(input: String): Int = withContext(Dispatchers.IO) {
        val s = ProviderUrls.parse(input)
        store.addSource(s.author, s.url)
        api.refreshManifest(s, force = true).count { !it.disabled }
    }

    /** Drop a source. Refuses when an installed provider still depends on it. */
    suspend fun removeSource(author: String) = withContext(Dispatchers.IO) {
        val stillUsed = store.installed().any { it.source.author == author }
        require(!stillUsed) { "Remove the providers from $author first" }
        val cur = store.sources().toMutableList()
        cur.removeAll { it.author == author }
        store.setSources(cur)
    }

    /** Manifest for one specific source (not just the default). */
    suspend fun refreshSource(source: ProviderSource, force: Boolean = false): List<ProviderExtension> =
        withContext(Dispatchers.IO) { api.refreshManifest(source, force) }

    suspend fun refreshManifest(force: Boolean = false): List<ProviderExtension> {
        val src = store.defaultSource() ?: error("No provider source")
        return api.refreshManifest(src, force)
    }

    suspend fun install(ext: ProviderExtension) {
        val src = store.sources().firstOrNull { it.author == ext.source.author }
            ?: error("Unknown source ${ext.source.author}")
        api.downloadModules(src, ext)
    }

    private suspend fun moduleCode(a: Active, kind: String): String {
        store.readModuleCode(a.ext.value, a.source.author, kind)?.takeIf { it.isNotBlank() }
            ?.let { return it }
        // Re-download if evicted.
        install(a.ext)
        return store.readModuleCode(a.ext.value, a.source.author, kind)
            ?: error("Missing $kind module for ${a.ext.value}")
    }

    private fun commonHeaders(): String = JSONObject(
        mapOf(
            "User-Agent" to COMMON_USER_AGENT,
            "Accept-Language" to "en-US,en;q=0.9",
        ),
    ).toString()

    /** Challenge pages surface as WAF_REQUIRED:host:url from the JS runtime. */
    class WafRequired(val host: String, val url: String) :
        Exception("Cloudflare challenge at $host. Solve it, then retry.")

    private fun wafOrThrow(e: Throwable): Nothing {
        val m = e.message.orEmpty()
        if (m.contains("WAF_REQUIRED:")) {
            val parts = m.substringAfter("WAF_REQUIRED:").split(":", limit = 2)
            throw WafRequired(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
        }
        throw e
    }

    suspend fun getCatalog(a: Active): Pair<List<CatalogEntry>, List<CatalogEntry>> {
        val code = moduleCode(a, "catalog")
        // Old bundles export a static array, which the lightweight eval path
        // reads without touching the network. Newer bundles (Kartoons 2.x)
        // export an async `catalog` function that establishes an account
        // session first; that has to run through the full invoke runtime where
        // providerContext.openWebView exists.
        val static = js.evalCatalog(code)
        val catalog = if (static.catalogIsFunction) {
            invokeCatalogList(a, code, "catalog")
        } else {
            static.catalog
        }
        val genres = if (static.genresIsFunction) {
            runCatching { invokeCatalogList(a, code, "genres") }.getOrDefault(emptyList())
        } else {
            static.genres
        }
        return catalog to genres
    }

    private suspend fun invokeCatalogList(
        a: Active,
        code: String,
        exportName: String,
    ): List<CatalogEntry> {
        val raw = guarded {
            js.invoke(a.ext.value, code, exportName, "{}", commonHeaders())
        }
        return decodeOrThrow(requireArray(raw, a.ext.value, exportName), a.ext.value, exportName)
    }

    suspend fun getPosts(a: Active, filter: String, page: Int): List<VegaPost> {
        val code = moduleCode(a, "posts")
        val params = JSONObject().put("filter", filter).put("page", page).put("providerValue", a.ext.value).toString()
        val raw = guarded { js.invoke(a.ext.value, code, "getPosts", params, commonHeaders()) }
        return requirePosts(raw, a.ext.value, "getPosts")
    }

    suspend fun getSearchPosts(a: Active, query: String, page: Int): List<VegaPost> {
        val code = moduleCode(a, "posts")
        val params = JSONObject().put("searchQuery", query).put("page", page).put("providerValue", a.ext.value).toString()
        // Some providers omit search; fall back to empty (mirrors web optional).
        val raw = try {
            guarded { js.invoke(a.ext.value, code, "getSearchPosts", params, commonHeaders()) }
        } catch (e: Exception) {
            if ((e.message ?: "").contains("missing export")) return emptyList()
            throw e
        }
        return requirePosts(raw, a.ext.value, "getSearchPosts")
    }

    suspend fun getMeta(a: Active, link: String): VegaInfo {
        val code = moduleCode(a, "meta")
        // Upstream passes `provider` alongside `link`; several bundles branch on it
        // to decide which site variant to scrape, so omitting it silently returns
        // the wrong (or an empty) show.
        val params = JSONObject().put("link", link).put("provider", a.ext.value).toString()
        val raw = guarded { js.invoke(a.ext.value, code, "getMeta", params, commonHeaders()) }
        return decodeOrThrow(raw, a.ext.value, "getMeta")
    }

    suspend fun getEpisodes(a: Active, url: String): List<EpisodeItem> {
        val meta = store.readModuleCode(a.ext.value, a.source.author, "episodes")
        val code = meta ?: return emptyList()
        val params = JSONObject().put("url", url).toString()
        val raw = try {
            guarded { js.invoke(a.ext.value, code, "getEpisodes", params, commonHeaders()) }
        } catch (e: Exception) {
            if ((e.message ?: "").contains("missing export")) return emptyList()
            throw e
        }
        val arr = requireArray(raw, a.ext.value, "getEpisodes")
        return decodeOrThrow(arr, a.ext.value, "getEpisodes")
    }

    /**
     * `isDownload` is part of the upstream contract (`ProviderManager.getStream`
     * normalises it with `Boolean(isDownload)`). Providers that hand out a
     * temporary, single-use signed URL need to know the stream is going to a
     * file rather than to a player, or the download 403s on the second range
     * request while playback is fine.
     */
    suspend fun getStream(a: Active, link: String, type: String, isDownload: Boolean = false): List<VegaStream> {
        val code = moduleCode(a, "stream")
        val params = JSONObject()
            .put("link", link)
            .put("type", type)
            .put("isDownload", isDownload)
            .toString()
        val raw = guarded {
            js.invoke(a.ext.value, code, "getStream", params, commonHeaders(), timeoutMs = GET_STREAM_TIMEOUT_MS)
        }
        val arr = requireArray(raw, a.ext.value, "getStream")
        return decodeOrThrow<List<VegaStream>>(arr, a.ext.value, "getStream")
            .filterNot { it.isDownloadOnlyDrive() }
            .map { withJarCookie(it) }
    }

    /**
     * HubCloud-based providers expose the Google Drive mirror as "GDrive
     * (download only)" / "G-Drive (download only)". Those links open a browser
     * download page rather than a playable stream, so on TV they only ever
     * became dead entries in the server list.
     */
    private fun VegaStream.isDownloadOnlyDrive(): Boolean =
        server.contains("download only", ignoreCase = true)

    /**
     * Mirrors mobile `withJarCookies`: streams get the site's cookies (a WAF
     * clearance, typically) as a Cookie header unless the provider set one
     * itself. The TV app has a single process-wide CookieManager shared with
     * the challenge WebView, so this reads that jar.
     */
    private fun withJarCookie(stream: VegaStream): VegaStream {
        val link = stream.link
        if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) return stream
        if (stream.headers.keys.any { it.equals("Cookie", true) }) return stream
        val cookie = runCatching { android.webkit.CookieManager.getInstance().getCookie(link).orEmpty() }
            .getOrDefault("")
        if (cookie.isBlank()) return stream
        return stream.copy(headers = stream.headers + ("Cookie" to cookie))
    }

    private fun requirePosts(raw: String, provider: String, op: String): List<VegaPost> {
        val arr = requireArray(raw, provider, op)
        return decodeOrThrow(arr, provider, op)
    }

    /**
     * Do not turn a provider schema/decode failure into an empty catalog or an
     * empty stream list. Empty data looks like missing content to users and
     * prevents the player from offering another server; this message preserves
     * the failing provider stage so it can be retried or diagnosed.
     */
    private inline fun <reified T> decodeOrThrow(raw: String, provider: String, op: String): T =
        runCatching { json.decodeFromString<T>(raw) }.getOrElse { e ->
            throw IllegalStateException(
                "Provider $provider $op returned invalid data: ${e.message ?: "decode failed"}",
                e,
            )
        }

    /**
     * Upstream's `Provider ${providerValue} ${operation} returned ${actualType},
     * expected an array` (asserted verbatim by its `providerErrorPropagation`
     * test). The port used to report `non-array: <first 120 chars>`, which
     * pasted a provider's own HTML error page into a TV-width message and buried
     * the one fact that matters — which of the call it was.
     */
    private fun requireArray(raw: String, provider: String, op: String): String {
        val t = raw.trim()
        // "null"/"undefined" used to be reported as "returned non-array: null",
        // which reads like a shape problem. It actually means the export resolved
        // to nothing at all — almost always a failed or unresolved fetch upstream
        // of the array. Name it so the stage is unambiguous.
        if (t == "null" || t == "undefined" || t.isEmpty()) {
            throw IllegalStateException("Provider $provider $op returned no result (undefined)")
        }
        require(t.startsWith("[")) {
            "Provider $provider $op returned ${jsonTypeOf(t)}, expected an array"
        }
        return t
    }

    /** JSON-ish type name for the error message, matching JS `typeof` vocabulary. */
    private fun jsonTypeOf(t: String): String = when {
        t.startsWith("{") -> "object"
        t.startsWith("\"") -> "string"
        t == "true" || t == "false" -> "boolean"
        t.toDoubleOrNull() != null -> "number"
        else -> "unknown"
    }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            wafOrThrow(e)
        }
    }

    /**
     * Upstream `getSettingsSchema` — the provider's own configuration UI, declared
     * by the bundle and fetched on demand so a user is never asked for a value
     * before seeing what it is for.
     *
     * `settings.js` is a separate module that most providers ship and none are
     * required to: a missing module, a missing export, or a schema that simply
     * isn't an array all mean "this provider has nothing to configure", and that
     * is a normal outcome, not a failure. Upstream returns `[]` in every one of
     * those cases, so the Providers screen hides the settings entry rather than
     * showing an error the user can't act on.
     *
     * The 6s timeout is upstream's. Unlike a content fetch, a schema is a static
     * export that must resolve near-instantly; a slow one means the bundle is
     * broken, and waiting 120s to render an empty settings list is worse than
     * showing none.
     */
    suspend fun getSettingsSchema(a: Active): List<SettingsField> {
        val code = store.readModuleCode(a.ext.value, a.source.author, "settings") ?: return emptyList()
        val raw = try {
            js.invoke(a.ext.value, code, "getSettingsSchema", "{}", commonHeaders(), timeoutMs = 6_000)
        } catch (e: Exception) {
            Log.i(TAG, "getSettingsSchema unavailable for ${a.ext.value}: ${e.message}")
            return emptyList()
        }
        val t = raw.trim()
        if (!t.startsWith("[")) return emptyList()
        return runCatching { json.decodeFromString<List<SettingsField>>(t) }
            .getOrElse {
                Log.i(TAG, "getSettingsSchema unparseable for ${a.ext.value}: ${it.message}")
                emptyList()
            }
    }

    // Model bridges: provider JSON uses linkList.directLinks with {title,link,type}.
    // VegaModels mirrors those names, so direct decode works; this helper keeps
    // the detail screen code stable if a provider nests differently.
    fun seasonsOf(info: VegaInfo): List<SeasonEntry> = info.linkList
}
