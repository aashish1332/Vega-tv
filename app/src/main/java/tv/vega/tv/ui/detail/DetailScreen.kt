package tv.vega.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.model.EpisodeItem
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.ui.common.EmptyState
import tv.vega.tv.ui.common.EpisodeCard
import tv.vega.tv.ui.common.ErrorScreen
import tv.vega.tv.ui.common.LoadingScreen
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.WafError
import tv.vega.tv.ui.common.VegaChip
import tv.vega.tv.ui.theme.ScrimHeavy

/**
 * Twin of ContentOverview + SeasonList: backdrop → chips → synopsis →
 * Play/Search-title/Watchlist actions → per-season episodes with Find +
 * Asc/Desc (same persisted semantics as web, per-season in memory).
 * Weak-TV: one backdrop, windowed rows, no per-episode thumbnails beyond
 * provider images.
 */
@Composable
fun DetailScreen(
    onPlay: () -> Unit,
    onBack: () -> Unit,
    onSolveWaf: (String) -> Unit = {},
    vm: DetailViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wafUrl by vm.wafUrl.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var inLibrary by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }

    when (val s = state) {
        is UiState.Loading -> LoadingScreen(Modifier.fillMaxSize())
        is UiState.Error -> {
            WafError(
                message = s.message,
                wafUrl = wafUrl,
                onRetry = vm::load,
                onSolveWaf = onSolveWaf,
                modifier = Modifier.fillMaxSize(),
            )
        }
        is UiState.Success -> {
            val info = s.data.info
            LaunchedEffect(info.linkList) {
                inLibrary = runCatching {
                    ServiceLocator.watchlist.isIn(PlaybackSession.pendingLink)
                }.getOrDefault(false)
                runCatching { playFocus.requestFocus() }
            }
            LazyColumn(Modifier.fillMaxSize().padding(32.dp)) {
                item(key = "overview") {
                    val ctx = LocalContext.current
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        val bg = info.image.ifBlank { PlaybackSession.pendingPoster }
                        if (bg.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(bg)
                                    .size(1280, 720).allowHardware(true).build(),
                                contentDescription = info.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.horizontalGradient(0f to ScrimHeavy, 0.6f to Color.Transparent),
                            ),
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(0f to Color.Transparent, 0.7f to ScrimHeavy),
                            ),
                        )
                        Column(
                            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(24.dp),
                        ) {
                            Text(
                                info.title.ifBlank { PlaybackSession.pendingTitle },
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 2,
                            )
                            Row(
                                Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                infoChips(info).take(6).forEach { VegaChip(it) }
                            }
                        }
                    }
                    Text(
                        info.synopsis.ifBlank { "No synopsis available" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                // The first season that actually has episodes,
                                // not merely the first season. A show whose
                                // season 1 is specials or behind a fetch that
                                // has not come back yet must not send the user
                                // into an empty episode list.
                                val ready = s.data.seasons.firstOrNull { it.episodes.isNotEmpty() }
                                    ?: s.data.seasons.firstOrNull()
                                val eps = ready?.episodes ?: emptyList()
                                PlaybackSession.set(
                                    info.title.ifBlank { PlaybackSession.pendingTitle },
                                    info.image.ifBlank { PlaybackSession.pendingPoster },
                                    eps, 0, info,
                                )
                                onPlay()
                            },
                            modifier = Modifier.focusRequester(playFocus),
                            enabled = s.data.seasons.any { it.episodes.isNotEmpty() },
                        ) { Text("Play") }
                        Button(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Show less" else "Read more")
                        }
                        Button(onClick = {
                            scope.launch {
                                inLibrary = ServiceLocator.watchlist.toggle(
                                    info.title.ifBlank { PlaybackSession.pendingTitle },
                                    info.image.ifBlank { PlaybackSession.pendingPoster },
                                    PlaybackSession.pendingLink,
                                    ServiceLocator.providers.active()?.ext?.value.orEmpty(),
                                )
                                if (!inLibrary) {
                                    ServiceLocator.sync.addTombstone(
                                        tv.vega.tv.data.sync.Tombstone(
                                            kind = "watchlist",
                                            id = PlaybackSession.pendingLink,
                                            deletedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                                ServiceLocator.syncSoon()
                            }
                        }) { Text(if (inLibrary) "✓ Watchlist" else "Watchlist") }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (s.data.seasons.isEmpty()) {
                    item(key = "no-seasons") {
                        // Focusable, unlike a bare Text: with no episode to
                        // start, the only way forward is BACK, and the user
                        // should be able to see that the screen is alive.
                        EmptyState(
                            message = "No episodes found for this title",
                            actionLabel = "Go back",
                            onAction = onBack,
                        )
                    }
                } else {
                    s.data.seasons.forEachIndexed { si, season ->
                        item(key = "season-$si") {
                            SeasonBlock(
                                season = season,
                                infoTitle = info.title.ifBlank { PlaybackSession.pendingTitle },
                                infoPoster = info.image.ifBlank { PlaybackSession.pendingPoster },
                                info = info,
                                onPlay = onPlay,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonBlock(
    season: ResolvedSeason,
    infoTitle: String,
    infoPoster: String,
    info: tv.vega.tv.data.model.VegaInfo,
    onPlay: () -> Unit,
) {
    var desc by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val eps = remember(season.episodes, desc, query) {
        var list = season.episodes
        if (query.isNotBlank()) list = list.filter { it.title.contains(query, true) }
        if (desc) list.reversed() else list
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                season.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (season.episodes.isNotEmpty()) {
                Button(onClick = { desc = !desc }) { Text(if (desc) "Desc" else "Asc") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { finding = !finding }) { Text("Find") }
            }
        }
        if (finding) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Find episode") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (eps.isEmpty()) {
            Text(
                if (season.episodes.isEmpty()) "No episodes" else "No episodes match \"$query\"",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(
                    eps,
                    // Index in the key: two episodes can legitimately share a
                    // title and number (a provider that lists a recap twice),
                    // and duplicate keys crash a lazy list outright.
                    key = { i, ep -> "ep-$i-${ep.link.ifBlank { ep.title }}" },
                    contentType = { _, _ -> "episode" },
                ) { idx, ep ->
                    // Play *this* episode: the filtered/reversed list is the
                    // playlist, so its own index is the one that must start.
                    // (Link lookups pick the first duplicate when a provider
                    // reuses one URL across episodes.)
                    EpisodeCard(
                        episode = ep,
                        onClick = {
                            PlaybackSession.set(infoTitle, infoPoster, eps, idx, info)
                            onPlay()
                        },
                    )
                }
            }
        }
    }
}

private fun infoChips(info: tv.vega.tv.data.model.VegaInfo): List<String> = buildList {
    if (info.type.isNotBlank()) add(info.type)
    if (!info.imdbId.isNullOrBlank()) add("IMDb")
    info.rating?.takeIf { it.isNotBlank() }?.let { add(it) }
    addAll(info.tags.take(4))
}
