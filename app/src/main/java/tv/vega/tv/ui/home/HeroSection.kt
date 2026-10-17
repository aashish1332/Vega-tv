package tv.vega.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.ui.common.VegaChip
import tv.vega.tv.ui.theme.ScrimHeavy

/**
 * Web Hero twin, weak-TV edition:
 * - Same look: 16:7 backdrop + left + bottom scrims + title + tag chips +
 *   actions, so the text block reads over any artwork.
 * - Cuts: one 1280x720 image (not full-res), two static gradient layers
 *   (no animated tint), no image-colors accent, no inline search field
 *   (TV keyboard lives on Search screen), no auto-rotate.
 * - D-pad: Play takes initial focus via [autoFocus].
 */
@Composable
fun HeroSection(
    item: VegaPost,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    autoFocus: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { playFocus.requestFocus() }
    }

    Box(
        modifier.fillMaxWidth()
            .aspectRatio(16f / 7f)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (item.image.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data(item.image)
                    .size(1280, 720)
                    .allowHardware(true)
                    .build(),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        // Two static scrims (drawn once, not per frame): left-to-right for the
        // title block, bottom-up for the action row. Together they guarantee
        // legibility over any artwork without a per-image tint computation.
        Box(
            Modifier.matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to ScrimHeavy,
                        0.55f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier.matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1.0f to ScrimHeavy,
                    ),
                ),
        )
        Column(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val chips = listOfNotNull(
                item.tag?.takeIf { it.isNotBlank() },
                item.cornerTag?.takeIf { it.isNotBlank() },
                item.provider?.takeIf { it.isNotBlank() },
            )
            if (chips.isNotEmpty()) {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    chips.take(3).forEach { VegaChip(it) }
                }
            }
            androidx.compose.foundation.layout.Row(Modifier.padding(top = 12.dp)) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.focusRequester(playFocus),
                ) { Text("Play") }
                androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                Button(onClick = onDetails) { Text("Details") }
            }
        }
    }
}
