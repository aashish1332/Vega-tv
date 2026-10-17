package tv.vega.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import tv.vega.tv.ui.waf.WafScreen
import tv.vega.tv.ui.watchlist.WatchlistScreen

class MainActivity : ComponentActivity() {
    private var backPressedAt = 0L
    internal var nav: androidx.navigation.NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onResume() {
        super.onResume()
        goFullscreen()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
            else -> {
                // Playback keys are handled here, not in the player view. An
                // AndroidView only gets DPAD/OK when it holds Android focus, and
                // a player screen routinely does not — the app is re-creating the
                // surface, or focus has moved to a Compose overlay, and in that
                // state every key fell through to the Activity and did nothing.
                // Consuming them only while the player is actually on screen
                // keeps this from stealing the remote anywhere else.
                if (PlayerKeyBus.onKey?.invoke(keyCode) == true) return true
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

    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(onDone = {
                nav.navigate(Routes.HOME) { popUpTo(Routes.SPLASH) { inclusive = true } }
            })
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPost = { post ->
                    PlaybackSession.pendingLink = post.link
                    PlaybackSession.pendingTitle = post.title
                    PlaybackSession.pendingPoster = post.image
                    nav.navigate(Routes.DETAIL)
                },
                onOpenSearch = { nav.navigate(Routes.SEARCH) },
                onOpenWatchlist = { nav.navigate(Routes.WATCHLIST) },
                onOpenExtensions = { nav.navigate(Routes.EXTENSIONS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onPlayEntry = { nav.navigate(Routes.PLAYER) },
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
                    PlaybackSession.pendingLink = post.link
                    PlaybackSession.pendingTitle = post.title
                    PlaybackSession.pendingPoster = post.image
                    nav.navigate(Routes.DETAIL)
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                onOpenPost = { post ->
                    PlaybackSession.pendingLink = post.link
                    PlaybackSession.pendingTitle = post.title
                    PlaybackSession.pendingPoster = post.image
                    nav.navigate(Routes.DETAIL)
                },
                onSolveWaf = { url -> nav.navigate(Routes.waf(url)) },
            )
        }
        composable(Routes.WATCHLIST) {
            WatchlistScreen(
                onOpenItem = { nav.navigate(Routes.DETAIL) },
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
                onPlay = { nav.navigate(Routes.PLAYER) },
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
}
