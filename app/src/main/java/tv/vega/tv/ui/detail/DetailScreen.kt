package tv.vega.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import tv.vega.tv.player.qualityLinksOf
import tv.vega.tv.player.qualityScore
import tv.vega.tv.ui.common.EmptyState
import tv.vega.tv.ui.common.EpisodeCard
import tv.vega.tv.ui.common.ErrorScreen
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.VegaSpinner
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
        is UiState.Loading -> {
            // The poster the viewer just tapped is already in hand: show it
            // as the backdrop and spin — page skeletons would be a step
            // backwards from the context they arrived with.
            val ctx = LocalContext.current
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                val poster = PlaybackSession.pendingPoster
                if (poster.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(poster)
                            .size(1280, 720).allowHardware(true).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0.35f to Color.Transparent, 1f to ScrimHeavy),
                        ),
                    )
                }
                VegaSpinner()
            }
        }
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
            val groups = s.data.seasonGroups
            // "S1" is the default, not just the first tab a provider happened to
            // list: some sources put Specials (S0) first. A single visible group
            // needs no selector at all (movies and one-season shows).
            //
            // A season card in the catalog ("Doraemon Season 3") opens the same
            // series page as that show's other season cards and carries all
            // seasons with it; the viewer expects to land on the season they
            // tapped instead of hunting for it in the tabs. The card title is
            // the only season signal the handoff carries, so match it by
            // number, else by the group's own label (named seasons have no
            // number to match).
            val clickedGroup = remember(groups) {
                val clicked = PlaybackSession.pendingTitle.trim()
                if (clicked.isBlank() || groups.size < 2) null
                else {
                    val number = seasonNumberOf(clicked)
                    groups.firstOrNull { g -> number != null && g.number == number }
                        ?: groups.firstOrNull { g ->
                            g.label.length >= 3 && clicked.contains(g.label, ignoreCase = true)
                        }
                }
            }
            val defaultGroupKey = remember(groups, clickedGroup) {
                clickedGroup?.key
                    ?: groups.firstOrNull { it.number == 1 }?.key
                    ?: groups.firstOrNull()?.key
            }
            var selectedGroupKey by remember(info.linkList) {
                mutableStateOf<String?>(defaultGroupKey)
            }
            val activeGroupKey = selectedGroupKey
                ?.takeIf { key -> groups.any { it.key == key } }
                ?: defaultGroupKey
            val activeSeasons = if (groups.size > 1) {
                s.data.seasons.filter { it.seasonKey == activeGroupKey }
            } else {
                s.data.seasons
            }
            LaunchedEffect(info.linkList) {
                inLibrary = runCatching {
                    ServiceLocator.watchlist.isIn(PlaybackSession.pendingLink)
                }.getOrDefault(false)
                runCatching { playFocus.requestFocus() }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
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
                                val ready = activeSeasons.firstOrNull { it.episodes.isNotEmpty() }
                                    ?: activeSeasons.firstOrNull()
                                val eps = ready?.episodes ?: emptyList()
                                PlaybackSession.set(
                                    info.title.ifBlank { PlaybackSession.pendingTitle },
                                    info.image.ifBlank { PlaybackSession.pendingPoster },
                                    eps, 0, info, qualityLinksOf(info),
                                )
                                // Any season still resolving stops here; its
                                // provider calls must not share the network
                                // with the video about to start.
                                vm.cancelPending()
                                onPlay()
                            },
                            modifier = Modifier.focusRequester(playFocus),
                            enabled = activeSeasons.any { it.episodes.isNotEmpty() },
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
                if (groups.size > 1) {
                    item(key = "season-picker") {
                        Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            Text(
                                "Seasons",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(
                                    groups,
                                    key = { _, group -> group.key },
                                    contentType = { _, _ -> "season-tab" },
                                ) { _, group ->
                                    Button(onClick = { selectedGroupKey = group.key }) {
                                        Text(
                                            (if (group.key == activeGroupKey) "✓ " else "") + group.label,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
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
                    activeSeasons.forEachIndexed { si, season ->
                        item(key = "season-${season.seasonKey}-$si") {
                            SeasonBlock(
                                season = season,
                                infoTitle = info.title.ifBlank { PlaybackSession.pendingTitle },
                                infoPoster = info.image.ifBlank { PlaybackSession.pendingPoster },
                                info = info,
                                // A movie's cards are renditions, already
                                // ordered best-first by the view model; the
                                // Asc/Desc toggle for episodes would make the
                                // Play button start a lower quality copy.
                                qualityCards = tv.vega.tv.player.isMovieInfo(info),
                                onPlay = {
                                    vm.cancelPending()
                                    onPlay()
                                },
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
    qualityCards: Boolean,
    onPlay: () -> Unit,
) {
    var desc by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val eps = remember(season.episodes, desc, query, qualityCards) {
        var list = season.episodes
        if (query.isNotBlank()) list = list.filter { it.title.contains(query, true) }
        if (qualityCards) list.sortedByDescending { qualityScore(it.title) }
        else if (desc) list.reversed() else list
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
                if (!qualityCards) {
                    Button(onClick = { desc = !desc }) { Text(if (desc) "Desc" else "Asc") }
                    Spacer(Modifier.width(8.dp))
                }
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
            if (season.pending) {
                // The season header is published before its episode fetch
                // finishes. "No episodes" here looked like a broken empty row
                // and then popped into cards; a quiet spinner communicates the
                // same wait without a skeleton flash.
                Row(
                    Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VegaSpinner(diameter = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Loading episodes…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    if (season.episodes.isEmpty()) "No episodes" else "No episodes match \"$query\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
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
                            PlaybackSession.set(
                                infoTitle, infoPoster, eps, idx, info, qualityLinksOf(info),
                            )
                            onPlay()
                        },
                        qualityCard = qualityCards,
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
