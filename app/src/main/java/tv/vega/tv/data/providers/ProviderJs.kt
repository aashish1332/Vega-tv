package tv.vega.tv.data.providers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tv.vega.tv.data.model.CatalogEntry
import tv.vega.tv.data.net.NetClients
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "VegaProvider"

/**
 * Upstream `SANDBOX_INVOKE_TIMEOUT_MS`. The port used 25s, which is not enough
 * for a provider that makes two or three sequential fetches on weak TV
 * hardware — the common case for a detail page, since the meta bundle usually
 * has to resolve a season list before it can return anything.
 */
private const val SANDBOX_INVOKE_TIMEOUT_MS = 120_000L

private const val URLS_JSON_URL =
    "https://raw.githubusercontent.com/Zenda-Cross/vega-providers/refs/heads/main/urls.json"

/** A providerGlobal snapshot beyond this is dropped rather than stored (512KB). */
private const val MAX_PROVIDER_STATE_CHARS = 512_000

/**
 * JS runner: a single hidden WebView (zero APK cost, already needed for WAF)
 * that executes unmodified provider bundles. Static modules (catalog) eval
 * directly; networked modules (posts/meta/stream/episodes) run inside the
 * provider_runtime.html realm with fetch/axios/cheerio/getBaseUrl/kv shims —
 * the same providerContext shape as the web sandboxWorker.
 *
 * Weak-TV: one lazily-created WebView, one invoke at a time (Mutex), fresh
 * realm per provider (like fresh worker per invoke), JSON-only results,
 * timeout with realm reload so a stuck provider can't wedge the app.
 */
class ProviderJs(appContext: Context) {
    private val ctx = appContext.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var runtimeReadyFor: String? = null

    /** True while [invoke] holds the realm; teardown must not cut one in half. */
    @Volatile
    private var invokeInFlight = false

    /** Serialises the in-flight flag with the teardown decision. */
    private val invokeState = Any()
    private var destroyAfterInvoke = false

    /** Export currently running in the realm; decides what may be aborted. */
    private var activeInvokeExport: String? = null

    /** Persistent, per-provider key/value store backing `providerContext.kvStore`. */
    private val kv = ProviderKv(ctx)

    /**
     * Per-provider `providerGlobal` snapshots, keyed by provider value.
     *
     * Mobile spawns a fresh worker per invoke and carries this state in and
     * back out; the TV realm persists across invokes of one provider, so the
     * snapshot is injected before the bundle is evaluated and refreshed from
     * whatever the bundle returned. In-memory only, exactly like mobile.
     */
    private val providerState = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The bridge backing the running runtime, when one is up.
     *
     * Every invoke re-primes the kv snapshot from disk in [primeKv], so a host-
     * side write is picked up on the next call regardless. This exists so the
     * provider settings screen can push an edit into the live store right away
     * rather than after the next reload, and so clearing the runtime does not
     * leave a stale bridge behind for a page that no longer exists.
     */
    @Volatile
    var activeBridge: ProviderHttp? = null
        private set

    /** Apply a host-side settings edit to the live runtime. Best-effort by design. */
    fun applyExternalKvWrite(providerValue: String, key: String, value: String) {
        runCatching { activeBridge?.applyKvSet(providerValue, key, value) }
    }

    fun applyExternalKvDelete(providerValue: String, key: String) {
        runCatching { activeBridge?.applyKvDelete(providerValue, key) }
    }

    /** Native bridge exposed to the runtime as `VegaHttp`. */
    @Volatile
    private var http: ProviderHttp? = null
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** Monotonic token; only the newest awaited load may resume (see ensureRuntime). */
    @Volatile
    private var loadGeneration = 0L

    /** Last runtime failure, surfaced on the Providers screen for on-device diagnosis. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Bounded ring of what the provider bundle actually did. Provider code is
     * third-party and runs with no logcat access on a TV, so its own
     * `console.log("vegaGetPosts url:", url)` and our per-request trace are the
     * only way to see the URL it built and whether the fetch returned bytes.
     */
    private val trace = java.util.concurrent.ConcurrentLinkedQueue<String>()

    @Synchronized
    private fun trace(line: String) {
        trace.add(line)
        while (trace.size > 40) trace.poll()
    }

    @Synchronized
    fun traceSnapshot(): List<String> = trace.toList()

    @Synchronized
    fun clearTrace() = trace.clear()

    private fun fail(msg: String): Nothing {
        lastError = msg
        Log.e(TAG, msg)
        throw IllegalStateException(msg)
    }

    private suspend fun webView(): WebView {
        if (web != null) return web!!
        return suspendCancellableCoroutine { cont ->
            main.post {
                try {
                    val w = WebView(ctx)
                    w.settings.javaScriptEnabled = true
                    w.settings.domStorageEnabled = true
                    w.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // CORS-bypassing native fetch. Without it every cross-origin
                    // provider request is blocked by the WebView before it is sent.
                    val bridge = ProviderHttp()
                    bridge.onTrace = { line -> trace(line) }
                    http = bridge
                    activeBridge = bridge
                    w.addJavascriptInterface(bridge, "VegaHttp")
                    // No loadData here on purpose: a blank-page load queued ahead of
                    // the runtime load could fire onPageFinished against the *runtime*
                    // WebViewClient, resuming ensureRuntime before the shims exist.
                    // The first ensureRuntime owns the initial load.
                    web = w
                    cont.resume(w)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        }
    }

    /**
     * Load the shim runtime; reloads when switching providers (fresh realm).
     *
     * Each load carries a generation token and only its own onPageFinished may resume,
     * so a late callback from a superseded load can't release the continuation early.
     */
    private suspend fun ensureRuntime(providerKey: String) {
        if (web != null && runtimeReadyFor == providerKey) return
        val w = webView()
        val html = ctx.assets.open("provider_runtime.html").bufferedReader().use { it.readText() }
        val gen = ++loadGeneration
        suspendCancellableCoroutine<Unit> { cont ->
            main.post {
                w.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (gen != loadGeneration) return // stale load, ignore
                        if (!cont.isCompleted) cont.resume(Unit)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        if (gen != loadGeneration) return
                        // Subresource failures are the provider's problem, not the
                        // runtime's. Only a main-frame error means the page itself
                        // failed to load; treating a blocked XHR as "Runtime page
                        // failed" reported a failure that never happened.
                        if (request?.isForMainFrame != true) return
                        if (!cont.isCompleted) {
                            cont.resumeWithException(
                                IllegalStateException("Runtime page failed: ${error?.description}"),
                            )
                        }
                    }
                }
                w.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null)
            }
        }
        // Poll until the shims are live: onPageFinished can fire before the inline
        // <script> has executed on slow WebViews, and one probe is not enough.
        val deadline = System.currentTimeMillis() + 15_000
        var probe = ""
        while (System.currentTimeMillis() < deadline) {
            probe = evalString("String(typeof window.__vegaInvoke)", 10_000)
            if (probe == "function") break
            delay(150)
        }
        if (probe != "function") {
            runtimeReadyFor = null
            fail("Provider runtime failed to load (typeof __vegaInvoke = $probe)")
        }
        lastError = null
        runtimeReadyFor = providerKey
        Log.i(TAG, "Runtime ready for $providerKey")
    }

    private suspend fun evalRaw(script: String, timeoutMs: Long): String? =
        withTimeout(timeoutMs) {
            val w = webView()
            suspendCancellableCoroutine { cont ->
                main.post {
                    try {
                        w.evaluateJavascript(script) { result ->
                            if (cont.isCompleted) return@evaluateJavascript
                            try {
                                if (result == null || result == "null") cont.resume(null)
                                else cont.resume(result)
                            } catch (e: Exception) {
                                cont.resumeWithException(e)
                            }
                        }
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }
            }
        }

    /** Decode an evaluateJavascript string result (JSON-quoted). */
    private fun unwrap(result: String?): String {
        if (result == null || result == "null") return ""
        return runCatching { json.decodeFromString<String>(result) }.getOrElse { result }
    }

    /** Evaluate [script] (an expression) and return its decoded string value. */
    suspend fun evalString(script: String, timeoutMs: Long = 10_000): String =
        unwrap(evalRaw(script, timeoutMs))

    @Serializable
    private data class CatalogBundle(
        val catalog: List<CatalogEntry> = emptyList(),
        val genres: List<CatalogEntry> = emptyList(),
        val catalogType: String = "",
        val genresType: String = "",
    )

    /**
     * Static catalog exports are evaluated directly; providers such as the
     * current Kartoons bundle export `catalog` as an async function because it
     * has to establish a device session before it can list categories. The
     * type markers let [ProviderManager] choose the invoke path for those.
     */
    data class CatalogEval(
        val catalog: List<CatalogEntry>,
        val genres: List<CatalogEntry>,
        val catalogIsFunction: Boolean,
        val genresIsFunction: Boolean,
    )

    /**
     * Run a catalog.js bundle and return (catalog, genres). The bundle is
     * untrusted but static: wrap in a function scope with fresh
     * exports/module so it cannot touch the page.
     *
     * Takes the same lock as invoke(): the catalog path is a plain evaluateJavascript
     * and must not interleave with a loadDataWithBaseURL realm swap.
     */
    suspend fun evalCatalog(moduleCode: String): CatalogEval = lock.withLock {
        require(moduleCode.length < 2 * 1024 * 1024) { "Module exceeds 2MB" }
        val w = webView()
        // The catalog is static, but it still needs a realm with a JS context.
        if (w.url.isNullOrEmpty() || runtimeReadyFor == null) ensureRuntime("catalog")
        // Escape for embedding: backslashes, backticks, ${, </script.
        val safe = moduleCode
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\$", "\\\$")
            .replace("</script", "<\\/script")
        val script = """(function(){
var exports={};var module={exports:exports};
// Direct eval (not (0,eval)): the bundle runs in this scope, so its bare
// `exports`/`module` resolve to the locals above. Indirect eval would run
// global-scope where "use strict" bundles throw ReferenceError.
try{ eval(`$safe`); }catch(e){ return JSON.stringify({error:String(e&&e.message||e)}); }
var m=(module&&module.exports)||{};var out={};
try{
  var c=(m.catalog!==undefined?m.catalog:exports.catalog);
  var g=(m.genres!==undefined?m.genres:exports.genres);
  out.catalogType=typeof c;
  out.genresType=typeof g;
  out.catalog=Array.isArray(c)?c:[];
  out.genres=Array.isArray(g)?g:[];
}catch(e){ out.catalog=[];out.genres=[]; }
return JSON.stringify({catalog:out.catalog,genres:out.genres,catalogType:out.catalogType,genresType:out.genresType});
})()"""
        val raw = evalString(script)
        // Error path: {"error":"..."} without catalog keys.
        val bundle = runCatching { json.decodeFromString<CatalogBundle>(raw) }.getOrNull()
            ?: throw IllegalStateException("Catalog eval failed: ${raw.take(200)}")
        if (bundle.catalog.isEmpty() && bundle.genres.isEmpty() &&
            !bundle.catalogType.equals("function", true) &&
            !bundle.genresType.equals("function", true)
        ) {
            // The bundle threw; evalCatalog's own catch swallowed it into empty lists.
            Log.w(TAG, "Catalog bundle produced no entries: ${raw.take(200)}")
        }
        CatalogEval(
            catalog = bundle.catalog,
            genres = bundle.genres,
            catalogIsFunction = bundle.catalogType.equals("function", true),
            genresIsFunction = bundle.genresType.equals("function", true),
        )
    }

    @Serializable
    private data class InvokeEnvelope(
        val __ok: kotlinx.serialization.json.JsonElement? = null,
        val __error: String? = null,
    )

    /**
     * Invoke a networked export (getPosts/getSearchPosts/getMeta/getStream/
     * getEpisodes) inside the shim runtime. [providerKey] scopes the realm
     * (reloaded on switch, like a fresh worker). [paramsJson] is a JSON
     * object of provider args; commonHeaders/timeoutMs are added by the
     * runtime. Returns the raw result JSON.
     *
     * Binder-safe: module code uploads as base64 chunks (evaluateJavascript
     * has a ~1MB transaction limit); only small strings cross per call.
     */
    suspend fun invoke(
        providerKey: String,
        moduleCode: String,
        exportName: String,
        paramsJson: String,
        commonHeadersJson: String = "{}",
        timeoutMs: Long = SANDBOX_INVOKE_TIMEOUT_MS,
    ): String {
        require(moduleCode.length < 2 * 1024 * 1024) { "Module exceeds 2MB" }
        lock.withLock {
            synchronized(invokeState) {
                invokeInFlight = true
                activeInvokeExport = exportName
                destroyAfterInvoke = false
            }
            try {
                // The JS budget pauses while a WAF dialog is open, so the native
                // backstop has to leave room for the human as well.
                return kotlinx.coroutines.withTimeout(timeoutMs + 210_000) {
                try {
                    ensureRuntime(providerKey)
                    val bridge = http ?: throw IllegalStateException("Native bridge not attached")
                    // Correlates this invoke's result/state with the JS promise.
                    // Cancellation cannot stop JS already running, so an orphan
                    // result must be dropped rather than completing this invoke.
                    val invokeId = java.util.UUID.randomUUID().toString()
                    primeKv(bridge, providerKey)
                    refreshBaseUrls(bridge)
                    bridge.stateCapture = null
                    bridge.stateCaptureId = null
                    // Fresh module scope per invoke (no cross-provider leakage).
                    evalString("window.__vegaReset()", 10_000)
                    // providerGlobal: whatever the previous invoke saved, so a
                    // provider's cache survives between calls. The stored text
                    // is JSON.stringify output; native-side JSON validation at
                    // capture time plus the object-shape guard keeps this a
                    // safe literal. U+2028/29 are escaped by hand because they
                    // are line terminators to older JS engines.
                    val stateJson = providerState[providerKey]
                        ?.takeIf { it.startsWith("{") && it.endsWith("}") }
                        ?.replace("\u2028", "\\u2028")?.replace("\u2029", "\\u2029")
                        ?: "{}"
                    evalString("window.providerGlobal=$stateJson", 10_000)
                    // Upload code as base64 chunks, then eval once in-page.
                    evalString("window.__code=''", 10_000)
                    val bytes = moduleCode.toByteArray(Charsets.UTF_8)
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    var i = 0
                    val chunk = 150_000
                    while (i < b64.length) {
                        val part = b64.substring(i, minOf(i + chunk, b64.length))
                        evalString("window.__code+=atob(\"$part\")", 15_000)
                        i += chunk
                    }
                    val loadResult = evalString(
                        "(function(){try{(function(exports,module,require,__awaiter,providerGlobal){eval(window.__code);})(window.__vegaExports,window.__vegaModule,function(){return {};},window.__awaiter,window.providerGlobal);window.__code='';return 'ok';}" +
                            "catch(e){window.__code='';" +
                            // Message alone is not enough for a ReferenceError: report
                            // the first few stack frames so we learn *where* it broke.
                            "return 'ERR:'+String((e&&e.message)||e)+' @ '+String((e&&e.stack||'').split('\\n').slice(0,3).join(' | '));}})()",
                        15_000,
                    )
                    require(!loadResult.startsWith("ERR:")) { "Module load failed: ${loadResult.take(300)}" }
                    trace("module loaded: $providerKey/$exportName (${moduleCode.length}b)")
                    val invokeScript =
                        "window.__vegaInvoke(${jsString(exportName)},{\"params\":$paramsJson,\"commonHeaders\":$commonHeadersJson,\"timeoutMs\":$timeoutMs,\"providerValue\":${jsString(providerKey)},\"invokeId\":${jsString(invokeId)}})"
                    // evaluateJavascript cannot return a promise, so the runtime pushes
                    // the result through VegaHttp.result(). Register the listener before
                    // starting the invoke, so a fast result cannot arrive unobserved.
                    val deferred = kotlinx.coroutines.CompletableDeferred<String>()
                    bridge.onResult = { id, payload ->
                        if (id == invokeId) deferred.complete(payload)
                    }
                    val raw = try {
                        val started = evalRaw(invokeScript, 15_000)
                        if (started == null) {
                            throw IllegalStateException("Invoke never started for $exportName")
                        }
                        // A provider blocked on openWebView is waiting on a human, so
                        // the challenge pump runs alongside the result wait. It ends
                        // on its own as soon as no request is outstanding.
                        val pump = launch { pumpOpenWebView(bridge) }
                        try {
                            withTimeout(timeoutMs + 200_000) { deferred.await() }
                        } finally {
                            pump.cancel()
                        }
                    } finally {
                        bridge.onResult = null
                        flushKv(bridge, providerKey)
                        // Only the invoke that produced this state may commit it:
                        // an orphaned JS promise can land during the next invoke.
                        if (bridge.stateCaptureId == invokeId) {
                            bridge.stateCapture?.let { saved ->
                                if (saved.length <= MAX_PROVIDER_STATE_CHARS) {
                                    providerState[providerKey] = saved
                                } else {
                                    trace("providerGlobal dropped (${saved.length}b)")
                                }
                            }
                        }
                        bridge.stateCapture = null
                        bridge.stateCaptureId = null
                    }
                    val env = runCatching { json.decodeFromString<InvokeEnvelope>(raw) }.getOrNull()
                        ?: throw IllegalStateException("Bad invoke envelope: ${raw.take(300)}")
                    env.__error?.let { throw IllegalStateException(it.take(500)) }
                    (env.__ok?.toString() ?: "null")
                } catch (e: Exception) {
                    // Force a fresh realm next time so a stuck provider can't wedge us.
                    runtimeReadyFor = null
                    lastError = "$exportName: ${e.message ?: e.javaClass.simpleName}"
                    Log.e(TAG, "invoke $exportName failed for $providerKey: ${e.message}", e)
                    throw e
                }
            }
            } finally {
                val teardown = synchronized(invokeState) {
                    invokeInFlight = false
                    activeInvokeExport = null
                    if (destroyAfterInvoke) {
                        destroyAfterInvoke = false
                        true
                    } else {
                        false
                    }
                }
                // Still holding the invoke mutex, so a new provider call cannot
                // observe the old page between these two statements.
                if (teardown) destroy()
            }
        }
    }

    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Prime the bridge's in-memory kv mirror and drop any writes left over from a
     * previous invoke. Without this a provider starts each call blind, which is
     * exactly the bug the persistent store exists to fix.
     */
    private suspend fun primeKv(bridge: ProviderHttp, providerKey: String) {
        val author = providerKey.substringBefore(':', providerKey)
        val snap = mutableMapOf<String, String>()
        runCatching { kv.snapshotFor(author) }.getOrNull()?.forEach { (k, v) ->
            snap["$author:$k"] = v
        }
        bridge.kvSnapshot = snap
        bridge.kvWrites.clear()
        bridge.kvDeletes.clear()
    }

    /** Write back whatever the provider changed during this invoke. */
    private suspend fun flushKv(bridge: ProviderHttp, providerKey: String) {
        val author = providerKey.substringBefore(':', providerKey)
        bridge.kvWrites.forEach { (scoped, value) ->
            runCatching { kv.set(author, scoped.substringAfter(':'), value) }
        }
        bridge.kvDeletes.forEach { scoped ->
            runCatching { kv.delete(author, scoped.substringAfter(':')) }
        }
        bridge.kvWrites.clear()
        bridge.kvDeletes.clear()
    }

    /**
     * Serve the native `urls.json` map that backs `getBaseUrl`. The JS shim used
     * to fetch this itself, which cost an extra bridge round trip on every call
     * and meant the 1h cache died with the page; upstream caches it natively too.
     */
    private suspend fun refreshBaseUrls(bridge: ProviderHttp) {
        if (bridge.baseUrlCache.expiresAt > System.currentTimeMillis()) return
        val body = runCatching {
            val req = okhttp3.Request.Builder()
                .url("$URLS_JSON_URL?t=${System.currentTimeMillis()}")
                .header("Cache-Control", "max-age=3600")
                .build()
            NetClients.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("urls.json HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        }.getOrNull() ?: return
        val obj = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = runCatching { obj.getJSONObject(k).optString("url") }.getOrDefault("")
        }
        bridge.baseUrlCache = ProviderHttp.BaseUrls(map, System.currentTimeMillis() + 3_600_000)
    }

    /**
     * While a provider is blocked on `openWebView`, keep the coordinator's state
     * moving: JS is polling `VegaHttp.rpcPending(requestId)`, and the answer only
     * appears once the WAF route calls back. This is the pump that connects them.
     */
    private suspend fun pumpOpenWebView(bridge: ProviderHttp) {
        while (true) {
            val entry = bridge.openRequests.entries.firstOrNull()
            if (entry == null) {
                // The provider may still be fetching the page that will ask
                // for a challenge. This pump used to exit on the first empty
                // poll, so a request arriving a moment later was never shown.
                delay(100)
                continue
            }
            val id = entry.key
            val req = entry.value
            trace("openWebView challenge: ${req.url}")
            // A provider waiting on a human must not take the invoke mutex with
            // it, or no other provider call could start while the challenge is
            // on screen. Only the RPC layer is touched, so it is safe.
            try {
                val res = WafCoordinator.open(
                    req.url,
                    req.waitForCookie,
                    req.waitForLoad,
                    req.headers,
                    req.injectedJavaScript,
                    req.timeoutMs,
                    req.title,
                    req.description,
                )
                bridge.openResults[id] = resultJson(res)
            } catch (e: Exception) {
                bridge.openResults[id] = errorJson(e.message ?: "dismissed")
            }
            bridge.openRequests.remove(id)
        }
    }

    private fun resultJson(res: WafResult) = org.json.JSONObject()
        .put("ok", true)
        .put(
            "value",
            org.json.JSONObject()
                .put("data", res.data)
                .put("cookies", res.cookies)
                .put("cookieMap", org.json.JSONObject(res.cookieMap as Map<*, *>))
                .put("userAgent", res.userAgent)
                .put("url", res.url),
        )
        .toString()

    private fun errorJson(msg: String) =
        org.json.JSONObject().put("ok", false).put("error", msg).toString()

    /**
     * Tear the hidden renderer down once a video is actually playing: its
     * process and any in-page provider timers must not compete with the
     * decoder. Rebuilt lazily by the next [invoke].
     *
     * If an invoke is in flight, the teardown is deferred to the instant it
     * unwinds. The old implementation silently skipped the teardown in that
     * case, leaving the provider page (and its timers) alive for the whole
     * film. The state lock is what makes the deferred decision race-free: a
     * player resolve that starts while the flag is set is never torn down.
     */
    fun suspendForPlayback() {
        val (destroyNow, abortHttp) = synchronized(invokeState) {
            if (invokeInFlight) {
                destroyAfterInvoke = true
                // A feed/meta/episode call can be aborted so its network work
                // stops now instead of at the provider's 45–120s deadline.
                // Never abort getStream: that is the call the player is
                // waiting for; it is allowed to finish naturally.
                false to (activeInvokeExport != "getStream")
            } else {
                true to false
            }
        }
        if (abortHttp) http?.cancelActiveCalls()
        if (destroyNow) destroy()
    }

    fun destroy() {
        WafCoordinator.cancelAll()
        http?.cancelActiveCalls()
        http = null
        activeBridge = null
        loadGeneration++
        // Capture this page before posting. Destroying `web` inside the post
        // could otherwise kill a replacement page created in the meantime.
        val doomed = web
        web = null
        if (doomed != null) {
            main.post { runCatching { doomed.destroy() } }
        }
        runtimeReadyFor = null
    }
}
