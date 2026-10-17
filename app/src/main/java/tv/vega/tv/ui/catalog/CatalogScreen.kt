package tv.vega.tv.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.ui.common.EmptyState
import tv.vega.tv.ui.common.ErrorScreen
import tv.vega.tv.ui.common.LoadingScreen
import tv.vega.tv.ui.common.VegaCard

/**
 * A whole catalog category as a grid — the target of every "See all" button.
 *
 * Pages append as the viewer approaches the end of the grid. That is what a
 * LazyColumn already does for the home rows' headers; here the rows are the
 * content, so the trigger is the grid's last visible item.
 */
@Composable
fun CatalogScreen(
    title: String,
    filter: String,
    onOpenPost: (VegaPost) -> Unit,
    onBack: () -> Unit,
    vm: CatalogViewModel = viewModel(),
) {
    LaunchedEffect(filter) { vm.start(filter) }
    val posts by vm.posts.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loadingMore by vm.loadingMore.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()
    // "Near the end" measured against the last visible index, not the last item:
    // a grid of 200 posters must fetch page 2 while the viewer still has rows on
    // screen, not after they have hit the wall.
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            posts.isNotEmpty() && last >= posts.size - 10
        }
    }
    LaunchedEffect(nearEnd, loadingMore) {
        if (nearEnd && !loadingMore) vm.loadMore()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 32.dp, top = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                colors = IconButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.onSurface,
                    focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        val firstCardFocus = remember { FocusRequester() }
        when {
            loading && posts.isEmpty() -> LoadingScreen(Modifier.fillMaxSize())
            error != null && posts.isEmpty() -> ErrorScreen(error!!, vm::loadMore, Modifier.fillMaxSize())
            posts.isEmpty() -> EmptyState(
                message = "Nothing in \"$title\" yet",
                actionLabel = "Go back",
                onAction = onBack,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(
                    start = 32.dp, end = 32.dp, top = 8.dp, bottom = 48.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(
                    posts,
                    key = { i, p -> "${p.link}#$i" },
                    contentType = { _, _ -> "poster" },
                ) { i, post ->
                    VegaCard(
                        title = post.title,
                        poster = post.image,
                        tag = post.cornerTag ?: post.tag,
                        onClick = { onOpenPost(post) },
                        modifier = if (i == 0) Modifier.focusRequester(firstCardFocus) else Modifier,
                    )
                }
            }
        }

        // First card takes focus on arrival, so the grid is live on entry
        // instead of waiting for the viewer to hunt for a focusable node.
        LaunchedEffect(posts.isEmpty(), loading) {
            if (!loading && posts.isNotEmpty()) runCatching { firstCardFocus.requestFocus() }
        }
    }
}
