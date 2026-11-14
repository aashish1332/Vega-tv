package tv.vega.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.vega.tv.data.model.VegaPost

/**
 * Twin of web Slider: titleLarge row + "See all" 92x36 pill + horizontal
 * posters (gap 14, padH 20). Weak-TV cuts: plain LazyRow (no RNGH), fixed
 * card widths, no skeleton shimmer loop (static placeholders), errors inline.
 */
@Composable
fun VegaRow(
    title: String,
    posts: List<VegaPost>,
    onOpenPost: (VegaPost) -> Unit,
    onSeeAll: ((String) -> Unit)? = null,
    filter: String = "",
    isLoading: Boolean = false,
    error: String? = null,
    showSeeAll: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            if (showSeeAll && onSeeAll != null) {
                Button(
                    onClick = { onSeeAll(filter) },
                    modifier = Modifier.width(92.dp),
                ) {
                    Text("See all", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        when {
            isLoading -> LoadingRow()
            error != null -> Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(20.dp),
            )
            posts.isEmpty() -> Text(
                "No content found",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            else -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Leading spacer is keyed inside the row's own range: an
                // unkeyed `item {}` alongside a keyed `items` makes Lazy throw on
                // duplicate keys when two rails on one screen share a post.
                item(key = "row-leader") { androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp)) }
                items(
                    posts,
                    key = { it.link.ifBlank { it.title } },
                    contentType = { "poster" },
                ) { post ->
                    val ratio = parseAspectRatio(post.aspectRatio)
                    val w = cardWidthForRatio(ratio).dp
                    VegaCard(
                        title = post.title,
                        poster = post.image,
                        tag = post.cornerTag ?: post.tag,
                        aspectRatio = ratio,
                        onClick = { onOpenPost(post) },
                        modifier = Modifier.width(w),
                    )
                }
                item(key = "row-trailer") { androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp)) }
            }
        }
    }
}
