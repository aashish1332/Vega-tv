package tv.vega.tv

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import tv.vega.tv.data.providers.WafCoordinator
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.player.PlayerKeyBus
import tv.vega.tv.ui.catalog.CatalogScreen
import tv.vega.tv.ui.detail.DetailScreen
import tv.vega.tv.ui.extensions.ExtensionsScreen
import tv.vega.tv.ui.settings.ProviderSettingsScreen
import tv.vega.tv.ui.home.HomeScreen
import tv.vega.tv.ui.player.PlayerScreen
import tv.vega.tv.ui.search.SearchScreen
import tv.vega.tv.ui.settings.SettingsScreen
import tv.vega.tv.ui.splash.SplashScreen
import tv.vega.tv.ui.theme.VegaTVTheme
import tv.vega.tv.ui.update.AutoUpdatePrompt
import tv.vega.tv.ui.waf.WafKeyBus
import tv.vega.tv.ui.waf.WafScreen
import tv.vega.tv.ui.watchlist.WatchlistScreen

class MainActivity : ComponentActivity() {
    private var backPressedAt = 0L
    internal var nav: androidx.navigation.NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The TV must not fall asleep on its own. Its inactivity detector
        // cannot know a viewer is watching a paused frame or reading a long
        // synopsis, and the screen going dark mid-session reads as a crash.
        // Idle handling stays in-app (the player's own chrome hides itself);
        // the display stays awake for as long as the app is on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()
        setContent {
            VegaTVTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) {
                    AppNav()
                }
            }
        }
    }

    /**
     * Edge-to-edge, no system bars, and keep it that way.
     *
     * Three layers, because one is not enough on TV firmware:
     *
     *  1. `setDecorFitsSystemWindows(false)` — insets are not consumed by
     *     decor, so Compose measures the whole display.
     *  2. `FLAG_LAYOUT_NO_LIMITS` — the pre-30 way of getting the window past
     *     the status/navigation strips. Without it a TV whose window insets
     *     still report non-zero bars draws its own background behind them and
     *     the page shows black bars top and bottom.
     *  3. `hide(systemBars())` — the bars themselves. This is re-applied in
     *     [onWindowFocusChanged] and [onResume] because hiding is *not*
     *     sticky on many firmwares: after a dialog (WAF WebView, SAF folder
     *     picker, subtitles import) returns, the bars come back visible and
     *     stay visible for the rest of the session.
     */
    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A returning window (dialog dismissed) gets its bars back; restore.
        if (hasFocus) goFullscreen()
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.isForeground = true
    }

    override fun onStop() {
        AppVisibility.isForeground = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        goFullscreen()
    }

    /**
     * Playback keys must be offered to the player *before* Compose sees them.
     *
     * Spatial navigation consumes the D-pad arrows (and OK) inside the view
     * tree whenever a control holds focus, so [onKeyDown] is never called for
     * exactly the presses the player wants: LEFT/RIGHT on the scrubber, or a
     * seek while the chrome is hidden. Dispatching here sees the key first;
     * the player declines everything it does not own (returns false), and
     * normal focus movement proceeds untouched. BACK and MENU stay in
     * [onKeyDown] because they are navigation, not transport.
     */
    /**
     * True while a challenge is visible, whether it is the coordinator's
     * auto-opened dialog or the manual WAF route. The manual route has no
     * pending call, and without this check the player sitting underneath would
     * consume the D-pad before the challenge's WebView pointer sees it.
     */
    private fun wafOnScreen(): Boolean =
        WafCoordinator.pending.value != null ||
            nav?.currentDestination?.route?.startsWith("waf") == true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The challenge browser owns the remote while it is up, whichever view
        // happens to hold focus. Offering the key here (before the focused
        // WebView, Compose, or the player) is what makes its cursor move — a
        // WebView key listener never runs when a Compose button or the Compose
        // host holds focus instead.
        if (wafOnScreen() && WafKeyBus.onKey?.invoke(event) == true) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_BUTTON_MODE,
                -> Unit
                else -> if (!wafOnScreen() &&
                    PlayerKeyBus.onKey?.invoke(event.keyCode) == true
                ) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // The challenge dialog owns the remote; a key that leaked past it must
        // not drive the player behind the modal. BACK closes the challenge.
        val challenge = WafCoordinator.pending.value
        if (wafOnScreen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // A keyboard opened by the sign-in page owns the first BACK.
                if (ViewCompat.getRootWindowInsets(window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                ) {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                        ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                    return true
                }
                if (challenge != null) WafCoordinator.dismiss(challenge.id)
                else nav?.popBackStack()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_MODE -> {
                PlayerKeyBus.onMenu?.let { it(); return true }
            }
            KeyEvent.KEYCODE_BACK -> {
                // Player overlays first (menu/server list), then nav pop,
                // else double-press exit on Home (TV has no task switcher).
                if (PlayerKeyBus.onBack?.invoke() == true) return true
                val controller = nav
                if (controller?.previousBackStackEntry != null && controller.popBackStack()) {
                    backPressedAt = 0
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - backPressedAt < 2_000) {
                    backPressedAt = 0
                    finish()
                    return true
                }
                backPressedAt = now
                Toast.makeText(this, "Press BACK again to exit", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

private fun android.content.Context.findActivity(): MainActivity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is MainActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val SEARCH = "search"
    const val WATCHLIST = "watchlist"
    const val EXTENSIONS = "extensions"
    const val SETTINGS = "settings"
    const val DETAIL = "detail"
    const val PLAYER = "player"
    const val PROVIDER_SETTINGS = "provider-settings"
    const val CATALOG = "catalog/{title}/{filter}"
    const val WAF = "waf?url={url}"
    fun waf(url: String) = "waf?url=${android.net.Uri.encode(url)}"
    fun catalog(title: String, filter: String) =
        "catalog/${android.net.Uri.encode(title)}/${android.net.Uri.encode(filter)}"
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    androidx.compose.runtime.DisposableEffect(nav, activity) {
        activity?.nav = nav
        onDispose { if (activity?.nav === nav) activity.nav = null }
    }

    // A provider's `openWebView` call suspends until a human clears the page.
    // It must surface wherever the call came from — catalog, detail, or the
    // player — without navigating away from playback (that would tear its
    // ExoPlayer down). The challenge is therefore an overlay in this window,
    // not a Dialog: a second window has its own focus and key dispatch, which
    // is exactly where the remote's D-pad got lost. In one window the activity
    // sees every key (WafKeyBus), so the page, the toolbar and the pointer all
    // share one input path.
    val pendingWaf by WafCoordinator.pending.collectAsStateWithLifecycle()
    val challenge = pendingWaf

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.SPLASH,
            // A full-screen alpha fade is the only transition that is essentially
            // free on a TV GPU. It removes the hard cut between feed, detail and
            // player without the layout cost of a slide (and without delaying the
            // player: the incoming video starts under the fade).
            enterTransition = { fadeIn(tween(140)) },
            exitTransition = { fadeOut(tween(90)) },
            popEnterTransition = { fadeIn(tween(140)) },
            popExitTransition = { fadeOut(tween(90)) },
        ) {
            composable(Routes.SPLASH) {
                SplashScreen(onDone = {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } }
                })
            }
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenPost = { post ->
                        PlaybackSession.playbackRequested = true
                        PlaybackSession.pendingLink = post.link
                        PlaybackSession.pendingTitle = post.title
                        PlaybackSession.pendingPoster = post.image
                        nav.navigate(Routes.DETAIL)
                    },
                    onOpenSearch = { nav.navigate(Routes.SEARCH) },
                    onOpenWatchlist = { nav.navigate(Routes.WATCHLIST) },
                    onOpenExtensions = { nav.navigate(Routes.EXTENSIONS) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onPlayEntry = {
                        PlaybackSession.playbackRequested = true
                        nav.navigate(Routes.PLAYER)
                    },
                    onOpenCategory = { title, filter ->
                        nav.navigate(Routes.catalog(title, filter))
                    },
                )
            }
            composable(Routes.CATALOG,
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("filter") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                CatalogScreen(
                    title = entry.arguments?.getString("title").orEmpty(),
                    filter = entry.arguments?.getString("filter").orEmpty(),
                    onOpenPost = { post ->
                        PlaybackSession.playbackRequested = true
                        PlaybackSession.pendingLink = post.link
                        PlaybackSession.pendingTitle = post.title
                        PlaybackSession.pendingPoster = post.image
                        // A catalog page is bound to the provider scope it was
                        // opened in, which is active; no per-card handoff.
                        PlaybackSession.pendingProvider = ""
                        nav.navigate(Routes.DETAIL)
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onOpenPost = { post ->
                        PlaybackSession.playbackRequested = true
                        PlaybackSession.pendingLink = post.link
                        PlaybackSession.pendingTitle = post.title
                        PlaybackSession.pendingPoster = post.image
                        // Results are fanned out across providers, so a row may
                        // belong to a provider that is not the active one. Open
                        // it on the provider that actually listed it.
                        PlaybackSession.pendingProvider = post.origin
                        nav.navigate(Routes.DETAIL)
                    },
                    onSolveWaf = { url -> nav.navigate(Routes.waf(url)) },
                )
            }
            composable(Routes.WATCHLIST) {
                WatchlistScreen(
                    onOpenItem = {
                        PlaybackSession.playbackRequested = true
                        nav.navigate(Routes.DETAIL)
                    },
                    onBrowse = {
                        nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } }
                    },
                )
            }
            composable(Routes.EXTENSIONS) {
                ExtensionsScreen(
                    onOpenSettings = { nav.navigate(Routes.PROVIDER_SETTINGS) },
                )
            }
            composable(Routes.PROVIDER_SETTINGS) {
                ProviderSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.DETAIL) {
                DetailScreen(
                    onPlay = {
                        PlaybackSession.playbackRequested = true
                        nav.navigate(Routes.PLAYER)
                    },
                    onBack = { nav.popBackStack() },
                    onSolveWaf = { url -> nav.navigate(Routes.waf(url)) },
                )
            }
            composable(Routes.PLAYER) {
                PlayerScreen(
                    onBack = { nav.popBackStack() },
                    onSolveWaf = { url -> nav.navigate(Routes.waf(url)) },
                )
            }
            composable(
                Routes.WAF,
                arguments = listOf(navArgument("url") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                val url = entry.arguments?.getString("url").orEmpty()
                WafScreen(
                    url = url,
                    onSolved = { nav.popBackStack() },
                    onClose = { nav.popBackStack() },
                )
            }
        }

        // Sits above the NavHost but only ever shows on a browse screen; see
        // the composable for why it refuses to cover playback.
        AutoUpdatePrompt()

        // The challenge covers everything, including the update notice. It
        // lives in this window so WafKeyBus can route the remote; a Dialog
        // would get its own key dispatch and lose the D-pad.
        if (challenge != null && nav.currentDestination?.route != Routes.WAF) {
            Surface(Modifier.fillMaxSize(), color = Color.Black) {
                WafScreen(
                    url = challenge.url,
                    onSolved = {},
                    onClose = { WafCoordinator.dismiss(challenge.id) },
                )
            }
        }
    }
}
