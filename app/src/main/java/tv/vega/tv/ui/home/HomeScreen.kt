package tv.vega.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.history.WatchEntry
import tv.vega.tv.data.history.WatchHistoryStore
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.data.model.identityKey
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.ui.common.ErrorScreen
import tv.vega.tv.ui.common.LoadingScreen
import tv.vega.tv.ui.common.ResumeCard
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.VegaRow
import tv.vega.tv.ui.common.VegaSpinner

/**
 * Web Home twin: sidebar nav + hero + continue-watching + catalog rows.
 * Weak-TV rules: single LazyColumn (no nested scroll containers), static
 * placeholders, fixed 150dp continue cards, no accent-per-poster.
 */
@Composable
fun HomeScreen(
    onOpenPost: (VegaPost) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenWatchlist: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlayEntry: () -> Unit,
    onOpenCategory: (String, String) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val continueWatching by vm.continueWatching.collectAsStateWithLifecycle()
    val installed by vm.installedProviders.collectAsStateWithLifecycle()
    val providerFilter by vm.providerFilter.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Some TV Card implementations still deliver the click after a long
    // press. Deleting must never also launch playback (which would save
    // progress and put the card straight back).
    var longPressedId by remember { mutableStateOf<String?>(null) }
    var longPressedAt by remember { mutableLongStateOf(0L) }

    // Keep the sidebar's provider list current without rebuilding the rows:
    // a provider installed on the Providers screen should appear when Home is
    // visible again, but an automatic whole-page refresh moved cards (and
    // focus) under the viewer's remote.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.setHomeVisible(true)
            try {
                vm.refreshInstalledOnly()
                awaitCancellation()
            } finally {
                vm.setHomeVisible(false)
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(176.dp).padding(start = 12.dp, top = 24.dp, end = 4.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Vega",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
            Button(onClick = { vm.setProviderFilter("") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Home, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Home")
            }
            Button(onClick = vm::refresh, modifier = Modifier.fillMaxWidth()) {
                // Progress lives inside the button so the action visibly did
                // something even when the list underneath stays put.
                if (refreshing) {
                    VegaSpinner(diameter = 18.dp)
                } else {
                    Icon(Icons.Default.Sync, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(if (refreshing) "Refreshing…" else "Refresh")
            }
            Button(onClick = onOpenSearch, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Search")
            }
            Button(onClick = onOpenWatchlist, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Watchlist")
            }
            Button(onClick = onOpenExtensions, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Extension, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Providers")
            }
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Settings")
            }
            // Provider separation: with several providers installed the home
            // page gains a scope. "Home" above is the mixed/all view; picking a
            // provider switches the app's active provider to it, so details,
            // search and playback all run against what is being browsed.
            if (installed.size > 1) {
                Text(
                    "Providers",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp),
                )
                installed.forEach { ext ->
                    val ref = "${ext.source.author}:${ext.value}"
                    ProviderButton(
                        label = ext.display_name.ifBlank { ext.value },
                        icon = ext.icon,
                        selected = providerFilter == ref,
                        onClick = { vm.setProviderFilter(ref) },
                    )
                }
            }
        }

        when (val s = state) {
            is UiState.Loading -> LoadingScreen(Modifier.fillMaxSize())
            is UiState.Error -> ErrorScreen(s.message, vm::load, Modifier.fillMaxSize())
            is UiState.Success -> {
                val rows = s.data.rows
                val diagnostic = s.data.diagnostic
                val heroPost = rows.firstOrNull()?.posts?.firstOrNull()
                // The hero is the first card of the first row; in a mixed
                // Home page that row may belong to another provider than the
                // active one.
                val heroRef = rows.firstOrNull()?.providerRef.orEmpty()
                LazyColumn(
                    Modifier.fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 24.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (heroPost != null) {
                        item(key = "hero") {
                            val openHero: () -> Unit = {
                                PlaybackSession.pendingProvider = heroRef
                                vm.cancelLoad()
                                onOpenPost(heroPost)
                            }
                            HeroSection(
                                item = heroPost,
                                onPlay = openHero,
                                onDetails = openHero,
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (continueWatching.isNotEmpty()) {
                        item(key = "continue-header") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Continue Watching",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "Hold OK to remove",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item(key = "continue-row") {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                contentPadding = PaddingValues(vertical = 10.dp),
                            ) {
                                items(
                                    continueWatching,
                                    key = { it.historyKey.ifBlank { it.mediaId } },
                                    contentType = { "resume" },
                                ) { e ->
                                    val entryKey = e.historyKey.ifBlank { e.mediaId }
                                    ResumeCard(
                                        title = e.title,
                                        poster = e.posterUrl.ifBlank { null },
                                        subtitle = e.episodeTitle.ifBlank { null },
                                        progress = WatchHistoryStore.progressFraction(e),
                                        onClick = {
                                            val clickAfterLongPress =
                                                longPressedId == entryKey &&
                                                    System.currentTimeMillis() - longPressedAt < 3000L
                                            if (!clickAfterLongPress) {
                                                // Playback directly: no catalog
                                                // fetch or meta round trip may
                                                // delay the player appearing.
                                                vm.cancelLoad()
                                                resumeEntry(e, onPlayEntry)
                                            }
                                        },
                                        onLongClick = {
                                            // Tombstone + local delete + publish
                                            // are one locked operation; removing
                                            // only the DataStore row let the next
                                            // shared-folder merge re-add the card.
                                            longPressedId = entryKey
                                            longPressedAt = System.currentTimeMillis()
                                            scope.launch {
                                                ServiceLocator.sync.removeHistory(
                                                    e.mediaId, e.link, entryKey,
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    rows.forEach { row ->
                        // Identity is the scope, not the display title: mixed
                        // rows from two providers can share a title, and a
                        // duplicate key makes the lazy list rebuild (and lose
                        // focus) at random. providerRef+filter is unique.
                        item(key = "row-${row.providerRef}:${row.filter}") {
                            // A row from the mixed view knows its origin: hand
                            // it to the detail screen, which activates that
                            // provider before resolving the link. The handoff
                            // is written synchronously — a coroutine here left
                            // a gap where a second tap (or the detail load
                            // itself) could read the previous card's provider.
                            val openPost: (VegaPost) -> Unit = { post ->
                                PlaybackSession.pendingProvider = row.providerRef
                                vm.cancelLoad()
                                onOpenPost(post)
                            }
                            val seeAll: (String) -> Unit = { filter ->
                                val ref = row.providerRef
                                if (ref.isBlank()) onOpenCategory(row.title, filter)
                                else scope.launch { vm.activate(ref); onOpenCategory(row.title, filter) }
                            }
                            VegaRow(
                                title = row.title,
                                posts = row.posts,
                                onOpenPost = openPost,
                                // "See all" now has somewhere to go: a paginated
                                // grid of the same catalog filter.
                                onSeeAll = seeAll,
                                filter = row.filter,
                                error = row.error,
                            )
                        }
                    }
                    if (rows.isEmpty() && continueWatching.isEmpty()) {
                        item(key = "empty-state") {
                            // Show the actual failure. The old hardcoded "No providers
                            // installed" named the one condition that was not true and
                            // hid every real cause behind it.
                            val detail = diagnostic ?: "No providers installed. Add a provider source in Settings → Providers."
                            Text(
                                detail,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                            // Nothing can load without a provider, so offer the fix
                            // here rather than making the user hunt the sidebar.
                            if (diagnostic?.startsWith("No provider is installed") == true) {
                                Spacer(Modifier.height(16.dp))
                                Row(Modifier.padding(horizontal = 24.dp)) {
                                    Button(onClick = onOpenExtensions) { Text("Add provider") }
                                }
                            }
                        }
                    } else if (diagnostic != null) {
                        item(key = "diagnostic") {
                            Text(
                                diagnostic,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Sidebar provider pick: logo plus a check for the scope Home is showing. */
@Composable
private fun ProviderButton(
    label: String,
    icon: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // tv-material flips the button to a light container while focused. Forcing
    // onSurface text here painted near-white on near-white; the focused state
    // must use the surface color that belongs on that container.
    var focused by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
    ) {
        Box(
            Modifier.size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (icon.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(icon)
                        .size(80, 80).allowHardware(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = when {
                focused -> MaterialTheme.colorScheme.surface
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
        if (selected) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = if (focused) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Start a continue-watching entry with the link the provider already gave us.
 *
 * This used to resolve meta + episodes first so Prev/Next had a playlist, but
 * that turned a tap into two sandboxed provider calls before the player route
 * even appeared. The saved episode/link is enough to resolve getStream; the
 * provider is activated inside PlayerViewModel before that call. The trade is
 * deliberate: the viewer gets the video immediately, while Prev/Next can only
 * become available after the title is opened again from its detail page.
 */
private fun resumeEntry(e: WatchEntry, onPlayEntry: () -> Unit) {
    // mediaId is the artist formerly known as "the link playback used". Older
    // builds stored the post/detail page in `link`, so a legacy row's real
    // playback link lives in mediaId. New rows set both to the same value.
    val playable = e.mediaId.ifBlank { e.link }
    val link = playable.ifBlank { return }
    val postPage = e.link.takeIf { it.isNotBlank() && it != playable }.orEmpty()
    val inferredType = when {
        e.mediaType.isNotBlank() -> e.mediaType
        e.episodeTitle.isNotBlank() -> "tv"
        else -> "movie"
    }
    PlaybackSession.title = e.title
    PlaybackSession.posterUrl = e.posterUrl
    PlaybackSession.episodes = emptyList()
    PlaybackSession.startIndex = e.episodeIndex.coerceAtLeast(0)
    PlaybackSession.info = null
    // Restore the complete server/quality picture saved with the row. The
    // player merges these with the fresh resolve (new links win) and falls
    // back to them if the provider call fails, so a resume never collapses to
    // the single mirror that happened to be playing last time.
    PlaybackSession.streams = emptyList()
    PlaybackSession.savedStreams = e.streams
    PlaybackSession.qualityAlternates = e.qualityLinks
    PlaybackSession.preferredStreamKey = e.lastStreamKey.ifBlank {
        e.streams.firstOrNull { it.link == playable }?.identityKey().orEmpty()
    }
    PlaybackSession.playingStreamKey = PlaybackSession.preferredStreamKey
    PlaybackSession.pendingLink = link
    // If the stored identity is still a post page, PlayerViewModel can heal it
    // with one getMeta call instead of failing with a retry button.
    PlaybackSession.pendingFallbackLink = postPage
    PlaybackSession.pendingTitle = e.title
    PlaybackSession.pendingPoster = e.posterUrl
    PlaybackSession.pendingProvider = e.provider
    PlaybackSession.pendingType = inferredType
    PlaybackSession.pendingEpisodeTitle = e.episodeTitle
    val frac = WatchHistoryStore.progressFraction(e)
    PlaybackSession.resumePositionMs =
        if (e.positionMs > 5000 && frac <= 0.85f) e.positionMs else 0L
    onPlayEntry()
}
