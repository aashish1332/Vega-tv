package tv.vega.tv.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.vega.tv.ui.theme.ProgressTrack
import tv.vega.tv.ui.theme.VegaPrimary
import tv.vega.tv.ui.theme.VegaTertiary

/** Web CompactChip twin: #171717 bg, primary text, r8, 9x5 pad. */
@Composable
fun VegaChip(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = VegaPrimary,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VegaTertiary)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/** Continue-watching progress: fixed primary bar (no per-poster Palette). */
@Composable
fun VegaProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
            .background(ProgressTrack),
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp)
                .background(VegaPrimary),
        )
    }
}

/**
 * A gently pulsing block for loading states: one alpha layer for the whole
 * skeleton, so the GPU only composites — nothing re-layouts per frame.
 */
@Composable
fun SkeletonBox(modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    Box(
        modifier.clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** One shared pulse for every skeleton on screen (never per-block loops). */
@Composable
fun Modifier.skeletonPulse(): Modifier {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 850), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    return this.graphicsLayer { this.alpha = alpha }
}

@Composable
fun LoadingRow(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).skeletonPulse(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        repeat(6) {
            SkeletonBox(Modifier.width(124.dp).height(186.dp), corner = 14.dp)
        }
    }
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp).skeletonPulse()) {
        SkeletonBox(Modifier.width(260.dp).height(34.dp), corner = 10.dp)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(5) { SkeletonBox(Modifier.width(124.dp).height(186.dp), corner = 14.dp) }
        }
        Spacer(Modifier.height(28.dp))
        SkeletonBox(Modifier.width(180.dp).height(26.dp), corner = 8.dp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(5) { SkeletonBox(Modifier.width(124.dp).height(186.dp), corner = 14.dp) }
        }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

/** Error with optional Cloudflare-solver action (mirrors WafWebViewDialog entry). */
@Composable
fun WafError(
    message: String,
    wafUrl: String?,
    onRetry: () -> Unit,
    onSolveWaf: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("Retry") }
            if (!wafUrl.isNullOrBlank()) {
                Button(onClick = { onSolveWaf(wafUrl) }) { Text("Solve challenge") }
            }
        }
    }
}
