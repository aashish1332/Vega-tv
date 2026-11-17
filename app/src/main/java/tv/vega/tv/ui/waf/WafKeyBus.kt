package tv.vega.tv.ui.waf

import android.view.KeyEvent

/**
 * Activity-level key hook for the challenge browser.
 *
 * A view key listener is not enough on this screen. The focused view depends on
 * timing and on which mode the viewer chose: the WebView, the Compose host, or
 * a toolbar button. Android only offers a key to the focused view and its
 * key listener, and the WebView is free to consume a D-pad press before a
 * listener on it would see it. The activity sees every key first, so installing
 * the browser's key handling here makes the cursor and the page behave the same
 * in every focus state.
 *
 * The handler must return true only for keys it actually consumed, so BACK and
 * anything the toolbar owns still reach normal navigation.
 */
object WafKeyBus {
    var onKey: ((KeyEvent) -> Boolean)? = null
}
