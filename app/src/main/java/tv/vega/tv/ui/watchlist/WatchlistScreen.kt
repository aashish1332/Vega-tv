package tv.vega.tv.ui.watchlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.repo.FamilyFilter
import tv.vega.tv.data.watchlist.WatchItem
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.ui.common.EmptyState
import tv.vega.tv.ui.common.VegaCard

/** Twin of web WatchList: poster grid → Info. Delete lives in Detail toggle. */
@Composable
fun WatchlistScreen(onOpenItem: (WatchItem) -> Unit, onBrowse: () -> Unit) {
    val items by ServiceLocator.watchlist.items.collectAsStateWithLifecycle(initialValue = emptyList())
    val familyOn by ServiceLocator.settings.familyFilter
        .collectAsStateWithLifecycle(initialValue = true)
    val visible = remember(items, familyOn) {
        if (familyOn) items.filter { FamilyFilter.isAllowed(it.title) } else items
    }
    if (visible.isEmpty()) {
        EmptyState(
            message = if (items.isEmpty()) "Your watchlist is empty" else "No watchlist titles match the family filter",
            actionLabel = "Browse",
            onAction = onBrowse,
        )
        return
    }
    // A grid is nothing but focusable cards, so without an explicit request the
    // screen arrives with nothing focused: the viewer has to press a direction
    // key before anything on screen acknowledges the remote.
    val firstCard = remember { FocusRequester() }
    val firstKey = visible.firstOrNull()?.link.orEmpty()
    LaunchedEffect(visible.isEmpty()) {
        if (visible.isNotEmpty()) runCatching { firstCard.requestFocus() }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // Posters are the whole page here, so the grid owns the screen. A
        // uniform 32dp frame around it read as black bars on every side; the
        // breathing room lives inside the scrollable content instead.
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(visible, key = { it.link }, contentType = { "poster" }) { w ->
            VegaCard(
                title = w.title,
                poster = w.poster.ifBlank { null },
                onClick = {
                    PlaybackSession.pendingLink = w.link
                    PlaybackSession.pendingTitle = w.title
                    PlaybackSession.pendingPoster = w.poster
                    // The saved entry remembers the provider it belongs to;
                    // resolving the link on whichever provider happens to be
                    // active can open a different title's page.
                    PlaybackSession.pendingProvider = w.provider
                    onOpenItem(w)
                },
                modifier = if (w.link == firstKey) Modifier.focusRequester(firstCard) else Modifier,
            )
        }
    }
}
