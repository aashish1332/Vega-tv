package tv.vega.tv.data.providers

import android.webkit.CookieManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Result of one `providerContext.openWebView(url, options)` call, matching the
 * shape upstream's worker returns (see `sandboxWorker.ts` — the resolved value
 * is `{data, cookies, cookieMap, userAgent, url}`).
 */
data class WafResult(
    val data: String,
    val cookies: String,
    val cookieMap: Map<String, String>,
    val userAgent: String,
    val url: String,
)

/** A challenge the user is being asked to solve, published for the WAF route. */
data class WafChallenge(
    val id: Long,
    val url: String,
    /** Upstream `waitForCookie`: the cookie whose arrival means "solved". */
    val waitForCookie: String?,
    /** When false the user may submit the page themselves without any cookie. */
    val waitForLoad: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val injectedJavaScript: String? = null,
    val timeoutMs: Long = 0L,
    val title: String = "",
    val description: String = "",
)

/**
 * Bridges a provider's `openWebView` request to the visible challenge WebView.
 *
 * `openWebView` is used **51 times** across the real `vega-providers` bundles —
 * it is how a provider asks the *user* to clear a Cloudflare interstitial, log
 * in, or click through a "press play" gate, and it expects a result back. The
 * previous runtime returned `Promise.reject(new Error('openWebView unavailable
 * on TV'))`, so every one of those 51 call sites failed outright and roughly
 * half the published providers could never return a stream.
 *
 * The navigation half already existed (`WafScreen` is routed from Detail and
 * Player), but it only knew how to *display* a challenge: it polled for
 * `cf_clearance` and then popped the back stack, throwing away the page HTML the
 * provider had asked for. This class is the missing return path — the provider
 * call suspends on a [CompletableDeferred] until the screen publishes an
 * answer, so a solved challenge resumes the exact provider call that wanted it
 * rather than making the user retry the whole flow.
 *
 * Held as a process singleton because both the RPC bridge (a
 * `@JavascriptInterface` method, not a composable) and the WAF route need it,
 * and there is no meaningful component scope to thread it through.
 */
object WafCoordinator {

    private val _pending = MutableStateFlow<WafChallenge?>(null)
    val pending: kotlinx.coroutines.flow.StateFlow<WafChallenge?> = _pending.asStateFlow()

    /**
     * Scope for reading the solved WebView. Deliberately not the caller's: the
     * UI must be free to recompose while a challenge is being handed back.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var nextId = 1L
    private val waiters = mutableMapOf<Long, CompletableDeferred<WafResult>>()
    private val webViews = mutableMapOf<Long, android.webkit.WebView>()

    /**
     * Navigate the user to [url] and suspend until they solve it or give up.
     * The UI layer observes [pending] and pushes the WAF route; [deliver] and
     * [dismiss] are what that route calls back with.
     */
    suspend fun open(
        url: String,
        waitForCookie: String? = null,
        waitForLoad: Boolean = false,
        headers: Map<String, String> = emptyMap(),
        injectedJavaScript: String? = null,
        timeoutMs: Long = 0L,
        title: String = "",
        description: String = "",
    ): WafResult {
        val id = nextId++
        val deferred = CompletableDeferred<WafResult>()
        synchronized(waiters) { waiters[id] = deferred }
        _pending.value = WafChallenge(
            id = id,
            url = url,
            waitForCookie = waitForCookie,
            waitForLoad = waitForLoad,
            headers = headers,
            injectedJavaScript = injectedJavaScript,
            timeoutMs = timeoutMs,
            title = title,
            description = description,
        )
        return try {
            deferred.await()
        } finally {
            synchronized(waiters) { waiters.remove(id); webViews.remove(id) }
            if (_pending.value?.id == id) _pending.value = null
        }
    }

    /**
     * The WebView the WAF screen should drive. Registering it here means
     * [deliver] can read the page the user actually solved, rather than having
     * the screen hand a string back through a composable callback and risk
     * serialising a multi-megabyte page through a nav argument.
     */
    fun attach(id: Long, webView: android.webkit.WebView) {
        synchronized(waiters) { webViews[id] = webView }
    }

    /**
     * Read the solved page and complete the waiting provider call, then run
     * [onDelivered] (the UI's "go back to whatever was blocked" callback).
     *
     * Called from the WAF screen, which cannot itself suspend while staying
     * responsive to the rest of the composition, so the coroutine is launched
     * on the coordinator's own scope. [onDelivered] is deliberately not run
     * until the read has completed: popping the screen earlier would destroy
     * the WebView this is about to read.
     */
    fun deliverAndThen(id: Long, dataOverride: String? = null, onDelivered: () -> Unit) {
        scope.launch {
            if (deliver(id, dataOverride)) onDelivered()
        }
    }

    /**
     * Challenge solved: return the page to the waiting provider call.
     *
     * Suspends briefly because the page HTML can only be read through
     * `evaluateJavascript`, whose callback is asynchronous — there is no
     * synchronous way to ask a WebView for its DOM.
     */
    suspend fun deliver(id: Long, dataOverride: String? = null): Boolean {
        val webView = synchronized(waiters) { webViews[id] }
        val deferred = synchronized(waiters) { waiters[id] } ?: return false
        val result = runCatching { snapshot(webView, dataOverride) }.getOrNull()
        if (result != null) return deferred.complete(result)
        deferred.completeExceptionally(IllegalStateException("Challenge page could not be read"))
        return false
    }

    /** User closed the challenge without solving it. */
    fun dismiss(id: Long) {
        val deferred = synchronized(waiters) { waiters.remove(id) }
        webViews.remove(id)
        deferred?.completeExceptionally(IllegalStateException("Challenge dismissed"))
    }

    /** Cancel everything, e.g. when the app tears the WebView down. */
    fun cancelAll() {
        val all = synchronized(waiters) {
            val copy = waiters.values.toList()
            waiters.clear()
            webViews.clear()
            copy
        }
        all.forEach { it.completeExceptionally(IllegalStateException("Provider runtime torn down")) }
        _pending.value = null
    }

    private suspend fun snapshot(
        webView: android.webkit.WebView?,
        dataOverride: String? = null,
    ): WafResult? {
        if (webView == null) return null
        val url = webView.url.orEmpty()
        val cookieHeader = runCatching { CookieManager.getInstance().getCookie(url).orEmpty() }.getOrDefault("")
        val map = cookieHeader.split(';')
            .mapNotNull { part ->
                val t = part.trim()
                val eq = t.indexOf('=')
                if (eq <= 0) null else t.substring(0, eq).trim() to t.substring(eq + 1).trim()
            }
            .toMap()
        val ua = runCatching { webView.settings.userAgentString }.getOrDefault("")
        // The provider asked for the page, not just proof that it loaded, so the
        // solved HTML is part of the answer — many providers re-parse it to pull
        // a fresh token straight out of the document. A provider that posts a
        // token through the ReactNativeWebView bridge gets that payload instead:
        // some gates keep the token only in localStorage, never in the DOM.
        // The view can be released while a read is in flight (BACK, challenge
        // replacement). Bound the wait so the provider call still answers.
        val data = dataOverride ?: withTimeoutOrNull(2_000) { readHtml(webView) }.orEmpty()
        return WafResult(data, cookieHeader, map, ua, url)
    }

    /**
     * Read a WebView's serialised DOM. Must run on the main thread; the callback
     * is the only completion signal `evaluateJavascript` offers.
     */
    private suspend fun readHtml(webView: android.webkit.WebView): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    webView.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                        if (cont.isCompleted) return@evaluateJavascript
                        if (raw == null || raw == "null") {
                            cont.resume(null)
                        } else {
                            // The result arrives as a JSON string literal.
                            val decoded = runCatching { JSONObject(raw).getString("") }.getOrNull()
                            cont.resume(decoded ?: raw)
                        }
                    }
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }
        }
}
