package tv.vega.tv.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

/**
 * How long a load may take before any progress UI is justified. A fast fetch
 * (the common provider-cache case) must paint nothing rather than flash.
 */
private const val LOADING_DELAY_MS = 180L

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {
    // A single spinner, not a grid of pulsing poster rectangles. The skeleton
    // cards made every provider switch / catalog open look like a content
    // reset and cost a full-screen layout + alpha animation on weak TV GPUs.
    // The delay keeps a 100–200ms cached load completely flicker-free.
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_DELAY_MS)
        show = true
    }
    if (!show) return
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VegaSpinner()
    }
}

/**
 * Rotating arc spinner. One implementation so "working" reads identically in
 * the player, on the detail backdrop and anywhere else a load must be shown
 * without placeholder cards.
 */
@Composable
fun VegaSpinner(modifier: Modifier = Modifier, diameter: Dp = 48.dp) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(16)
            angle = (angle + 6f) % 360f
        }
    }
    Canvas(modifier.size(diameter)) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2f
        drawCircle(
            color = Color.White.copy(alpha = 0.15f),
            radius = (size.minDimension - stroke) / 2f,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = VegaPrimary,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
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
        Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
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
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
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
            Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Retry") }
            if (!wafUrl.isNullOrBlank()) {
                Button(onClick = { onSolveWaf(wafUrl) }) { Text("Solve challenge") }
            }
        }
    }
}

/**
 * One-line card title that scrolls — the web's marquee — while its card holds
 * focus and the name is wider than the card. Everywhere else it sits clipped
 * at the card edge instead of ending in "…", which was hiding the tail of
 * every long name.
 *
 * Only the focused card animates, so a row of twenty tiles still costs one
 * moving layer. `iterations` (0 vs forever) is the on/off switch because it is
 * the one parameter the marquee restarts for without recreating its node; the
 * scroll also cancels and snaps back the moment focus leaves, so nothing is
 * left mid-word on screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    Text(
        text,
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        // Unbounded width is what basicMarquee measures with; soft wrap or a
        // second line would defeat the scroll and reintroduce the clip.
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier.basicMarquee(
            iterations = if (active) Int.MAX_VALUE else 0,
            repeatDelayMillis = 1_800,
            initialDelayMillis = 350,
            // Slower than a phone's: read from three metres away.
            velocity = 40.dp,
        ),
    )
}
