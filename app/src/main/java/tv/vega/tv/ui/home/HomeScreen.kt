package tv.vega.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.history.WatchEntry
import tv.vega.tv.data.history.WatchHistoryStore
import tv.vega.tv.data.model.EpisodeItem
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.ui.common.ErrorScreen
import tv.vega.tv.ui.common.LoadingScreen
import tv.vega.tv.ui.common.ResumeCard
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.VegaRow

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
    val updateTag by vm.updateAvailable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(176.dp).padding(start = 12.dp, top = 24.dp, end = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Vega",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Home, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Home")
            }
            Button(onClick = vm::refresh, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Refresh")
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
        }

        when (val s = state) {
            is UiState.Loading -> LoadingScreen(Modifier.fillMaxSize())
            is UiState.Error -> ErrorScreen(s.message, vm::load, Modifier.fillMaxSize())
            is UiState.Success -> {
                val rows = s.data.rows
                val diagnostic = s.data.diagnostic
                val heroPost = rows.firstOrNull()?.posts?.firstOrNull()
                LazyColumn(
                    Modifier.fillMaxSize().padding(start = 16.dp, top = 24.dp, end = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (updateTag != null) {
                        item(key = "update") {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Update available: $updateTag — see Settings → About",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(12.dp))
                                Button(onClick = vm::dismissUpdate) { Text("Dismiss") }
                            }
                        }
                    }
                    if (heroPost != null) {
                        item(key = "hero") {
                            HeroSection(
                                item = heroPost,
                                onPlay = { onOpenPost(heroPost) },
                                onDetails = { onOpenPost(heroPost) },
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (continueWatching.isNotEmpty()) {
                        item(key = "continue-header") {
                            Text(
                                "Continue Watching",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                            )
                        }
                        item(key = "continue-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                items(
                                    continueWatching,
                                    key = { it.mediaId },
                                    contentType = { "resume" },
                                ) { e ->
                                    ResumeCard(
                                        title = e.title,
                                        poster = e.posterUrl.ifBlank { null },
                                        subtitle = e.episodeTitle.ifBlank { null },
                                        progress = WatchHistoryStore.progressFraction(e),
                                        onClick = { scope.launch { resumeEntry(e, onPlayEntry) } },
                                    )
                                }
                            }
                        }
                    }
                    rows.forEach { row ->
                        item(key = "row-${row.title}") {
                            VegaRow(
                                title = row.title,
                                posts = row.posts,
                                onOpenPost = onOpenPost,
                                // "See all" now has somewhere to go: a paginated
                                // grid of the same catalog filter.
                                onSeeAll = { filter -> onOpenCategory(row.title, filter) },
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

/**
 * Rebuild the playlist for a continue-watching entry and resume, mirroring
 * web getResumePosition (ignore ≤5s, restart if >85%).
 */
private suspend fun resumeEntry(e: WatchEntry, onPlayEntry: () -> Unit) {
    val link = e.link.ifBlank { return }
    runCatching {
        val info = ServiceLocator.repository.meta(link)
        val season = info.linkList.firstOrNull() ?: return
        val eps: List<EpisodeItem> = season.directLinks.mapIndexed { i, d ->
            EpisodeItem(title = d.title, link = d.link, image = d.image, number = i + 1)
        }.ifEmpty {
            val url = season.episodesLink.orEmpty()
            if (url.isBlank()) emptyList()
            else {
                val a = ServiceLocator.providers.active() ?: return
                runCatching { ServiceLocator.providers.getEpisodes(a, url) }
                    .getOrDefault(emptyList()).mapIndexed { i, ep -> ep.copy(number = i + 1) }
            }
        }
        if (eps.isEmpty()) return
        val idx = e.episodeIndex.coerceIn(eps.indices)
        PlaybackSession.set(info.title.ifBlank { e.title }, info.image.ifBlank { e.posterUrl }, eps, idx, info)
        val frac = WatchHistoryStore.progressFraction(e)
        PlaybackSession.resumePositionMs =
            if (e.positionMs > 5000 && frac <= 0.85f) e.positionMs else 0L
        PlaybackSession.pendingLink = link
        PlaybackSession.pendingTitle = info.title.ifBlank { e.title }
        PlaybackSession.pendingPoster = info.image.ifBlank { e.posterUrl }
        onPlayEntry()
    }
}
