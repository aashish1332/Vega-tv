package tv.vega.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.vega.tv.data.model.EpisodeItem
import tv.vega.tv.ui.theme.ProgressTrack
import tv.vega.tv.ui.theme.ScrimTag
import tv.vega.tv.ui.theme.VegaOutlineVariant
import tv.vega.tv.ui.theme.VegaPrimary

/**
 * Episode tile for the detail screen.
 *
 * Episodes are landscape stills (or nothing at all), so a 2:3 poster card is
 * the wrong shape for them: it turns a 90dp-tall thumbnail into a 240dp-tall
 * slab, and on providers that ship no still the card degenerates into a huge
 * letter tile. This card is 16:9 at 160dp — the same shape as the reference
 * web episode row — and always says something useful when there is no image:
 * the episode number and a play glyph.
 *
 * The label comes from [episodeLabel], because providers hand back file names
 * ("Show.S01E02.1080p.WEB-DL.x265.mkv") and an episode list that reads like a
 * directory listing is unusable.
 */
@Composable
fun EpisodeCard(
    episode: EpisodeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watched: Boolean = false,
    progress: Float? = null,
    // A movie's "episodes" are its quality renditions. Their titles are the
    // entire point ("4K HDR REMUX HEVC H265 IMAX 80.2GB"), and the file-name
    // heuristics would collapse them to "Episode 1" — complete with a
    // meaningless EP chip.
    qualityCard: Boolean = false,
) {
    val label = if (qualityCard) {
        episode.title.trim().ifBlank { episodeLabel(episode) }
    } else {
        episodeLabel(episode)
    }
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(EPISODE_CARD_WIDTH_DP.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, VegaOutlineVariant),
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, VegaPrimary),
                ),
            ),
            scale = CardDefaults.scale(focusedScale = 1.06f),
        ) {
            val ctx = LocalContext.current
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val img = episode.image?.trim().orEmpty()
                if (img.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data(img)
                            .size(320, 180)
                            .allowHardware(true)
                            .build(),
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // No still: play glyph and the episode number, centered as
                    // one stack. A provider that ships no images at all must
                    // still produce a row that reads as episodes.
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp),
                        )
                        val num = if (qualityCard) "" else episodeNumber(episode)
                        if (num.isNotBlank()) {
                            Text(
                                "EP $num",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                if (watched) {
                    Box(
                        Modifier.align(Alignment.Center).size(30.dp)
                            .clip(CircleShape).background(VegaPrimary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Watched",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // Episode number chip when there is an image to put it on.
                val num = if (qualityCard) "" else episodeNumber(episode)
                if (img.isNotBlank() && !watched && num.isNotBlank()) {
                    Text(
                        "EP $num",
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(ScrimTag)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            if (progress != null && progress > 0.01f) {
                Box(
                    Modifier.fillMaxWidth().height(3.dp)
                        .background(ProgressTrack),
                ) {
                    Box(
                        Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                            .background(VegaPrimary),
                    )
                }
            }
        }
        MarqueeText(
            text = label,
            active = focused,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 2.dp),
        )
    }
}

/** Width in dp shared by every episode row so gaps stay even. */
const val EPISODE_CARD_WIDTH_DP: Int = 160

/**
 * Continue-watching tile.
 *
 * A poster is the wrong shape for "partially watched episode 4 of a show": the
 * thing worth showing is the still, how much of it is left, and which episode.
 * 16:9 at 220dp answers all three without the card becoming a slab.
 */
@Composable
fun ResumeCard(
    title: String,
    poster: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float = 0f,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(220.dp)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(10.dp)),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, VegaOutlineVariant),
                ),
                focusedBorder = Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, VegaPrimary),
                ),
            ),
            scale = CardDefaults.scale(focusedScale = 1.06f),
        ) {
            val ctx = LocalContext.current
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val img = poster?.trim().orEmpty()
                if (img.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx)
                            .data(img)
                            .size(440, 248)
                            .allowHardware(true)
                            .build(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).size(26.dp),
                    )
                }
                if (progress > 0.01f) {
                    Box(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp)
                            .background(ProgressTrack),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp)
                                .background(VegaPrimary),
                        )
                    }
                }
            }
        }
        MarqueeText(
            text = title,
            active = focused,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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

private val SE_EPISODE = Regex("""(?i)\bS\d{1,3}\s*[.\-_ ]?\s*E(?:P)?\s*(\d{1,3})\b""")
private val WORD_EPISODE = Regex("""(?i)\b(?:episode|ep)\s*[._\- ]?\s*(\d{1,4})\b""")
private val TAG_E = Regex("""(?i)\bE\s*[._\-]?\s*(\d{1,4})\b""")
private val LEADING_NUMBERED = Regex("""^\s*\d{1,3}\s*[._\-]\s*\S""")
private val LEADING_NUM = Regex("""^\s*(\d{1,3})\s*[._\-]\s*\S""")
private val FILE_EXT =
    Regex("""(?i)\.(mkv|mp4|avi|m4v|ts|m3u8|webm|mov|flv|wmv)$""")
private val FILE_JUNK =
    Regex(
        """(?i)\b(x264|x265|h\.?264|h\.?265|hevc|av1|aac|ac3|eac3|ddp?5\.?1?""" +
            """|10bit|8bit|1080p|720p|480p|2160p|1440p|4k|uhd|web[.\-]?dl|webrip|""" +
            """bluray|brrip|hdrip|dvdrip|hdtv|remux|proper|repack)\b""",
    )

private fun looksLikeFileName(title: String): Boolean =
    FILE_EXT.containsMatchIn(title) ||
        FILE_JUNK.containsMatchIn(title) ||
        LEADING_NUMBERED.containsMatchIn(title)

/**
 * The episode's own number as its title states it, or null when the title
 * does not carry one.
 *
 * [EpisodeItem.number] is the provider's list position, and list order is not
 * trustworthy: hosts that list newest-first made the position count 1,2,3…
 * while the titles counted 12,11,10…, so the chip inside a card ran in
 * reverse compared to the label under it. S01E12 / "Episode 12" / "E12" /
 * "12 - Title" all state the truth; the position is only the last resort.
 */
private fun numberFromTitle(raw: String): Int? {
    raw.trim().toIntOrNull()?.let { if (it in 1..9999) return it }
    SE_EPISODE.find(raw)?.let { return it.groupValues[1].toInt() }
    WORD_EPISODE.find(raw)?.let { return it.groupValues[1].toInt() }
    if (!looksLikeFileName(raw)) return null
    TAG_E.find(raw)?.let { return it.groupValues[1].toInt() }
    LEADING_NUM.find(raw)?.let { return it.groupValues[1].toInt() }
    return null
}

internal fun episodeNumber(episode: EpisodeItem): String =
    (numberFromTitle(episode.title) ?: episode.number.takeIf { it > 0 })?.toString().orEmpty()

/**
 * A title a person can read, out of whatever the provider called this episode.
 *
 * Providers return either a human title ("Episode 2: The Heist"), a bare
 * number, or a file name ("S01E02.1080p.WEB-DL.x265.mkv"). Only the first two
 * are usable on a TV card; a file name is reduced to its episode number.
 */
internal fun episodeLabel(episode: EpisodeItem): String {
    val raw = episode.title.trim()
    if (raw.isBlank()) return "Episode ${episodeNumber(episode)}".trim()
    if (!looksLikeFileName(raw)) return raw
    numberFromTitle(raw)?.let { return "Episode $it" }
    val n = episodeNumber(episode)
    return if (n.isBlank()) "Episode" else "Episode $n"
}
