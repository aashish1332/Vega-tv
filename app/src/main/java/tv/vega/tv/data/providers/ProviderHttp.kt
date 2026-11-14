package tv.vega.tv.data.providers

import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tv.vega.tv.data.net.NetClients
import java.util.concurrent.TimeUnit

private const val TAG = "VegaProviderHttp"

/** 32 MiB, matching upstream ProviderHttpModule's ceiling. */
private const val MAX_BODY = 32L * 1024 * 1024

/**
 * Response headers that describe the wire form, not the body OkHttp handed us.
 * Forwarding them to a JS `Response` lets the platform re-inflate bytes that
 * are already decoded, and makes `.text()` throw.
 */
private val HOP_BY_HOP = setOf(
    "content-encoding",
    "content-length",
    "transfer-encoding",
    "connection",
)

/**
 * Native fetch for the provider runtime.
 *
 * The runtime page is served from a `https://localhost/` base URL, so the WebView's
 * own `fetch` is CORS-bound: any cross-origin request to a provider site that sends
 * no `Access-Control-Allow-Origin` fails before it reaches the network. Upstream
 * hits exactly this and sidesteps it — `vega-app`'s `providerFetch.ts` calls
 * `NativeModules.ProviderHttpModule.fetch` on Android, which runs OkHttp natively
 * and returns the body to JS. This is the TV equivalent of that module.
 *
 * OkHttp here is [NetClients.http], so provider traffic honours the same
 * DoH setting as the rest of the app, and cookies are shared with the visible
 * WebView used for Cloudflare challenges (CookieManager is process-global).
 *
 * Contract (all JSON, since @JavascriptInterface cannot hand JS a JS object):
 *   in  {url, method?, headers?, bodyBase64?}
 *   out {ok:true, status, statusText, url, headers, bodyBase64}
 *       | {ok:false, error}
 */
class ProviderHttp {

    /**
     * Calls currently blocked in [fetch]. A teardown can cancel these instead
     * of waiting for a provider's own 45–120s request budget to expire.
     */
    private val activeCalls = java.util.concurrent.ConcurrentHashMap<okhttp3.Call, Boolean>()

    @Volatile
    private var cancelled = false

    fun cancelActiveCalls() {
        cancelled = true
        activeCalls.keys.forEach { runCatching { it.cancel() } }
        activeCalls.clear()
    }

    @JavascriptInterface
    fun fetch(reqJson: String): String {
        var req: JSONObject? = null
        return try {
            // A provider that retries after teardown must not resurrect a
            // network stream the player is about to compete with.
            if (cancelled) error("request cancelled for playback")
            req = JSONObject(reqJson)
            val url = req.optString("url").ifBlank { error("missing url") }
            val method = (req.optString("method").ifBlank { "GET" }).uppercase()

            val headers = JSONObject(req.optString("headers") ?: "{}")
            val cookies = runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }
                .getOrDefault("")
            val hasCookie = headers.keys().asSequence().any { it.equals("cookie", true) }
            if (!hasCookie && cookies.isNotBlank()) headers.put("Cookie", cookies)
            // Mirror upstream: a manual Cookie header is pushed into CookieManager so
            // the challenge WebView and later native requests send it too.
            if (hasCookie) {
                runCatching {
                    CookieManager.getInstance().setCookie(url, headers.optString("Cookie"))
                }
            }

            val bodyB64 = req.optString("bodyBase64")
            val body = if (bodyB64.isBlank()) null else {
                Base64.decode(bodyB64, Base64.DEFAULT)
                    .toRequestBody(null)
            }

            val builder = Request.Builder().url(url)
            headers.keys().asSequence().forEach { k ->
                val v = headers.optString(k)
                if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v)
            }
            val request = builder
                .method(method, if (method in setOf("GET", "HEAD")) null else body ?: ByteArray(0).toRequestBody(null))
                .build()

            val call = NetClients.http.newCall(request)
            activeCalls[call] = true
            try {
                val timeoutMs = req.optLong("timeoutMs", 0L)
                if (timeoutMs > 0) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
                val response = call.execute()
                response.use { r ->
                    val bytes = r.body?.bytes() ?: ByteArray(0)
                    if (bytes.size > MAX_BODY) error("Response too large: ${bytes.size} bytes")
                    Log.i(TAG, "$method $url -> ${r.code} (${bytes.size}b)")
                    // Set-Cookie must reach CookieManager, or a Cloudflare clearance
                    // earned here is invisible to the next request and to the challenge
                    // WebView — which is how a solved challenge keeps re-appearing.
                    r.headers.values("set-cookie").forEach { c ->
                        runCatching { CookieManager.getInstance().setCookie(url, c) }
                    }
                    JSONObject().apply {
                        put("ok", true)
                        put("status", r.code)
                        put("statusText", r.message)
                        put("url", r.request.url.toString())
                        put("headers", JSONObject().apply {
                            r.headers.forEach { h ->
                                // OkHttp already decoded the body; forwarding these would
                                // tell the JS Response the bytes are still compressed, and a
                                // stale Content-Length contradicts what it actually got.
                                if (!HOP_BY_HOP.contains(h.first.lowercase())) put(h.first, h.second)
                            }
                        })
                        put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    }.toString()
                }
            } finally {
                activeCalls.remove(call)
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "fetch failed for ${req?.optString("url")}: $msg")
            JSONObject().put("ok", false).put("error", msg).toString()
        }
    }

    @JavascriptInterface
    fun trace(line: String) {
        onTrace?.invoke(line.take(500))
    }

    /**
     * Provider invoke completion. evaluateJavascript cannot return a promise, so
     * JS pushes the result here instead; [ProviderJs] awaits it. Delivered on a
     * background thread, hence the volatile hook rather than direct state.
     *
     * [id] correlates the payload with the invoke that produced it. A cancelled
     * Kotlin invoke cannot stop the JS promise it already started (the fetch may
     * be in a synchronous native call), so that orphan can still push a result
     * after the next invoke is waiting. Without the id it completed the *next*
     * invoke's deferred and every result lagged one query behind its search.
     */
    @JavascriptInterface
    fun result(id: String, payload: String) {
        Log.i(TAG, "invoke result $id: ${payload.length} chars")
        onResult?.invoke(id, payload)
    }

    /**
     * `providerGlobal` round-trip. The runtime calls this right before [result]
     * on a successful invoke (the bridge calls are ordered, so the payload is
     * always in place by the time [ProviderJs.invoke] resumes); [ProviderJs]
     * stores it per provider and injects it into the next invoke, which is how
     * mobile's fresh-worker-per-call model keeps provider state alive.
     */
    @Volatile
    var stateCapture: String? = null

    /** Id of the invoke that produced [stateCapture]; orphans are ignored. */
    @Volatile
    var stateCaptureId: String? = null

    @JavascriptInterface
    fun state(id: String, payload: String) {
        stateCaptureId = id
        stateCapture = payload
    }

    // ---------------------------------------------------------------------
    // providerRpc: the nine operations upstream's worker may call natively.
    // ---------------------------------------------------------------------
    //
    // A @JavascriptInterface method is synchronous and cannot await a coroutine,
    // so every op is answered with a synchronously-available result and the two
    // genuinely asynchronous ops (openWebView, and the kv DataStore round trip)
    // are bridged by handing the JS side a handle: we stash the request, JS
    // polls/awaits `rpcPending`, and the answer arrives as a second `rpc` call.
    // That keeps the JS side's interface a normal promise without needing an
    // async bridge that a WebView on old TV firmware may not expose.

    /**
     * In-memory mirror of the persistent store, so the synchronous bridge can
     * answer kv reads. [ProviderJs] primes it at the start of every invoke and
     * flushes the write-behind buffers back to DataStore when the invoke ends.
     * Reading DataStore per call would mean a suspend from a non-suspend bridge
     * method; this keeps the bridge synchronous without making kv volatile.
     */
    @Volatile
    var kvSnapshot: Map<String, String> = emptyMap()

    internal val kvWrites = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
    internal val kvDeletes = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** The JS `fetch` op, with upstream's URL guard and rate limit applied. */
    private fun rpcFetch(args: JSONObject): String {
        val url = args.optString("url")
        val guard = guardProviderUrl(url)
        if (guard != null) return JSONObject().put("ok", false).put("error", guard).toString()
        val req = JSONObject()
            .put("url", url)
            .put("method", args.optString("method", "GET"))
            .put("headers", args.optJSONObject("headers") ?: JSONObject())
        args.optString("bodyBase64").takeIf { it.isNotBlank() }?.let { req.put("bodyBase64", it) }

        // Upstream rate-limits before issuing. A @JavascriptInterface method is
        // synchronous, so the wait is taken here on the WebView's JS thread; that
        // is safe because ProviderJs already serialises invokes with a mutex, so
        // no second call can be queued behind this one and no RPC can be starved.
        val release = try {
            kotlinx.coroutines.runBlocking { rateLimiter.acquire(hostOf(url)) }
        } catch (e: Exception) {
            return JSONObject()
                .put("ok", false)
                .put("error", e.message ?: "Rate limited")
                .toString()
        }
        val raw = try {
            fetch(req.toString())
        } finally {
            // A leaked slot is invisible until the app stops making requests
            // entirely, so release on every path.
            release()
        }
        // fetch() already reports {ok,status,bodyBase64}. Fold the RPC envelope
        // around it so the JS side has one shape for every op.
        val inner = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        return JSONObject()
            .put("ok", inner.optBoolean("ok", false))
            .put("value", inner)
            .put("error", inner.optString("error"))
            .toString()
    }

    private fun hostOf(url: String): String =
        runCatching { url.toHttpUrlOrNull()?.host.orEmpty() }.getOrDefault(url)

    /** Upstream's process-wide `providerRateLimiter`. */
    private val rateLimiter = ProviderRateLimiter.default

    private fun rpcCrypto(args: JSONObject): String {
        val algo = args.optString("algorithm").uppercase()
        val input = args.optString("data")
        val bytes = runCatching { Base64.decode(input, Base64.DEFAULT) }.getOrElse { ByteArray(0) }
        val digestAlgo = when (algo.replace("-", "")) {
            "MD5" -> "MD5"
            "SHA1" -> "SHA-1"
            "SHA256" -> "SHA-256"
            "SHA384" -> "SHA-384"
            "SHA512" -> "SHA-512"
            else -> return JSONObject().put("ok", false).put("error", "Unsupported digest: $algo").toString()
        }
        val out = runCatching {
            java.security.MessageDigest.getInstance(digestAlgo).digest(bytes)
        }.getOrElse {
            return JSONObject().put("ok", false).put("error", it.message ?: "digest failed").toString()
        }
        return JSONObject()
            .put("ok", true)
            .put("value", Base64.encodeToString(out, Base64.NO_WRAP))
            .toString()
    }

    private fun rpcGetBaseUrl(args: JSONObject): String {
        val providerValue = args.optString("providerValue")
        val url = baseUrlCache.map[providerValue]
        // Upstream returns '' for a provider that is not in urls.json
        // (`d?.[pv]?.url || ''`), so an unresolvable base URL is an empty
        // string, not a hang. The map is loaded once per invoke by
        // ProviderJs.refreshBaseUrls before the provider ever asks.
        if (url != null) return JSONObject().put("ok", true).put("value", url).toString()
        onTrace?.invoke("getBaseUrl: no entry for $providerValue")
        return JSONObject().put("ok", true).put("value", "").toString()
    }

    /**
     * Upstream `getCookies`: name -> value map of the cookies the site has set
     * for the URL. Mobile keeps one jar per source author; the TV app has a
     * single process-wide CookieManager shared by native fetches and the WAF
     * WebView, so this reads that jar — providers see their own site's cookies,
     * including a clearance earned by a solved challenge.
     */
    private fun rpcGetCookies(args: JSONObject): String {
        val url = args.optString("url")
        guardProviderUrl(url)?.let { return JSONObject().put("ok", false).put("error", it).toString() }
        val header = runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }
            .getOrDefault("")
        val map = JSONObject()
        header.split(';').forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) map.put(part.substring(0, i).trim(), part.substring(i + 1).trim())
        }
        return JSONObject().put("ok", true).put("value", map).toString()
    }

    data class BaseUrls(val map: Map<String, String>, val expiresAt: Long)

    @Volatile
    var baseUrlCache: BaseUrls = BaseUrls(emptyMap(), 0L)

    /**
     * Upstream `urlGuard.ts`. Provider-supplied URLs are untrusted, so anything
     * the host fetches on a provider's behalf is checked before it leaves the
     * app: http(s) only, no embedded credentials, and nothing pointing at the
     * local network. Returns null when the URL is acceptable, or the reason it
     * is not.
     */
    private fun guardProviderUrl(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return "Provider URL is required"
        val url = runCatching { value.toHttpUrlOrNull() }.getOrNull()
            ?: return "Provider URL is not valid"
        if (url.scheme != "http" && url.scheme != "https") return "Provider URL is not allowed"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "Provider URL is not allowed"
        if (isPrivateHost(url.host)) return "Provider URL is not allowed"
        return null
    }

    private fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim('[', ']').lowercase()
        if (host.isEmpty() || host == "localhost" || host == "::1" || host == "::" ||
            host == "0.0.0.0" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal")
        ) return true
        val v4 = host.split(".")
        if (v4.size == 4) {
            val p = v4.map { it.toIntOrNull() }
            if (p.size == 4 && p.all { it != null && it in 0..255 }) {
                val a = p[0]!!; val b = p[1]!!
                if (a == 10 || a == 127 || a == 0 || a >= 224) return true
                if (a == 172 && b in 16..31) return true
                if (a == 192 && b == 168) return true
                if (a == 169 && b == 254) return true
                if (a == 100 && b in 64..127) return true
            }
        }
        if (host.contains(":")) {
            return host.startsWith("fc") || host.startsWith("fd") ||
                host.startsWith("fe8") || host.startsWith("fe9") ||
                host.startsWith("fea") || host.startsWith("feb")
        }
        return false
    }

    @JavascriptInterface
    fun rpc(op: String, argsJson: String): String {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return JSONObject().put("ok", false).put("error", "bad rpc args").toString()
        }
        val providerValue = args.optString("providerValue")
        return try {
            when (op) {
                "fetch" -> rpcFetch(args)
                "crypto" -> rpcCrypto(args)
                "getBaseUrl" -> rpcGetBaseUrl(args)
                "getCookies" -> rpcGetCookies(args)
                "kvGet" -> rpcKvGet(providerValue, args)
                "kvSet" -> rpcKvSet(providerValue, args)
                "kvDelete" -> rpcKvDelete(providerValue, args)
                "kvKeys" -> rpcKvKeys(providerValue)
                "kvClear" -> rpcKvClear(providerValue)
                "openWebView" -> rpcOpenWebView(args)
                else -> JSONObject().put("ok", false).put("error", "unknown rpc op: $op").toString()
            }
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "rpc $op failed: $msg")
            JSONObject().put("ok", false).put("error", msg.take(300)).toString()
        }
    }

    /** Synchronously satisfiable part of the kv ops, backed by a loaded snapshot. */
    private fun rpcKvGet(providerValue: String, args: JSONObject): String {
        val key = args.optString("key")
        val stored = kvSnapshot[scopedKvKey(providerValue, key)]
        val out = JSONObject().put("ok", true)
        // `put(String, Object)` drops the key when handed null, so an absent
        // entry has to go in as NULL to still read as `undefined` in JS.
        out.put("value", stored?.let { decodeKvValue(it) } ?: JSONObject.NULL)
        return out.toString()
    }

    /**
     * Undo the encoding `kvStore.set` applies, so a value comes back as the
     * type it went in as.
     *
     * `set` stores a string verbatim and everything else as `JSON.stringify(v)`
     * — the runtime shim does exactly this — so the store holds `true` for a
     * boolean but `auto` for a string. Returning that raw made every read a
     * string, and `"false"` is truthy in JS, so a provider branching on a
     * settings toggle (`4khdhub/meta.ts` does exactly this:
     * `kvStore.get<boolean>("4khdhub_quickDownload")`) could never see it as
     * off.
     *
     * Only the forms `JSON.stringify` produces for a non-string are decoded.
     * Text and select values — `auto`, `cf worker`, a scraped token — are
     * returned untouched, so they still round-trip byte for byte. The one
     * ambiguity this leaves is a string field whose value is literally
     * `"true"`, which would come back as a boolean; that is inherent to
     * encoding type information in the stored text, and misreading a select
     * label is far less damaging than misreading every toggle.
     */
    private fun decodeKvValue(raw: String): Any {
        val t = raw.trim()
        return when {
            t == "true" -> true
            t == "false" -> false
            t == "null" -> JSONObject.NULL
            // A number is only re-typed when the whole string is one, so
            // "1080p" and "1e5-2" stay text.
            t.isNotEmpty() && t.toDoubleOrNull() != null -> t.toDouble()
            t.startsWith("{") || t.startsWith("[") ->
                runCatching { JSONObject(t) }.getOrNull() ?: JSONArray(t)
            else -> raw
        }
    }

    private fun scopedKvKey(providerValue: String, key: String) = "$providerValue:$key"

    private fun rpcKvSet(providerValue: String, args: JSONObject): String {
        val key = args.optString("key")
        val value = args.optString("value")
        if (key.length > ProviderKv.MAX_KEY_LENGTH) {
            return JSONObject().put("ok", false).put("error", "Key too long").toString()
        }
        if (value.toByteArray(Charsets.UTF_8).size > ProviderKv.MAX_VALUE_BYTES) {
            return JSONObject().put("ok", false).put("error", "Value too large").toString()
        }
        kvWrites.add(scopedKvKey(providerValue, key) to value)
        kvSnapshot = kvSnapshot + (scopedKvKey(providerValue, key) to value)
        return JSONObject().put("ok", true).put("value", JSONObject.NULL).toString()
    }

    private fun rpcKvDelete(providerValue: String, args: JSONObject): String {
        val key = args.optString("key")
        val sk = scopedKvKey(providerValue, key)
        val had = kvSnapshot.containsKey(sk)
        kvDeletes.add(sk)
        kvSnapshot = kvSnapshot - sk
        return JSONObject().put("ok", true).put("value", had).toString()
    }

    /**
     * Update the live snapshot for a write made by the host, not the sandbox.
     *
     * Deliberately does *not* enqueue into [kvWrites]: the settings screen has
     * already persisted through `ProviderKv`, so queueing here would write the
     * same value twice. This only makes the value answerable to the running
     * page, so a `kvStore.get` issued later in the same session sees the edit.
     */
    fun applyKvSet(providerValue: String, key: String, value: String) {
        kvSnapshot = kvSnapshot + (scopedKvKey(providerValue, key) to value)
    }

    fun applyKvDelete(providerValue: String, key: String) {
        kvSnapshot = kvSnapshot - scopedKvKey(providerValue, key)
    }

    private fun rpcKvKeys(providerValue: String): String {
        val prefix = "$providerValue:"
        val out = JSONArray()
        kvSnapshot.keys.filter { it.startsWith(prefix) }
            .map { it.substring(prefix.length) }.sorted().forEach { out.put(it) }
        return JSONObject().put("ok", true).put("value", out).toString()
    }

    private fun rpcKvClear(providerValue: String): String {
        val prefix = "$providerValue:"
        val doomed = kvSnapshot.keys.filter { it.startsWith(prefix) }
        doomed.forEach { kvDeletes.add(it) }
        kvSnapshot = kvSnapshot.filterKeys { !it.startsWith(prefix) }
        return JSONObject().put("ok", true).put("value", JSONObject.NULL).toString()
    }

    /**
     * `openWebView` needs a UI round trip, which a synchronous bridge cannot
     * express. Publish the challenge and let the JS side await `rpcPending`;
     * [ProviderJs] completes it when the WAF route delivers or dismisses.
     */
    private fun rpcOpenWebView(args: JSONObject): String {
        val url = args.optString("url")
        if (url.isBlank()) {
            return JSONObject().put("ok", false).put("error", "openWebView url is required").toString()
        }
        val requestId = nextRequestId()
        openRequests[requestId] = OpenRequest(
            url = url,
            waitForCookie = args.optString("waitForCookie").takeIf { it.isNotBlank() },
            waitForLoad = args.optBoolean("waitForLoad", false),
            headers = args.optJSONObject("headers")?.let { h ->
                buildMap {
                    h.keys().forEach { k -> put(k, h.optString(k)) }
                }
            }.orEmpty(),
            injectedJavaScript = args.optString("injectedJavaScript").takeIf { it.isNotBlank() },
            timeoutMs = args.optLong("timeoutMs", 0L).coerceAtLeast(0L),
            title = args.optString("title"),
            description = args.optString("description"),
        )
        return JSONObject().put("ok", true).put("pending", true).put("requestId", requestId).toString()
    }

    private fun nextRequestId(): Long {
        synchronized(requestCounter) { return ++requestCounter.id }
    }

    /**
     * Called by JS to block on an async op started earlier. Returns the stored
     * answer, or a `pending` marker while the challenge is still on screen.
     */
    @JavascriptInterface
    fun rpcPending(requestId: Long): String {
        val done = openResults[requestId]
        if (done != null) {
            openResults.remove(requestId)
            return done
        }
        return JSONObject().put("ok", true).put("pending", true).toString()
    }

    private class RequestCounter { var id = 0L }
    private val requestCounter = RequestCounter()

    internal data class OpenRequest(
        val url: String,
        val waitForCookie: String?,
        val waitForLoad: Boolean,
        val headers: Map<String, String> = emptyMap(),
        val injectedJavaScript: String? = null,
        val timeoutMs: Long = 0L,
        val title: String = "",
        val description: String = "",
    )

    internal val openRequests = java.util.concurrent.ConcurrentHashMap<Long, OpenRequest>()
    internal val openResults = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** Set by [ProviderJs] so provider console output lands in its on-screen ring buffer. */
    @Volatile
    var onTrace: ((String) -> Unit)? = null

    @Volatile
    var onResult: ((String, String) -> Unit)? = null
}
