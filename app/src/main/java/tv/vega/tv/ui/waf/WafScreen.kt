package tv.vega.tv.ui.waf

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import tv.vega.tv.data.providers.COMMON_USER_AGENT
import tv.vega.tv.data.providers.WafCoordinator
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private enum class NavMode { Page, Pointer }

/**
 * A DOM-drawn pointer.
 *
 * Compose content and a native WebView cannot be layered reliably: the
 * AndroidView is a real child View and is painted after Compose's own draw
 * pass, so a Compose cursor box behind it is invisible. The cursor therefore
 * lives in the page itself, in a fixed, pointer-events:none element above
 * every site layer (including cross-origin challenge iframes).
 */
private val PAGE_POINTER_JS = """
    (function () {
      if (window.__vegaPointerReady) return;
      window.__vegaPointerReady = true;
      var el = null;
      function ensure() {
        if (!el) {
          el = document.createElement('div');
          el.id = '__vega_pointer';
          el.setAttribute('aria-hidden', 'true');
          var s = el.style;
          s.position = 'fixed';
          s.left = '50%';
          s.top = '50%';
          s.width = '20px';
          s.height = '20px';
          s.marginLeft = '-10px';
          s.marginTop = '-10px';
          s.borderRadius = '50%';
          s.border = '2px solid #ffffff';
          s.background = 'rgba(8, 14, 24, 0.55)';
          s.boxShadow = '0 0 0 2px #4da3ff, 0 2px 10px rgba(0,0,0,0.7)';
          s.zIndex = '2147483647';
          s.pointerEvents = 'none';
          s.boxSizing = 'border-box';
          s.display = 'none';
        }
        var root = document.documentElement || document.body;
        if (root && el.parentNode !== root) root.appendChild(el);
        return el;
      }
      window.__vegaPointerSet = function (x, y, w, h) {
        var e = ensure();
        if (!w || !h) return;
        var cw = window.innerWidth || w;
        var ch = window.innerHeight || h;
        e.style.left = (x * cw / w) + 'px';
        e.style.top = (y * ch / h) + 'px';
        e.style.display = 'block';
      };
      window.__vegaPointerShow = function () { ensure().style.display = 'block'; };
      window.__vegaPointerHide = function () { if (el) el.style.display = 'none'; };
    })()
""".trimIndent()

/** Position the in-page cursor using WebView device pixels. */
private fun WebView.setPageCursor(x: Float, y: Float) {
    if (width <= 0 || height <= 0) return
    val px = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
    val py = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
    runCatching {
        evaluateJavascript(
            "window.__vegaPointerSet&&window.__vegaPointerSet($px,$py,$width,$height)",
            null,
        )
    }
}

private fun WebView.setPageCursorVisible(visible: Boolean) {
    runCatching {
        evaluateJavascript(
            if (visible) "window.__vegaPointerShow&&window.__vegaPointerShow()"
            else "window.__vegaPointerHide&&window.__vegaPointerHide()",
            null,
        )
    }
}

private val ACTIVE_TEXT_FIELD_JS = """
    (function () {
      function resolve(el) {
        var guard = 0;
        while (el && el.shadowRoot && el.shadowRoot.activeElement && guard++ < 8) {
          el = el.shadowRoot.activeElement;
        }
        return el;
      }
      function editable(el) {
        if (!el) return false;
        var tag = (el.tagName || '').toLowerCase();
        if (tag === 'textarea') return true;
        if (tag === 'input') {
          var type = (el.getAttribute('type') || 'text').toLowerCase();
          return ['button','submit','reset','checkbox','radio','file','image','hidden','range','color']
            .indexOf(type) < 0;
        }
        return !!(el.isContentEditable || el.getAttribute('contenteditable') === 'true');
      }
      var el = resolve(document.activeElement);
      if (!el) return 'none';
      if ((el.tagName || '').toLowerCase() === 'iframe') {
        var mark = ((el.src || '') + ' ' + (el.name || '') + ' ' + (el.id || '')).toLowerCase();
        return /captcha|challenge|turnstile|cloudflare|recaptcha|hcaptcha/.test(mark)
          ? 'none' : 'editable';
      }
      return editable(el) ? 'editable' : 'none';
    })()
""".trimIndent()

private val FOCUS_TEXT_FIELD_JS = """
    (function () {
      var nodes = document.querySelectorAll("input, textarea, [contenteditable='true']");
      for (var i = 0; i < nodes.length; i++) {
        var el = nodes[i];
        if (el.disabled || el.readOnly) continue;
        if ((el.tagName || '').toLowerCase() === 'input') {
          var type = (el.getAttribute('type') || 'text').toLowerCase();
          if (['button','submit','reset','checkbox','radio','file','image','hidden','range','color']
            .indexOf(type) >= 0) continue;
        }
        var r = el.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) continue;
        el.focus();
        try { el.scrollIntoView({block: 'center', inline: 'nearest'}); }
        catch (e) { el.scrollIntoView(); }
        return 'editable';
      }
      return 'none';
    })()
""".trimIndent()

/**
 * D-pad navigation inside the page.
 *
 * Android WebView has no spatial navigation on Android (arrow keys scroll), so
 * without this the remote can only move a synthetic pointer around. That is
 * slow, and it cannot type into a login form. This listener moves real DOM
 * focus between visible controls, scrolls the target into view and lets ENTER
 * activate it. It is installed into every document the WebView loads.
 *
 * It is deliberately small and self-contained: providers' pages are arbitrary
 * third-party sites, and anything that rewrites their DOM can break a login.
 * The only mutation is an outline style on the focused element, restored on
 * blur.
 */
private val SPATIAL_NAV_JS = """
    (function () {
      if (window.__vegaSpatialNav) return;
      window.__vegaSpatialNav = true;

      var SEL = 'a[href],button,input:not([type="hidden"]),select,textarea,[tabindex]:not([tabindex="-1"]),[role="button"],[contenteditable="true"]';
      var marked = null;

      function tag(el) { return (el && el.tagName || '').toLowerCase(); }

      function isEditable(el) {
        if (!el) return false;
        var t = tag(el);
        if (t === 'textarea') return true;
        if (t === 'input') {
          var type = (el.getAttribute('type') || 'text').toLowerCase();
          return ['button','submit','reset','checkbox','radio','file','image','hidden','range','color']
            .indexOf(type) < 0;
        }
        return !!(el.isContentEditable || el.getAttribute('contenteditable') === 'true');
      }

      function visible(el) {
        if (!el || el.disabled) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) return false;
        var s = window.getComputedStyle(el);
        if (!s) return false;
        if (s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity) === 0) return false;
        return true;
      }

      function nodes() {
        var all = document.querySelectorAll(SEL);
        var out = [];
        for (var i = 0; i < all.length; i++) {
          var el = all[i];
          if (!visible(el)) continue;
          var t = tag(el);
          if (t !== 'a' && t !== 'button' && t !== 'input' && t !== 'select' &&
              t !== 'textarea' && !el.hasAttribute('tabindex')) {
            el.setAttribute('tabindex', '0');
          }
          out.push(el);
        }
        return out;
      }

      function mark(el) {
        if (marked === el) return;
        clearMark();
        if (!el || !el.style) return;
        el.__vegaOutline = el.style.outline;
        el.__vegaOutlineOffset = el.style.outlineOffset;
        el.style.outline = '3px solid #4da3ff';
        el.style.outlineOffset = '2px';
        marked = el;
      }

      function clearMark() {
        if (!marked) return;
        try {
          marked.style.outline = marked.__vegaOutline || '';
          marked.style.outlineOffset = marked.__vegaOutlineOffset || '';
        } catch (e) {}
        marked = null;
      }

      document.addEventListener('focusin', function (e) { mark(e.target); }, true);
      document.addEventListener('focusout', function (e) {
        if (marked === e.target) clearMark();
      }, true);

      function box(el) {
        var r = el.getBoundingClientRect();
        return {r: r, x: r.left + r.width / 2, y: r.top + r.height / 2};
      }

      function nearest() {
        var list = nodes();
        if (!list.length) return null;
        var cx = window.innerWidth / 2;
        var cy = window.innerHeight / 2;
        var best = null;
        var bestD = 1e18;
        for (var i = 0; i < list.length; i++) {
          var c = box(list[i]);
          var d = (c.x - cx) * (c.x - cx) + (c.y - cy) * (c.y - cy);
          if (d < bestD) { bestD = d; best = list[i]; }
        }
        return best;
      }

      function pick(dir) {
        var cur = document.activeElement;
        if (!cur || cur === document.body || cur === document.documentElement) return nearest();
        var a = box(cur);
        var list = nodes();
        var best = null;
        var bestScore = 1e18;
        for (var i = 0; i < list.length; i++) {
          var el = list[i];
          if (el === cur || cur.contains(el)) continue;
          var c = box(el);
          var primary;
          var perp;
          if (dir === 'left') { primary = a.r.left - c.r.right; perp = Math.abs(a.y - c.y); }
          else if (dir === 'right') { primary = c.r.left - a.r.right; perp = Math.abs(a.y - c.y); }
          else if (dir === 'up') { primary = a.r.top - c.r.bottom; perp = Math.abs(a.x - c.x); }
          else { primary = c.r.top - a.r.bottom; perp = Math.abs(a.x - c.x); }
          if (primary < -12) continue;
          var score = Math.max(0, primary) + perp * 2.2;
          if (score < bestScore) { bestScore = score; best = el; }
        }
        return best;
      }

      function scrollTo(el) {
        try { el.scrollIntoView({block: 'nearest', inline: 'nearest'}); }
        catch (e) { try { el.scrollIntoView(); } catch (e2) {} }
      }

      function askKeyboard() {
        try {
          if (window.ReactNativeWebView && window.ReactNativeWebView.postMessage) {
            window.ReactNativeWebView.postMessage(JSON.stringify({__wafKeyboard: true}));
          }
        } catch (e) {}
      }

      function nav(dir) {
        var target = pick(dir);
        if (!target) return false;
        try { target.focus({preventScroll: true}); }
        catch (e) { try { target.focus(); } catch (e2) { return false; } }
        scrollTo(target);
        if (isEditable(target)) askKeyboard();
        return true;
      }

      document.addEventListener('keydown', function (e) {
        if (e.defaultPrevented) return;
        var k = e.key || '';
        var dir = k === 'ArrowLeft' ? 'left' : k === 'ArrowRight' ? 'right' :
          k === 'ArrowUp' ? 'up' : k === 'ArrowDown' ? 'down' : '';
        var active = document.activeElement;
        if (dir) {
          // Left/right inside a text field move the caret; up/down leave it.
          if (isEditable(active) && (dir === 'left' || dir === 'right')) return;
          if (nav(dir)) {
            e.preventDefault();
            e.stopPropagation();
            return;
          }
          if (dir === 'up' &&
              (window.scrollY || document.documentElement.scrollTop || 0) <= 0) {
            try {
              if (window.ReactNativeWebView && window.ReactNativeWebView.postMessage) {
                window.ReactNativeWebView.postMessage(JSON.stringify({__wafNav: 'up'}));
              }
            } catch (e2) {}
            e.preventDefault();
          }
          return;
        }
        if (k === 'Enter') {
          if (isEditable(active)) { askKeyboard(); return; }
          if (active && active !== document.body && active !== document.documentElement &&
              typeof active.click === 'function') {
            e.preventDefault();
            active.click();
          } else {
            var list = nodes();
            if (list.length) {
              e.preventDefault();
              nav('down');
            }
          }
        }
      }, true);
    })()
""".trimIndent()

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun WebView.showIme() {
    isFocusable = true
    isFocusableInTouchMode = true
    if (!requestFocus()) requestFocusFromTouch()
    postDelayed({
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        ViewCompat.getWindowInsetsController(this)?.show(WindowInsetsCompat.Type.ime())
    }, 120)
}

private fun WebView.imeVisible(): Boolean =
    runCatching {
        ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }.getOrDefault(false)

private fun parseCookieHeader(header: String): Map<String, String> =
    header.split(';')
        .mapNotNull { part ->
            val pair = part.trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) null else pair.substring(0, eq).trim() to pair.substring(eq + 1).trim()
        }
        .toMap()

/**
 * The visible half of `providerContext.openWebView` — the point where a provider
 * asks a *human* to clear a Cloudflare interstitial, log in, or click through a
 * gate, and gets the solved page back.
 *
 * The mobile app hands this page to a normal WebView and lets the user touch it.
 * On a TV there is no touchscreen, so the screen has to provide an input model.
 * The previous version made the WebView unfocusable and routed every D-pad key
 * into a virtual pointer, which meant no native form focus, no IME hand-off at
 * the right field, and a constantly composited overlay above the WebView — the
 * stutter. The page is now a real, focused WebView: a small injected script
 * moves DOM focus with the D-pad, and the pointer is an opt-in fallback for
 * cross-origin widgets (Cloudflare Turnstile) that no page script can press.
 *
 * Two callers reach this screen, and they need different endings:
 *
 *  - A provider blocked mid-call ([WafCoordinator] has a pending challenge).
 *    "Continue" must hand the page back to that suspended provider call so *it*
 *    resumes, rather than making the user abandon and retry the whole
 *    detail-to-stream flow. The coordinator is what carries that answer.
 *
 *  - The manual "a challenge appeared, solve it and I'll retry" path from the
 *    detail and player screens. There is no waiting call, so solving simply pops
 *    back and the user's own retry picks up the new cookies.
 */
@Composable
fun WafScreen(url: String, onSolved: () -> Unit, onClose: () -> Unit) {
    var status by remember { mutableStateOf("Loading page…") }
    var done by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var mode by remember { mutableStateOf(NavMode.Page) }
    val toolbarFocus = remember { FocusRequester() }
    val webViewFocus = remember { FocusRequester() }
    // Pointer keys arrive on the WebView's key listener (the page always keeps
    // real focus); this stable hook reaches the composable's cursor state.
    val pointerKeyAction =
        remember { AtomicReference<(Int, Int) -> Boolean>({ _, _ -> false }) }
    // The JS bridge runs on a binder thread; it can only hand work back to the
    // main thread through these stable hooks.
    val focusToolbarAction = remember { AtomicReference<() -> Unit>({}) }
    focusToolbarAction.set { runCatching { toolbarFocus.requestFocus() } }

    val density = LocalDensity.current
    val pointerStep = with(density) { 18.dp.toPx() }
    val pointerRepeatStep = with(density) { 9.dp.toPx() }
    val edgeScrollStep = with(density) { 28.dp.toPx() }
    var cursorX by remember { mutableFloatStateOf(-1f) }
    var cursorY by remember { mutableFloatStateOf(-1f) }
    var areaWidth by remember { mutableStateOf(0) }
    var areaHeight by remember { mutableStateOf(0) }

    val challenge by WafCoordinator.pending.collectAsStateWithLifecycle()
    val pendingId = challenge?.id
    val bridgeSettled = remember(pendingId) {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }
    val waitForCookie = challenge?.waitForCookie
    val targetUrl = challenge?.url?.takeIf { it.isNotBlank() } ?: url
    val headers = challenge?.headers.orEmpty()
    val userAgent = headers.entries
        .firstOrNull { it.key.equals("User-Agent", true) }
        ?.value
        ?.takeIf { it.isNotBlank() }
        ?: COMMON_USER_AGENT
    val injectedJavaScript = challenge?.injectedJavaScript
    val timeoutMs = challenge?.timeoutMs ?: 0L
    val watchedCookieName = waitForCookie ?: "cf_clearance"
    val waitForLoad = challenge?.waitForLoad == true
    val initialWatchedCookie = remember(pendingId, targetUrl, watchedCookieName) {
        runCatching {
            parseCookieHeader(CookieManager.getInstance().getCookie(targetUrl).orEmpty())[watchedCookieName]
        }.getOrNull()
    }

    fun close() {
        runCatching { CookieManager.getInstance().flush() }
        pendingId?.let { WafCoordinator.dismiss(it) }
        onClose()
    }

    fun hideImeIfVisible(): Boolean {
        val wv = webView ?: return false
        if (!wv.imeVisible()) return false
        val imm = wv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(wv.windowToken, 0)
        ViewCompat.getWindowInsetsController(wv)?.hide(WindowInsetsCompat.Type.ime())
        wv.requestFocus()
        return true
    }

    fun requestKeyboardIfEditable(wv: WebView) {
        runCatching {
            wv.evaluateJavascript(ACTIVE_TEXT_FIELD_JS) { result ->
                if (result?.contains("editable") == true) {
                    wv.showIme()
                    status = "Keyboard open — type, then press BACK to close it"
                }
            }
        }
    }

    fun focusFirstTextField() {
        val wv = webView ?: return
        status = "Looking for a text field…"
        runCatching {
            wv.evaluateJavascript(FOCUS_TEXT_FIELD_JS) { result ->
                if (result?.contains("editable") == true) {
                    wv.showIme()
                    status = "Keyboard open — type, then press BACK to close it"
                } else {
                    status = "No text field found — use Pointer mode"
                }
            }
        }
    }

    fun tapWebView(x: Float, y: Float) {
        val wv = webView ?: return
        if (wv.width <= 0 || wv.height <= 0) return
        val px = x.coerceIn(0f, (wv.width - 1).toFloat())
        val py = y.coerceIn(0f, (wv.height - 1).toFloat())
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, px, py, 0)
        wv.dispatchTouchEvent(down)
        down.recycle()
        wv.postDelayed({
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px, py, 0)
            wv.dispatchTouchEvent(up)
            up.recycle()
            wv.postDelayed({ requestKeyboardIfEditable(wv) }, 140)
        }, 90)
        status = "Tap sent"
    }

    fun movePointer(dx: Float, dy: Float, step: Float) {
        val maxX = (areaWidth - 1).coerceAtLeast(0).toFloat()
        val maxY = (areaHeight - 1).coerceAtLeast(0).toFloat()
        val wv = webView
        when {
            dx < 0f -> {
                if (cursorX <= 0f) wv?.scrollBy(-edgeScrollStep.roundToInt(), 0)
                else cursorX = (cursorX - step).coerceAtLeast(0f)
            }
            dx > 0f -> {
                if (cursorX >= maxX) wv?.scrollBy(edgeScrollStep.roundToInt(), 0)
                else cursorX = (cursorX + step).coerceAtMost(maxX)
            }
            dy < 0f -> {
                if (cursorY <= 0f) wv?.scrollBy(0, -edgeScrollStep.roundToInt())
                else cursorY = (cursorY - step).coerceAtLeast(0f)
            }
            dy > 0f -> {
                if (cursorY >= maxY) wv?.scrollBy(0, edgeScrollStep.roundToInt())
                else cursorY = (cursorY + step).coerceAtMost(maxY)
            }
        }
        // The cursor is painted by the page, not Compose, so it stays above
        // the native WebView surface and over cross-origin challenge iframes.
        if (wv != null && cursorX >= 0f && cursorY >= 0f) {
            wv.setPageCursor(cursorX, cursorY)
        }
    }

    fun tapAtCursor() {
        if (cursorX >= 0f && cursorY >= 0f) tapWebView(cursorX, cursorY)
    }

    fun handlePointerKeyCode(keyCode: Int, repeatCount: Int): Boolean {
        val step = if (repeatCount > 0) pointerRepeatStep else pointerStep
        return when (keyCode) {
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { movePointer(-1f, 0f, step); true }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { movePointer(1f, 0f, step); true }
            AndroidKeyEvent.KEYCODE_DPAD_UP -> { movePointer(0f, -1f, step); true }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { movePointer(0f, 1f, step); true }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
            AndroidKeyEvent.KEYCODE_ENTER,
            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
            AndroidKeyEvent.KEYCODE_BUTTON_A,
            -> { tapAtCursor(); true }
            else -> false
        }
    }

    pointerKeyAction.set { keyCode, repeatCount ->
        mode == NavMode.Pointer && handlePointerKeyCode(keyCode, repeatCount)
    }

    /** Force the page cursor to the centre after a toolbar round trip. */
    fun recenterPointer() {
        val wv = webView ?: return
        if (wv.width <= 0 || wv.height <= 0) return
        cursorX = wv.width / 2f
        cursorY = wv.height / 2f
        if (mode == NavMode.Pointer) {
            wv.setPageCursor(cursorX, cursorY)
            wv.setPageCursorVisible(true)
        }
        wv.requestFocus()
    }

    /**
     * The Cloudflare checkbox lives in a cross-origin frame, so no page script
     * can click it. The frame element's rectangle is still visible, so tapping
     * its usual spot with a native touch works from page mode without entering
     * pointer mode.
     */
    fun tapVerificationBox() {
        val wv = webView ?: return
        if (wv.width <= 0 || wv.height <= 0) return
        status = "Looking for the verification box…"
        wv.evaluateJavascript(
            """
            (function () {
              function visible(r) { return r && r.width >= 20 && r.height >= 20; }
              var chains = Array.prototype.slice.call(document.querySelectorAll('iframe'));
              var best = null, bestArea = 0;
              for (var i = 0; i < chains.length; i++) {
                var f = chains[i];
                var r = f.getBoundingClientRect();
                if (!visible(r)) continue;
                var meta = (f.src || '') + ' ' + (f.title || '') + ' ' + (f.getAttribute('aria-label') || '');
                var preferred = /turnstile|cloudflare|challenge|verify/i.test(meta);
                var area = r.width * r.height;
                if (!best || (preferred && !best.preferred) || (preferred === best.preferred && area > bestArea)) {
                  best = { r: r, preferred: preferred };
                  bestArea = area;
                }
              }
              if (!best) {
                var boxes = Array.prototype.slice.call(document.querySelectorAll("input[type='checkbox']"));
                for (var j = 0; j < boxes.length; j++) {
                  var br = boxes[j].getBoundingClientRect();
                  if (visible(br)) { best = { r: br, preferred: true }; break; }
                }
              }
              if (!best) return null;
              return JSON.stringify({
                x: best.r.left + Math.min(30, best.r.width * 0.22),
                y: best.r.top + Math.min(32, best.r.height * 0.5),
                vw: window.innerWidth || 1
              });
            })()
            """.trimIndent(),
        ) { result ->
            val raw = runCatching {
                JSONTokener(result ?: "").nextValue() as? String
            }.getOrNull()
            val obj = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val cssX = obj?.optDouble("x", Double.NaN) ?: Double.NaN
            val cssY = obj?.optDouble("y", Double.NaN) ?: Double.NaN
            val cssWidth = obj?.optDouble("vw", Double.NaN) ?: Double.NaN
            if (cssX.isNaN() || cssY.isNaN() || cssWidth.isNaN() || cssWidth <= 1.0) {
                status = "No verification box found — use Pointer mode"
                return@evaluateJavascript
            }
            val scale = wv.width / cssWidth
            tapWebView((cssX * scale).toFloat(), (cssY * scale).toFloat())
        }
    }

    // Keep the window its own size while the IME is up. Resizing it on every
    // keyboard toggle reflows the whole page and is a visible stutter on TV.
    val localView = LocalView.current
    DisposableEffect(localView) {
        val window = localView.context.findActivity()?.window
        val previous = window?.attributes?.softInputMode
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            previous?.let { window?.setSoftInputMode(it) }
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    LaunchedEffect(pendingId, webView) {
        val id = pendingId
        val wv = webView
        if (id != null && wv != null) WafCoordinator.attach(id, wv)
    }

    // Page mode and pointer mode both keep real focus on the WebView: pointer
    // keys are intercepted by its key listener, and a Compose focus target
    // cannot be trusted to stay above a native WebView.
    LaunchedEffect(pendingId, webView, mode) {
        val wv = webView ?: return@LaunchedEffect
        if (mode == NavMode.Pointer) {
            if (cursorX < 0f || cursorY < 0f) {
                cursorX = wv.width / 2f
                cursorY = wv.height / 2f
            }
            wv.setPageCursor(cursorX, cursorY)
            wv.setPageCursorVisible(true)
        } else {
            wv.setPageCursorVisible(false)
        }
        repeat(3) {
            withFrameNanos { }
            if (wv.requestFocus()) return@LaunchedEffect
        }
    }

    DisposableEffectBackHandler {
        when {
            mode == NavMode.Pointer -> mode = NavMode.Page
            hideImeIfVisible() -> Unit
            // A login flow is a browser: BACK follows its redirects first.
            webView?.canGoBack() == true -> webView?.goBack()
            else -> close()
        }
    }

    LaunchedEffect(pendingId) {
        done = false
        bridgeSettled.set(false)
        status = "Loading page…"
        mode = NavMode.Page
    }

    DisposableEffect(pendingId) {
        onDispose {
            if (pendingId != null && WafCoordinator.pending.value?.id == pendingId) {
                WafCoordinator.dismiss(pendingId)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            // Moving down from the toolbar enters the page. Keys inside the
            // WebView never reach Compose, which is exactly what we want.
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown &&
                mode == NavMode.Page
            ) {
                val wv = webView
                if (wv != null && !wv.hasFocus()) {
                    runCatching { wv.requestFocus() }
                    true
                } else {
                    false
                }
            } else {
                false
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    challenge?.title?.takeIf { it.isNotBlank() } ?: "Provider sign-in",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Button(onClick = { focusFirstTextField() }) { Text("Keyboard", maxLines = 1) }
            Button(onClick = {
                if (mode == NavMode.Page) {
                    mode = NavMode.Pointer
                    // The page just had focus (or the toolbar did): put the
                    // pointer mid-page and hand focus back immediately instead
                    // of waiting for the next recomposition pass.
                    recenterPointer()
                } else {
                    mode = NavMode.Page
                }
            }) {
                Text(if (mode == NavMode.Page) "Pointer" else "Page", maxLines = 1)
            }
            Button(onClick = { tapVerificationBox() }) { Text("Tap check", maxLines = 1) }
            Button(
                onClick = {
                    if (bridgeSettled.compareAndSet(false, true)) {
                        done = true
                        status = "Solved ✓"
                        runCatching { CookieManager.getInstance().flush() }
                        if (pendingId != null) {
                            WafCoordinator.deliverAndThen(pendingId) { onSolved() }
                        } else {
                            onSolved()
                        }
                    }
                },
                modifier = Modifier.focusRequester(toolbarFocus),
            ) { Text("Continue", maxLines = 1) }
            Button(onClick = { close() }) { Text("Close", maxLines = 1) }
        }
        Text(
            if (mode == NavMode.Page) {
                "D-pad navigates · OK selects · UP at the top opens the toolbar · BACK goes back"
            } else {
                "Pointer: D-pad moves · OK taps · hold at an edge to scroll · BACK returns to page"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        )
        Box(
            Modifier.fillMaxSize().weight(1f).onSizeChanged { size ->
                areaWidth = size.width
                areaHeight = size.height
                if (cursorX < 0f || cursorY < 0f) {
                    cursorX = size.width / 2f
                    cursorY = size.height / 2f
                } else {
                    cursorX = cursorX.coerceIn(0f, (size.width - 1).coerceAtLeast(0).toFloat())
                    cursorY = cursorY.coerceIn(0f, (size.height - 1).coerceAtLeast(0).toFloat())
                }
                if (mode == NavMode.Pointer) webView?.setPageCursor(cursorX, cursorY)
            },
        ) {
            key(challenge?.id) {
                AndroidView(
                    factory = { c ->
                        WebView(c).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(android.graphics.Color.BLACK)
                            overScrollMode = WebView.OVER_SCROLL_NEVER
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setOnKeyListener { _, keyCode, event ->
                                if (event.action == AndroidKeyEvent.ACTION_DOWN &&
                                    pointerKeyAction.get().invoke(keyCode, event.repeatCount)
                                ) {
                                    // Pointer mode owns the D-pad; the page must
                                    // not also run its focus navigation.
                                    true
                                } else if (event.action == AndroidKeyEvent.ACTION_DOWN &&
                                    (keyCode == AndroidKeyEvent.KEYCODE_MENU ||
                                        keyCode == AndroidKeyEvent.KEYCODE_BUTTON_Y)
                                ) {
                                    post { focusToolbarAction.get().invoke() }
                                    true
                                } else {
                                    false
                                }
                            }
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                javaScriptCanOpenWindowsAutomatically = true
                                // Login redirects often open in a popup; keep one
                                // WebView so cookies and window.opener stay intact
                                // on the same document.
                                setSupportMultipleWindows(false)
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(true)
                                builtInZoomControls = false
                                displayZoomControls = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                allowFileAccess = false
                                allowContentAccess = false
                                cacheMode = WebSettings.LOAD_DEFAULT
                                textZoom = 100
                            }
                            userAgent?.let { settings.userAgentString = it }
                            CookieManager.getInstance()?.let { cm ->
                                cm.setAcceptCookie(true)
                                // Login flows routinely bounce through a
                                // different origin; third-party cookies are
                                // what keeps the session alive across it.
                                cm.setAcceptThirdPartyCookies(this, true)
                            }
                            addJavascriptInterface(object {
                                @android.webkit.JavascriptInterface
                                fun postMessage(message: String) {
                                    val obj = runCatching { JSONObject(message) }.getOrNull() ?: return
                                    if (obj.optBoolean("__wafKeyboard")) {
                                        post { showIme() }
                                        return
                                    }
                                    if (obj.optString("__wafNav").equals("up", true)) {
                                        post { focusToolbarAction.get().invoke() }
                                        return
                                    }
                                    if (!obj.optBoolean("__waf")) return
                                    val id = pendingId ?: return
                                    val payload = when {
                                        obj.has("data") && !obj.isNull("data") ->
                                            obj.opt("data")?.toString()
                                        obj.has("token") && !obj.isNull("token") ->
                                            obj.opt("token")?.toString()
                                        else -> null
                                    }?.takeIf { it.isNotBlank() } ?: return
                                    if (bridgeSettled.compareAndSet(false, true)) {
                                        runCatching { CookieManager.getInstance().flush() }
                                        post {
                                            done = true
                                            status = "Solved ✓"
                                            WafCoordinator.deliverAndThen(id, payload) { onSolved() }
                                        }
                                    }
                                }
                            }, "ReactNativeWebView")
                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: android.os.Message?,
                                ): Boolean {
                                    // Reached only if a future WebView ignores the
                                    // single-window setting: navigate the same view
                                    // rather than losing the login to a dead popup.
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                                    transport?.webView = view
                                    resultMsg?.sendToTarget()
                                    return transport != null
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    if (!done) status = "Loading page…"
                                }

                                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                    super.onPageFinished(view, loadedUrl)
                                    runCatching { CookieManager.getInstance().flush() }
                                    injectedJavaScript?.takeIf { it.isNotBlank() }?.let {
                                        view?.evaluateJavascript(it, null)
                                    }
                                    view?.evaluateJavascript(SPATIAL_NAV_JS, null)
                                    // The pointer element must exist before the
                                    // first D-pad press; its coordinates are
                                    // kept in native state and re-applied here.
                                    view?.evaluateJavascript(PAGE_POINTER_JS, null)
                                    if (view != null && mode == NavMode.Pointer) {
                                        if (cursorX < 0f || cursorY < 0f) {
                                            cursorX = view.width / 2f
                                            cursorY = view.height / 2f
                                        }
                                        view.setPageCursor(cursorX, cursorY)
                                        view.setPageCursorVisible(true)
                                    }
                                    if (!done) {
                                        status = when {
                                            pendingId == null -> "Complete the check, then press Continue"
                                            waitForCookie != null -> "Waiting for sign-in to finish…"
                                            else -> "Sign in or complete the check, then press Continue"
                                        }
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true && !done) {
                                        status = "Page failed to load — check the connection"
                                    }
                                }
                            }
                            webView = this
                            pendingId?.let { WafCoordinator.attach(it, this) }
                            if (headers.isEmpty()) loadUrl(targetUrl)
                            else loadUrl(targetUrl, headers)
                        }
                    },
                    update = { view ->
                        view.isFocusable = true
                        view.isFocusableInTouchMode = true
                    },
                    modifier = Modifier.fillMaxSize().focusRequester(webViewFocus),
                    onRelease = { view ->
                        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                        runCatching { view.stopLoading() }
                        runCatching { view.destroy() }
                        if (webView === view) webView = null
                    },
                )
            }
        }
    }

    LaunchedEffect(pendingId, targetUrl) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        val manualDeadline = System.currentTimeMillis() +
            (if (timeoutMs in 1_000..600_000) timeoutMs else 180_000)
        while (!done) {
            delay(1000)
            if (pendingId != null && WafCoordinator.pending.value?.id != pendingId) {
                return@LaunchedEffect
            }
            if (pendingId == null && System.currentTimeMillis() >= manualDeadline) break
            // getCookie is a binder call. Running it on the main thread every
            // second was itself a source of jank while the page was scrolling.
            val cookies = withContext(Dispatchers.IO) {
                runCatching {
                    parseCookieHeader(cm.getCookie(targetUrl).orEmpty())
                }.getOrDefault(emptyMap())
            }
            val watchedNow = cookies[watchedCookieName]
            val satisfied = when {
                // A provider that named a cookie asked for exactly this
                // signal; upstream's sandbox resolves the moment it exists.
                waitForCookie != null -> watchedNow != null
                // `waitForLoad` is the "press to continue" interstitial: there
                // is no cookie to watch, and the page finishing is the answer.
                waitForLoad && pendingId != null -> (webView?.progress ?: 0) >= 100
                pendingId != null -> false
                // Manual route: a clearance that was already there cannot be
                // this page's answer, or the dialog would close on open.
                else -> watchedNow != null && watchedNow != initialWatchedCookie
            }
            if (satisfied) {
                if (bridgeSettled.compareAndSet(false, true)) {
                    done = true
                    status = "Solved ✓"
                    runCatching { CookieManager.getInstance().flush() }
                    if (pendingId != null) {
                        WafCoordinator.deliverAndThen(pendingId) { onSolved() }
                    } else {
                        onSolved()
                    }
                }
                return@LaunchedEffect
            }
        }
        if (!done) {
            status = "Timed out — press Close and retry"
        }
    }
}

/** BACK must dismiss a pending provider challenge, not orphan it. */
@Composable
private fun DisposableEffectBackHandler(onBack: () -> Unit) {
    val view = LocalView.current
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(view) {
        // Key events, not the activity back dispatcher: a TV remote's BACK is a
        // real key event, and a provider-suspended challenge must be answered
        // on both paths. `focused` is checked because an unhandled BACK on a
        // TV box is delivered to the launcher otherwise.
        val listener = view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == AndroidKeyEvent.KEYCODE_BACK &&
                event.action == AndroidKeyEvent.ACTION_UP
            ) {
                currentOnBack()
                true
            } else {
                false
            }
        }
        onDispose { view.setOnKeyListener(null) }
    }
}
