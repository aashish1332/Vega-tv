package tv.vega.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.vega.tv.ui.theme.ScrimTag
import tv.vega.tv.ui.theme.VegaOutlineVariant
import tv.vega.tv.ui.theme.VegaPrimary

/**
 * Visual twin of web MediaPosterCard (r18, tag top-right, badge top-left,
 * title 12sp + subtitle 11sp) with weak-TV cuts:
 * - No BlurView: tag is opaque scrim (one layer, no GPU blur pass).
 * - No FadeInDown per card: only tv-material focus scale (1.05, one layer).
 * - No shadow/elevation: hairline resting border + 2dp focused border.
 * - Coil downsampled at decode to the card's shape; crossfade comes from the
 *   shared ImageLoader (memory-cache hits still show instantly).
 */
@Composable
fun VegaCard(
    title: String,
    poster: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tag: String? = null,
    badge: String? = null,
    aspectRatio: Float = 2f / 3f,
) {
    // Drives the title marquee: only the card the remote is sitting on scrolls
    // its name, and it scrolls only while the name is wider than the card.
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        border = CardDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, VegaOutlineVariant),
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, VegaPrimary),
            ),
        ),
        // A smaller focus jump avoids adjacent cards colliding on 720p TVs.
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        val ctx = LocalContext.current
        // Wide cards decode wide: a 300x450 portrait decode for a 16:9 tile
        // throws most of the bitmap away after paying for it.
        val wide = aspectRatio >= 1f
        val decodeW = if (wide) 640 else 300
        val decodeH = if (wide) 360 else 450
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (!poster.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx)
                        .data(poster)
                        .size(decodeW, decodeH)
                        .allowHardware(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Opaque placeholder: single layer, no blur, first letter.
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        title.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Badge wins over tag (same as web).
            if (badge != null) {
                Text(
                    badge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            } else if (!tag.isNullOrBlank()) {
                Text(
                    tag.trim().uppercase(),
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ScrimTag)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        MarqueeText(
            text = title,
            active = focused,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
            )
        }
    }
}
