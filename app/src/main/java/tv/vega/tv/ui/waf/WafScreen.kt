package tv.vega.tv.ui.waf

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.vega.tv.data.providers.WafCoordinator

/**
 * The visible half of `providerContext.openWebView` — the point where a provider
 * asks a *human* to clear a Cloudflare interstitial, log in, or click through a
 * gate, and gets the solved page back.
 *
 * Two callers reach this screen, and they need different endings:
 *
 *  - A provider blocked mid-call ([WafCoordinator] has a pending challenge).
 *    "Solve & Continue" must hand the page back to that suspended provider call
 *    so *it* resumes, rather than making the user abandon and retry the whole
 *    detail-to-stream flow. The coordinator is what carries that answer.
 *
 *  - The manual "a challenge appeared, solve it and I'll retry" path from the
 *    detail and player screens. There is no waiting call, so solving simply pops
 *    back and the user's own retry picks up the new cookies.
 *
 * Previously this screen only polled for `cf_clearance` and popped the back
 * stack, which meant the first case could not exist at all: even once a
 * challenge was solved, the provider call that asked for help stayed suspended
 * and its result was thrown away.
 */
@Composable
fun WafScreen(url: String, onSolved: () -> Unit, onClose: () -> Unit) {
    var status by remember { mutableStateOf("Loading challenge…") }
    var done by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Which provider call, if any, is blocked waiting for this challenge.
    val challenge by WafCoordinator.pending.collectAsStateWithLifecycle()
    val pendingId = challenge?.id
    val waitForCookie = challenge?.waitForCookie

    // Hand the coordinator the live WebView so it can read the solved DOM when
    // the user confirms, and register that this challenge is now on screen.
    LaunchedEffect(pendingId, webView) {
        val id = pendingId
        val wv = webView
        if (id != null && wv != null) WafCoordinator.attach(id, wv)
    }

    DisposableEffectBackHandler {
        if (pendingId != null) WafCoordinator.dismiss(pendingId)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Solve the check, then playback resumes",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            // A provider waiting on us is a real handshake, so offer the
            // explicit continue. Without a waiting provider (manual path) the
            // button would do nothing meaningful, so it is not shown.
            if (pendingId != null) {
                Button(
                    onClick = {
                        done = true
                        status = "Solved ✓"
                        // Resume the provider call; it already has the cookies
                        // in the shared jar, so no reload is needed here.
                        WafCoordinator.deliverAndThen(pendingId) { onSolved() }
                    },
                ) { Text("Solve & Continue") }
            }
            Button(onClick = onClose) { Text("Close") }
        }
        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = WebViewClient()
                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize().weight(1f),
        )
    }

    LaunchedEffect(url) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline && !done) {
            delay(1000)
            val cookies = runCatching { cm.getCookie(url).orEmpty() }.getOrDefault("")
            val satisfied = when {
                waitForCookie != null -> cookies.contains(waitForCookie)
                // A provider may hand the challenge a page that sets no cookie
                // at all (a "press to continue" interstitial). Upstream's
                // waitForLoad covers that, so don't demand a clearance that will
                // never appear.
                challenge?.waitForLoad == true -> (webView?.progress ?: 0) >= 100
                pendingId != null -> false
                else -> cookies.contains("cf_clearance")
            }
            if (satisfied) {
                if (pendingId != null) {
                    // Signalled by cookie/load alone: don't wait for the button.
                    done = true
                    status = "Solved ✓"
                    WafCoordinator.deliverAndThen(pendingId) { onSolved() }
                } else {
                    done = true
                    status = "Solved ✓"
                    delay(600)
                    onSolved()
                }
                return@LaunchedEffect
            } else if (cookies.isNotBlank()) {
                status = "Cookies seen, waiting for clearance…"
            }
        }
        if (!done) status = "Timed out — press Close and retry"
    }
}

/** BACK must dismiss a pending provider challenge, not orphan it. */
@Composable
private fun DisposableEffectBackHandler(onBack: () -> Unit) {
    val view = LocalView.current
    androidx.compose.runtime.DisposableEffect(view) {
        // Key events, not the activity back dispatcher: a TV remote's BACK is a
        // real key event, and a provider-suspended challenge must be answered
        // on both paths. `focused` is checked because an unhandled BACK on a
        // TV box is delivered to the launcher otherwise.
        val listener = view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP
            ) {
                onBack()
                true
            } else {
                false
            }
        }
        onDispose { view.setOnKeyListener(null) }
    }
}
