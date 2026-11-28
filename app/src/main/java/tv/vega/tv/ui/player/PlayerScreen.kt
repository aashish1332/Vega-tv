package tv.vega.tv.ui.player

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlin.math.abs
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.diag.DiagLog
import tv.vega.tv.data.history.WatchEntry
import tv.vega.tv.data.net.NetClients
import tv.vega.tv.data.model.VegaStream
import tv.vega.tv.data.model.SkipInterval
import tv.vega.tv.data.model.identityKey
import tv.vega.tv.player.BufferSignals
import tv.vega.tv.player.MatroskaCueDataSource
import tv.vega.tv.player.ParallelRangeDataSource
import tv.vega.tv.player.VegaLoadControl
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.player.PlayerKeyBus
import tv.vega.tv.player.createDrmSessionManager
import tv.vega.tv.player.drmUnsupportedReason
import tv.vega.tv.player.playbackErrorMessage
import tv.vega.tv.player.isMovieInfo
import tv.vega.tv.player.qualityScore
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.VegaSpinner
import tv.vega.tv.ui.theme.ScrimHeavy
import tv.vega.tv.ui.theme.VegaPrimary

private const val AUTO_HIDE_MS = 5_000L
private const val UI_TICK_MS = 200L
private const val SEEK_STEP_MS = 10_000L
private const val AUTO_NEXT_SECONDS = 8
private const val MIN_TRACK = 0.001f

private data class Skip(val title: String, val fromMs: Long, val toMs: Long)

/**
 * What the seek overlay shows: direction, the accumulated step of the current
 * key burst (±10s per press, so a held key reads as ±30s), and the target time.
 *
 * [blocked] is the honest answer for a source ExoPlayer cannot seek (a live
 * edge or an index-less file): the overlay says so instead of letting the seek
 * silently restart playback at 0:00. [live] picks the wording.
 */
private data class SeekOsd(
    val direction: Int,
    val targetMs: Long,
    val stepMs: Long,
    val blocked: Boolean = false,
    val live: Boolean = false,
)

/**
 * Burst-seek accumulator that survives key-handler effect restarts.
 *
 * The handler effect is re-keyed whenever the UI shows or hides, so state owned
 * by the effect dies at exactly the wrong moment: the keypress that starts a
 * seek is also the keypress that reveals the UI. This holder lives in
 * `remember`, one level above the effect's churn.
 */
private class SeekCoalescer {
    var pendingMs = 0L
    var job: Job? = null
}

/**
 * Forward-buffer budget in bytes for this device.
 *
 * A fixed cap cannot serve both a 2 GB TV stick and a 4K box. The old fixed
 * 64 MB was only ~6 s at 80 Mbps, and a 5 s back buffer took most of it (see
 * [VegaLoadControl]), so a large 4K HDR file had almost no forward cushion and
 * rebuffered continuously. The official client instead sizes the buffers from
 * the device: at most 35% of the app heap and 1/32 of total RAM. Keep the old
 * 64 MB as the floor so no device gets a smaller buffer than 1.1.3, and cap the
 * result at 256 MB — enough for ~20 s of a 100 Mbps remux while still a
 * fraction of the heap on the boxes that can afford it.
 *
 * [VegaLoadControl] applies this value as the forward read-ahead cap, exactly
 * like the official client: once the allocator holds the budget and more than
 * five seconds are buffered, loading stops until playback frees the blocks.
 * The five-second floor keeps a high-bitrate film from stopping with an empty
 * buffer, and the cap still bounds memory.
 */
private fun mediaBufferBudgetBytes(ctx: Context): Int {
    val heap = Runtime.getRuntime().maxMemory()
    val info = ActivityManager.MemoryInfo()
    (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.getMemoryInfo(info)
    // totalMem can be 0 on very old APIs; the heap is then the only bound.
    val totalRam = if (info.totalMem > 0) info.totalMem else heap * 8
    val safe = minOf((heap * 0.35).toLong(), totalRam / 32)
    return minOf(maxOf(64L * 1024 * 1024, safe), 256L * 1024 * 1024).toInt()
}

/**
 * Native player twin of the desktop/TV web console.
 *
 * Layout (visible while the UI is up, auto-hidden after [AUTO_HIDE_MS] of
 * stillness):
 *
 *   ┌──────────────────────────────────────────────────────────┐
 *   │ ←  Title / episode                        server · 1080p │  top bar
 *   │                                                          │
 *   │              ⏪ 10        ⏵ / ⏸       10 ⏩                │  center controls
 *   │                                                          │
 *   │   01:12 ────────●───────────────────────── -28:31        │  scrubber
 *   │  Audio CC Subs 1.0x 4K HDR …  Server  Fit  Stats  ◀ Prev  Next ▶ │  action row
 *   └──────────────────────────────────────────────────────────┘
 *
 * Keys (the remote, not the surface, drives playback — see [PlayerKeyBus]):
 *   OK          play/pause — or reveal the UI when it is hidden
 *   ← / →       seek: ±10% on the scrubber, ±10s otherwise, even while the
 *               chrome is hidden (no UI opens) — D-pad movement only between
 *               the other control buttons
 *   ↑ / ↓       spatial navigation / reveal the UI when hidden
 *   MENU        server · quality · audio · subs · speed panel
 *   BACK        closes the panel, cancels auto-next, hides the UI, exits
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onSolveWaf: (String) -> Unit = {},
    vm: PlayerViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    val displayMetrics = ctx.resources.displayMetrics
    val displayWidth = displayMetrics.widthPixels.coerceAtLeast(1)
    val displayHeight = displayMetrics.heightPixels.coerceAtLeast(1)
    // Auto-quality reads this before the (slower) provider resolve completes.
    LaunchedEffect(displayHeight) { vm.maxPlaybackHeight = displayHeight }
    val state by vm.state.collectAsStateWithLifecycle()
    val wafUrl by vm.wafUrl.collectAsStateWithLifecycle()
    val nextError by vm.nextError.collectAsStateWithLifecycle()
    val nextErrorTitle by vm.nextErrorTitle.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    // ── Stage state ──────────────────────────────────────────────────────────
    // The player is the screen, not a reward for a successful resolve. Play
    // opens it at once — title, chrome, MENU — and the source is resolved
    // underneath. A resolve failure then has somewhere to land: the same stage,
    // with its own Retry / Solve / Back, instead of a bare error page that
    // replaces the player before playback ever begins.
    val streams = (state as? UiState.Success)?.data.orEmpty()
    val resolving = state is UiState.Loading
    val resolveError: String? = when (val s = state) {
        is UiState.Error -> s.message
        is UiState.Success ->
            if (s.data.isEmpty()) "The provider returned no playable streams for this title." else null
        else -> null
    }
    val sel = streams.getOrNull(vm.selectedIndex) ?: streams.firstOrNull()
    val hasMedia = sel != null
    // Hindi is the requested default. "auto" clears the preference and lets
    // the source pick; a manual MENU ▸ Audio choice still wins for this stream.
    val audioLanguage by ServiceLocator.settings.audioLanguage
        .collectAsStateWithLifecycle(initialValue = "hi")
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var menuTab by remember { mutableStateOf(0) } // 0 server 1 quality 2 audio 3 subs 4 speed
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var resizeMode by remember {
        mutableStateOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }
    var playError by remember { mutableStateOf<String?>(null) }
    var rawPlayError by remember { mutableStateOf<String?>(null) }
    // Mirrored from the player each tick *and* set on the button press itself,
    // so the play/pause glyph flips on the keypress rather than up to 200ms
    // later when the clock next runs.
    var isPlayingNow by remember { mutableStateOf(true) }
    var speedNow by remember { mutableFloatStateOf(1f) }

    // "Stats for nerds" overlay (INFO key / MENU ▸ Stats).
    var statsOpen by remember { mutableStateOf(false) }
    var statsVideo by remember { mutableStateOf("") }
    var statsAudio by remember { mutableStateOf("") }
    var statsDropped by remember { mutableIntStateOf(0) }
    // Live data-source decision (single vs parallel) for the Stats overlay.
    var statsSource by remember { mutableStateOf("one connection") }
    // The seek-burst accumulator must outlive the key-handler effect: that
    // effect is re-keyed the moment the UI shows, and a job owned by it would
    // be cancelled mid-flight — the seek from the very keypress that revealed
    // the UI would silently vanish.
    val seekCoalescer = remember { SeekCoalescer() }

    // ── UI visibility ────────────────────────────────────────────────────────
    // A wall-clock stamp recomputed on every interaction, read by one loop.
    // The previous implementation keyed a delay off `showUI` transitions, so a
    // single key press left the controls on screen for the rest of the video:
    // the coroutine that would have hidden them had already run to completion.
    var showUI by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun touch() {
        lastInteraction = System.currentTimeMillis()
        showUI = true
    }

    var seekOsd by remember { mutableStateOf<SeekOsd?>(null) }
    var autoNextLeft by remember { mutableStateOf<Int?>(null) }
    var autoNextDismissed by remember { mutableStateOf(false) }
    // True when the stream died at its very last bytes (a truncated final
    // fragment, usually a lying Content-Length header). The picture is over
    // even though the player never reached STATE_ENDED, so the auto-next
    // countdown below must still be allowed to run.
    var tailFailed by remember { mutableStateOf(false) }

    // A failed auto-next must stop the countdown that produced it: otherwise
    // the loop keeps firing every cycle against the same provider error and
    // skips episodes one at a time.
    LaunchedEffect(nextError) {
        if (nextError != null) {
            autoNextDismissed = true
            autoNextLeft = null
        }
    }

    // Auto-hide is a single timer armed while the chrome is actually up, not a
    // 5 Hz poll for the whole movie. Every input that can reset or cancel it
    // (an interaction, a pause, the menu) is a key of this effect, so the
    // countdown restarts by itself and nothing runs while the controls are away.
    LaunchedEffect(
        showUI, menuOpen, playError, autoNextLeft, isPlayingNow, lastInteraction,
        resolving, resolveError, hasMedia,
    ) {
        // Never hide while paused: a frozen frame with no controls reads as
        // a crash, and every TV player keeps its chrome up when paused.
        // Nor while the stage has no source yet, or is showing a resolve
        // failure: that card is the only thing on screen saying what happened,
        // and it must not fade out from under the viewer.
        if (!showUI || menuOpen || playError != null || autoNextLeft != null || !isPlayingNow ||
            resolving || resolveError != null || !hasMedia
        ) {
            return@LaunchedEffect
        }
        val waited = System.currentTimeMillis() - lastInteraction
        delay((AUTO_HIDE_MS - waited).coerceAtLeast(0L))
        showUI = false
        // Focus must leave the (now uncomposed) controls, or the next
        // arrow press is consumed by an invisible focus target.
        focusManager.clearFocus()
    }

    // Where focus sits, so the key handler below knows whether a DPAD press is
    // navigation (let Compose move it) or playback (seek here).
    var controlFocused by remember { mutableStateOf(false) }
    var scrubberFocused by remember { mutableStateOf(false) }

    val trackSelector = remember {
        DefaultTrackSelector(ctx).apply {
            // No cap. Every rendition the provider offers is a real rendition:
            // banding audio on a 1080p stream to 2 channels because a heuristic
            // decided that was "typical" is a quality loss with no upside, and
            // AdaptiveSetSize is still in Media3 precisely because providers
            // hand out track sets that are not uniform.
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                // HLS masters offer every ladder rung, not just the link the
                // provider named. Cap adaptive video at the panel's own size;
                // otherwise ExoPlayer happily pulls a 4K variant on a 1080p TV
                // and spends the whole film dropping frames.
                .setMaxVideoSize(displayWidth, displayHeight)
                .setPreferredAudioLanguages(*preferredAudioCodes(audioLanguage))
                .build()
        }
    }
    LaunchedEffect(audioLanguage) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioLanguages(*preferredAudioCodes(audioLanguage))
            .build()
    }
    // Header-aware HTTP stack: the factory reads the current header snapshot on
    // every request, so per-stream provider auth applies to media + segments.
    //
    // This is OkHttp rather than the platform URLConnection stack because a
    // film makes hundreds of segment/range requests: OkHttp keeps the TLS
    // connection and (for HLS) the HTTP/2 stream alive between them. The
    // platform stack re-handshakes far more often, which is exactly what turns
    // a 1080p HLS stream into periodic 2–4s stalls on a weak TV SoC.
    val mediaHeaders = remember {
        java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap())
    }
    fun mediaFactory(client: okhttp3.OkHttpClient): DataSource.Factory =
        object : DataSource.Factory {
            override fun createDataSource(): DataSource =
                OkHttpDataSource.Factory(client)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0")
                    // Merged, not overwritten: a second setDefaultRequestProperties
                    // call replaces the first, which would silently drop every
                    // provider authorization header the moment an Accept were added.
                    .setDefaultRequestProperties(
                        // Cookies are provider credentials too. Dropping them
                        // makes otherwise valid signed/HLS servers return 403.
                        mediaHeaders.get() + ("Accept" to "*/*"),
                    )
                    .createDataSource()
        }
    val headerHttp = remember { mediaFactory(NetClients.http) }
    // Chunk downloads must be genuinely separate sockets. On the shared
    // HTTP/2 client, "four connections" is four multiplexed streams on one TCP
    // connection, so a server that shapes each connection still starves the
    // film. The HTTP/1.1-only client reuses the same pool, headers, cookies
    // and DoH resolver but negotiates a fresh HTTP/1.1 connection per
    // concurrent range request.
    val rangeChunkHttp = remember {
        mediaFactory(
            NetClients.http.newBuilder()
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .build(),
        )
    }
    // Any progressive video can outrun one TCP connection even when the link
    // has more capacity: a 300 MB episode on a throttled host behaves exactly
    // like an 80 GB 4K remux. This source starts with one request, measures how
    // fast it really delivers, and only then continues over ordered 1->8 MB
    // chunks on up to four HTTP/1.1 sockets. If parallel requests do not
    // measurably help, the URL stays single for the rest of the session. It
    // sits *below* the Matroska wrapper so the cue/header probes above stay
    // single-request; small HLS segments, sidecars and small files pass
    // untouched.
    //
    // The load control publishes the film's own bitrate and every real network
    // stall through this holder, so the source can tell a genuinely starved
    // connection from a healthy one even when the provider declares no
    // bitrate.
    val bufferSignals = remember { BufferSignals() }
    val parallelHttp = remember {
        ParallelRangeDataSource.Factory(headerHttp, rangeChunkHttp, bufferSignals)
    }
    // Matroska files whose Cues are reachable only through a chained SeekHead
    // make Media3 report the stream as unseekable, and every seek then restarts
    // playback at 0:00. This wrapper rewrites that one SeekEntry in flight;
    // HLS/MP4 and every non-Matroska source pass through untouched.
    // `treatAsMatroska` is set from the provider's declared type before prepare.
    val matroskaHttp = remember { MatroskaCueDataSource.Factory(parallelHttp) }
    // Per-stream DRM. Media3 asks this provider once per media item; the map
    // is written immediately before setMediaItem, so a DRM configuration can
    // never leak from one server to the next (or to a clear stream).
    val drmByLink = remember { java.util.concurrent.ConcurrentHashMap<String, tv.vega.tv.data.model.StreamDrm>() }
    val drmProvider = remember {
        DrmSessionManagerProvider { item ->
            drmByLink[item.mediaId]
                ?.createDrmSessionManager(headerHttp)
                ?: DrmSessionManager.DRM_UNSUPPORTED
        }
    }
    // The listener outlives any single composition, so it reaches the UI
    // through this holder rather than through a state it captured. Without the
    // assignment below the listener would fire into a no-op and a 403 would
    // look exactly like a video that never started.
    val showError = remember { mutableStateOf<(String?) -> Unit>({}) }
    LaunchedEffect(Unit) { showError.value = { rawPlayError = it } }
    val player = remember {
        ExoPlayer.Builder(
            ctx,
            // A preferred-decoder init failure falls back to the next decoder
            // instead of failing the stream outright.
            DefaultRenderersFactory(ctx).setEnableDecoderFallback(true),
        )
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(ctx)
                    .setDataSourceFactory(matroskaHttp)
                    // Inline ClearKey / licence-server DRM from newer providers.
                    // A stream with no `drm` object resolves to DRM_UNSUPPORTED
                    // (Media3's no-DRM manager) and is unaffected.
                    .setDrmSessionManagerProvider(drmProvider)
                    // A connection dropped while re-fetching around a seek gets
                    // a few backoff retries before the error card shows.
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(4)),
            )
            .setTrackSelector(trackSelector)
            .setLoadControl(
                // Official-client parity: 8 s minimum, a very long maximum,
                // start at 1.5 s, resume after a rebuffer at 3 s. The byte
                // budget is applied by VegaLoadControl with the same 5 s
                // forward-full rule as the official client, so a high-bitrate
                // film never stops with an empty buffer.
                VegaLoadControl(
                    // 256 KB chunks: at 4K bitrates the 64 KB default churned
                    // thousands of small allocations per minute.
                    DefaultAllocator(true, 256 * 1024),
                    mediaBufferBudgetBytes(ctx).toLong(),
                    bufferSignals,
                ),
            )
            .build()
            .also { p ->
                p.setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                // A TV has no route picker, so a session nobody can hear — box
                // muted at the system level — is indistinguishable from a broken
                // player. Start at full volume; the remote's own volume keys
                // adjust from there.
                p.volume = 1f
                // The screen is kept awake by the activity, but some TV
                // firmwares still put Wi-Fi into power-save between HLS
                // segments. Holding the network wake lock for the duration of
                // playback removes that source of periodic stalls.
                p.setWakeMode(C.WAKE_MODE_NETWORK)
            }
    }
    DisposableEffect(Unit) {
        PlaybackSession.playerActive = true
        onDispose {
            PlaybackSession.playerActive = false
            // Player exit is the end of the exclusive playback window; Home's
            // visibility effect is a second safety net if this screen is
            // removed by a route rebuild.
            PlaybackSession.playbackRequested = false
        }
    }
    // Swap provider-authorized headers per stream.
    fun dataSourceWith(headers: Map<String, String>) {
        mediaHeaders.set(headers)
    }
    // Wall-clock, not a coroutine: the listener below is called on the main
    // thread and must not start work of its own.
    var stalledSince by remember { mutableLongStateOf(0L) }
    // Bumped whenever Media3 publishes a new track list. Language tags are
    // often absent until the manifest is parsed, so the Hindi fallback must
    // re-evaluate then, not only when the stream URL is chosen.
    var tracksRev by remember { mutableIntStateOf(0) }
    // Opening-only auto-failover. It must survive the player's Loading state
    // (server retry) and the switch to Success, so it lives at screen scope,
    // keyed to the episode rather than to one stream.
    var autoFailoverAllowed by remember(PlaybackSession.startIndex) {
        mutableStateOf(true)
    }

    /*
     * Playback failures reach the screen here.
     *
     * `PlayerView` handles its own errors: it shows a brief "exo error" toast
     * and, on an unhandled `PlaybackException`, deliberately steps the player
     * to the next item. With exactly one item in the playlist that means it
     * calls `seekToNext()`, which is a no-op, and the screen sits on a black
     * surface with nothing on it. The listener is what turns a decode error, a
     * 403, or an unplayable container into a sentence the user can read.
     */
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // A progressive stream whose server lies about Content-Length —
                // or a CDN that truncates the final fragment — fails at the
                // last byte with "length=…, bytesRead=-1". The episode has
                // played out; replacing the finished picture with a card the
                // viewer cannot act on is noise. Mark the tail failure so the
                // auto-next countdown still fires, and show nothing.
                val d = player.duration
                if (player.playbackState == androidx.media3.common.Player.STATE_ENDED ||
                    (d > 0 && player.currentPosition >= d - 2_000L)
                ) {
                    tailFailed = true
                    return
                }
                // DRM failures and every other media failure get a sentence a
                // viewer can act on. The raw code and cause stay in logcat.
                Log.w("VegaPlayer", "Playback error ${error.errorCodeName}", error)
                showError.value(playbackErrorMessage(error))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Anything that leaves the player buffering for this long while
                // the user is watching is a stall, not a slow network.
                stalledSince = if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    System.currentTimeMillis()
                } else 0L
                // READY is the point after which auto-failover must stop.
                // Set it directly from the callback rather than polling.
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    autoFailoverAllowed = false
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) return
                // A stream that produced even a second of video has started:
                // from now on only the viewer may change servers.
                autoFailoverAllowed = false
                // Playback owns the box now; tear down the hidden provider
                // WebView until the next provider call rebuilds it lazily.
                ServiceLocator.providers.js.suspendForPlayback()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                tracksRev++
            }


        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // Decoder health for the stats overlay. Formats are polled off the tick
    // clock instead (player.videoFormat), so this listener exists for the one
    // number no getter exposes: cumulative dropped frames.
    DisposableEffect(player) {
        val l = object : AnalyticsListener {
            private var lastFormat = ""

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                statsDropped = droppedFrames
            }

            // The forensic trail for decoder-level failures: when a hardware
            // decoder takes the whole box down on a seek, the TV reboots
            // before any handler runs — but this note was written when the
            // decoder started and survives to the next launch.
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                lastFormat = buildString {
                    append(format.sampleMimeType ?: "?")
                    append(' ').append(format.width).append('x').append(format.height)
                    format.frameRate?.takeIf { it > 0f }?.let { append(" @").append(it.toInt()).append("fps") }
                    format.bitrate?.takeIf { it > 0 }?.let { append(' ').append(it / 1_000_000).append(" Mbps") }
                    if (format.colorInfo != null) append(" hdr")
                }
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializationDurationMs: Long,
            ) {
                noteDecoder(decoderName)
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                noteDecoder(decoderName)
            }

            private fun noteDecoder(decoderName: String) {
                DiagLog.notePlayback(ctx, "decoder=$decoderName\nformat=$lastFormat")
            }
        }
        player.addAnalyticsListener(l)
        onDispose { player.removeAnalyticsListener(l) }
    }

    /*
     * D-pad, handled where the keys actually arrive.
     *
     * A Compose overlay is the only focusable thing on this screen most of the
     * time, and the `PlayerView` underneath gets hardware keys only while it
     * holds Android focus. Wiring OK/LEFT/RIGHT here means the remote works
     * whether the surface, the overlay, or nothing at all has focus.
     *
     * The split matters as much as the handling: while a button holds focus the
     * arrows must move between buttons, not step the play head, so every branch
     * returns false and lets Compose navigate. When the controls are hidden —
     * or the scrubber is the thing focused — the arrows belong to playback.
     */
    /**
     * The honest overlay for a source ExoPlayer cannot seek: a live edge, a
     * stream still preparing, or a file whose index is unusable. Without this
     * a seek on an unseekable item silently restarts playback at 0:00.
     */
    fun showSeekBlocked() {
        // Before the timeline exists there is nothing to seek, but saying
        // "unavailable" while the first frame is still loading would be wrong.
        if (player.playbackState != androidx.media3.common.Player.STATE_READY) return
        seekOsd = SeekOsd(
            direction = 0,
            targetMs = player.currentPosition.coerceAtLeast(0L),
            stepMs = 0,
            blocked = true,
            live = player.isCurrentMediaItemLive,
        )
        lastInteraction = System.currentTimeMillis()
    }

    DisposableEffect(
        player, hasMedia, resolveError, playError, nextError,
        menuOpen, showUI, controlFocused, scrubberFocused,
    ) {
        fun playing() = player.playbackState != androidx.media3.common.Player.STATE_IDLE

        fun seekBy(ms: Long, clampMs: Long = 10 * 60_000L): Boolean {
            if (!playing()) return false
            // ExoPlayer answers a seek on an unseekable item by restarting at
            // 0:00. That is the entire "seek is broken" symptom on index-less
            // MKVs and live edges, so never send the seek at all.
            if (!player.isCurrentMediaItemSeekable) {
                showSeekBlocked()
                return true
            }
            // TV remotes emit repeated key events in bursts. Coalesce them so
            // the decoder performs one seek instead of thrashing on every key.
            // The accumulator lives in `seekCoalescer` (remember-scoped), not
            // in this effect: the effect is re-keyed the moment the UI shows
            // and anything owned by it would be cancelled mid-seek.
            val c = seekCoalescer
            c.pendingMs = (c.pendingMs + ms).coerceIn(-clampMs, clampMs)
            val d = player.duration
            val target = (player.currentPosition + c.pendingMs)
                .coerceIn(0L, if (d > 0) d else Long.MAX_VALUE)
            c.job?.cancel()
            c.job = scope.launch {
                delay(350)
                player.seekTo(target)
                c.pendingMs = 0L
            }
            // The overlay speaks for the seek: the play head itself cannot show
            // a target that has not been reached yet.
            seekOsd = SeekOsd(if (c.pendingMs < 0) -1 else 1, target, c.pendingMs)
            showError.value(null)
            // Seeking is playback, not chrome: bump the auto-hide clock only.
            // Opening the whole control set on every LEFT/RIGHT would cover the
            // picture the viewer is trying to scrub through.
            lastInteraction = System.currentTimeMillis()
            return true
        }

        // The focused scrubber owns LEFT/RIGHT: one press is ±10% of the full
        // duration (accumulated across a burst), the timeline convention of
        // every streaming player. A 10% step of a long film exceeds the 10-
        // minute burst clamp the fixed-step seeks use, hence clampMs = d.
        fun scrubByFraction(fraction: Float): Boolean {
            if (!playing()) return false
            val d = player.duration
            if (d <= 0) return true
            return seekBy((d * fraction).toLong(), clampMs = d)
        }

        val onKey: (Int) -> Boolean = { key ->
            if (resolveError != null || playError != null || nextError != null) {
                // A failure card owns the remote: its Retry / Choose server /
                // Close buttons must receive OK and the D-pad before transport.
                // Without this gate the `else` branch below ate OK as play/pause
                // and the card could only be operated with BACK.
                // MENU does not come through here, so the panel still opens on
                // top of the cards.
                false
            } else when (key) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                -> {
                    when {
                        // The auto-next card owns OK while it is up: it has its
                        // own Play now / Cancel buttons, focused and visible
                        // over the ended picture.
                        autoNextLeft != null -> false
                        !showUI -> {
                            // Hidden: the first press only reveals the UI, so
                            // an accidental OK does not pause what is watched.
                            touch()
                            true
                        }
                        menuOpen -> {
                            // Navigating the panel is interaction too: without
                            // this it could auto-hide under the remote's focus.
                            touch()
                            false
                        }
                        // Nothing has resolved yet: the only things that can
                        // take OK are the exit button and the MENU panel.
                        !hasMedia -> false
                        // The timeline owns OK while focused — the same
                        // play/pause a thumb press used to give, on a target a
                        // remote can actually reach.
                        scrubberFocused -> {
                            if (player.isPlaying) player.pause() else player.play()
                            isPlayingNow = !isPlayingNow
                            touch()
                            true
                        }
                        controlFocused -> {
                            // Navigating the chrome is interaction too: without
                            // this the bar auto-hides under the remote's focus.
                            touch()
                            false
                        }
                        else -> {
                            // Controls up, focus not on one of them (nowhere):
                            // OK stays the universal play/pause.
                            if (player.isPlaying) player.pause() else player.play()
                            isPlayingNow = !isPlayingNow
                            touch()
                            true
                        }
                    }
                }

                // Menu open means focus is on the panel, and the overlay card
                // means focus is on its buttons: LEFT/RIGHT are spatial
                // navigation there, not transport.
                android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                    when {
                        autoNextLeft != null -> false
                        !showUI ->
                            if (hasMedia) seekBy(-SEEK_STEP_MS) || true else { touch(); true }
                        menuOpen -> false
                        scrubberFocused -> scrubByFraction(-0.1f)
                        else -> { touch(); false }
                    }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                    when {
                        autoNextLeft != null -> false
                        !showUI ->
                            if (hasMedia) seekBy(SEEK_STEP_MS) || true else { touch(); true }
                        menuOpen -> false
                        scrubberFocused -> scrubByFraction(0.1f)
                        else -> { touch(); false }
                    }
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    // Any D-pad press shows UI if hidden; the auto-next card
                    // keeps its own spatial navigation.
                    if (autoNextLeft != null) {
                        false
                    } else if (!showUI) {
                        touch()
                        true
                    } else {
                        touch()
                        false // Let normal navigation happen
                    }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                -> {
                    if (!playing()) false
                    else {
                        if (player.isPlaying) player.pause() else player.play()
                        isPlayingNow = !isPlayingNow
                        touch()
                        true
                    }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
                -> {
                    val dir = if (key == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 1L else -1L
                    seekBy(dir * 60_000L)
                }
                // The transport keys on a TV remote. The player screen is the
                // only place that knows what "next" means, so they are handled
                // here rather than left to the system's default no-op.
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (PlaybackSession.startIndex + 1 < PlaybackSession.episodes.size) {
                        vm.next()
                        true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (playing()) {
                        // Standard media convention: past the first few seconds
                        // this restarts the current item; at its very start
                        // (the just-started case) it steps back when there is a
                        // predecessor, and stays a plain restart on the first.
                        if (player.currentPosition > 5_000 ||
                            PlaybackSession.startIndex <= 0 ||
                            PlaybackSession.episodes.isEmpty()
                        ) {
                            player.seekTo(0)
                        } else {
                            vm.prev()
                        }
                        touch()
                        true
                    } else false
                }
                android.view.KeyEvent.KEYCODE_MEDIA_STOP -> {
                    onBack()
                    true
                }
                // INFO toggles the stats overlay, VLC's info pane.
                android.view.KeyEvent.KEYCODE_INFO -> {
                    statsOpen = !statsOpen
                    true
                }
                else -> false
            }
        }
        PlayerKeyBus.onKey = onKey
        onDispose {
            // Deliberately NOT cancelling a queued seek here: this effect
            // re-keys when the UI appears, and the seek started by the very
            // keypress that revealed the UI must still land.
            if (PlayerKeyBus.onKey === onKey) PlayerKeyBus.onKey = null
        }
    }

    DisposableEffect(resolveError, resolving) {
        PlayerKeyBus.onBack = {
            when {
                playError != null || nextError != null || resolveError != null -> {
                    // The failure card's own Back leaves the player; the remote's
                    // BACK must do the same instead of only hiding the chrome.
                    onBack()
                    true
                }
                menuOpen -> {
                    menuOpen = false
                    touch()
                    true
                }
                // Nothing has resolved yet: there is no picture to protect and
                // no control set worth hiding, so BACK is a plain cancel.
                resolving -> {
                    onBack()
                    true
                }
                autoNextLeft != null -> {
                    autoNextDismissed = true
                    autoNextLeft = null
                    true
                }
                showUI -> {
                    // First BACK puts the controls away; a second one exits.
                    // Leaving playback on the first press made BACK feel like
                    // the chrome was blocking the way out.
                    showUI = false
                    focusManager.clearFocus()
                    true
                }
                else -> {
                    // Chrome already hidden: BACK means leave playback.
                    onBack()
                    true
                }
            }
        }
        PlayerKeyBus.onMenu = {
            if (menuOpen) {
                menuOpen = false
                touch()
            } else {
                menuTab = 0
                menuOpen = true
                touch()
            }
        }
        onDispose {
            PlayerKeyBus.onBack = null
            PlayerKeyBus.onMenu = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Final progress flush (same 5s rule as periodic save). A local file
            // has no episode and must not resolve the write against whatever the
            // last streamed show happened to leave in `pendingLink`.
            val pos = player.currentPosition
            val dur = player.duration.coerceAtLeast(0)
            if (pos > 5000 && dur > 0) saveProgressSync(pos, dur)
            seekCoalescer.job?.cancel()
            runCatching { player.release() }
            PlayerKeyBus.onBack = null
            PlayerKeyBus.onMenu = null
        }
    }

    // The seek overlay expires on its own; a coroutine keyed to the *current*
    // overlay means a seek fired mid-expiry still clears exactly once.
    LaunchedEffect(seekOsd) {
        if (seekOsd != null) {
            delay(1100)
            if (seekOsd != null) seekOsd = null
        }
    }

    // One clock for the chrome: position, duration, buffered amount, and the
    // 10s progress save. 200ms keeps the scrubber from visibly stepping while
    // staying far below one frame of a remote's repeat rate. While nothing on
    // screen reads the values — chrome and stats both hidden — the state writes
    // (and the stats rebuild behind them) are skipped, so a full-screen movie
    // costs the decoder, not the UI thread; the resume checkpoint still ticks.
    LaunchedEffect(Unit) {
        var lastSave = 0L
        while (isActive) {
            delay(UI_TICK_MS)
            if (showUI || menuOpen || statsOpen) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                val d = player.duration
                if (d > 0) durationMs = d
                bufferedMs = player.bufferedPosition
                speedNow = player.playbackParameters.speed
                // Stats overlay, only rebuilt into state when the text actually
                // changes, so a 60 fps decode does not recompose the chrome.
                val vf = player.videoFormat
                val vLine = vf?.let { f ->
                    buildString {
                        if (f.width > 0 && f.height > 0) append("${f.width}×${f.height}")
                        if (f.frameRate > 0f) {
                            if (isNotEmpty()) append(" · ")
                            append("${f.frameRate.toInt()} fps")
                        }
                        f.sampleMimeType?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it.substringAfter('/'))
                        }
                        if (f.bitrate > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${f.bitrate / 1000} kbps")
                        }
                    }
                }.orEmpty()
                if (vLine != statsVideo) statsVideo = vLine
                val af = player.audioFormat
                val aLine = af?.let { f ->
                    buildString {
                        f.sampleMimeType?.let { append(it.substringAfter('/')) }
                        if (f.channelCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${f.channelCount}ch")
                        }
                        if (f.sampleRate > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${f.sampleRate / 1000} kHz")
                        }
                        if (f.bitrate > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${f.bitrate / 1000} kbps")
                        }
                    }
                }.orEmpty()
                if (aLine != statsAudio) statsAudio = aLine
                val src = tv.vega.tv.player.ParallelRangeDataSource.diagnostics()
                if (src != statsSource) statsSource = src
            }
            isPlayingNow = player.isPlaying
            // Continue Watching can enter with no rebuilt episode list; the
            // pending link is still a streamed title, so its checkpoint must
            // keep ticking. (The final onDispose flush alone lost hours of
            // progress if the app was killed mid-film.)
            val streaming = PlaybackSession.episodes.isNotEmpty() ||
                PlaybackSession.pendingLink.isNotBlank()
            val pos = player.currentPosition
            val d = player.duration
            if (streaming && pos - lastSave > 10_000 && d > 0) {
                lastSave = pos
                saveProgress(pos, d)
            }
        }
    }

    // Selecting the server that is already playing (common after the failure
    // card sends the viewer to the list) must reload it too. Changing
    // `selectedIndex` back to the same value would otherwise recompose to an
    // identical link and do nothing at all.
    var mediaRev by remember { mutableIntStateOf(0) }

    // Many Indian providers leave the audio track language as "und"
    // and only label it "Hindi". Preferred-language matching cannot
    // see that, so after the first real track list arrives, pick the
    // Hindi-labelled track explicitly. Runs once per stream; a later
    // manual MENU -> Audio choice is never overridden.
    var audioAppliedFor by remember { mutableStateOf("") }
    LaunchedEffect(sel?.link, tracksRev, audioLanguage) {
        val cur = sel ?: return@LaunchedEffect
        if (audioAppliedFor == cur.link) return@LaunchedEffect
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIsPreferred = groups.any { g ->
            g.isSelected && (0 until g.length).any {
                audioTrackMatches(g.getTrackFormat(it), audioLanguage)
            }
        }
        val preferred = groups.firstNotNullOfOrNull { g ->
            (0 until g.length).firstOrNull { i ->
                audioTrackMatches(g.getTrackFormat(i), audioLanguage)
            }?.let { i -> g to i }
        }
        audioAppliedFor = cur.link
        var params = trackSelector.buildUponParameters()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (!selectedIsPreferred && preferred != null) {
            params = params.setOverrideForType(
                TrackSelectionOverride(preferred.first.mediaTrackGroup, preferred.second),
            )
        }
        trackSelector.parameters = params.build()
    }

    // Automatic server failover is an opening-only convenience. It is
    // allowed while the chosen stream has never reached READY; once
    // playback has actually started (or the viewer picked a server),
    // an error must show the card instead of silently jumping to
    // another server. That prevents a slow/buffering server from being
    // abandoned mid-film without the viewer's consent.
    var failedLinks by remember(PlaybackSession.startIndex) {
        mutableStateOf(emptySet<String>())
    }
    var failoverLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(rawPlayError, sel?.link) {
        val reason = rawPlayError ?: return@LaunchedEffect
        val cur = sel ?: return@LaunchedEffect
        // The media id, not `sel`, is what actually failed: an error
        // can arrive in the same frame as a failover recomposition.
        val currentLink = player.currentMediaItem?.mediaId
            ?.takeIf { it.isNotBlank() }
            ?: cur.link
        val failed = failedLinks + currentLink
        failedLinks = failed
        if (!autoFailoverAllowed) {
            // Playback had already started (or the viewer chose this
            // server): stop here and make the choice explicit.
            failoverLabel = null
            playError = reason
            showUI = true
            touch()
            return@LaunchedEffect
        }
        val currentIndex = streams.indexOfFirst { it.link == currentLink }
            .takeIf { it >= 0 } ?: vm.selectedIndex
        // Never retry the same URL automatically: the player already
        // exhausted its internal retries before reporting the error.
        val next = streams.indices
            .map { (currentIndex + it + 1) % streams.size }
            .firstOrNull { i ->
                val candidate = streams[i]
                candidate.link.isNotBlank() &&
                    candidate.link != currentLink &&
                    candidate.link !in failed &&
                    candidate.drmUnsupportedReason() == null
            }
        if (next == null) {
            failoverLabel = null
            playError = reason
            showUI = true
            touch()
        } else {
            // A failed ExoPlayer source is already IDLE, which is the
            // one case where the media effect uses the session resume
            // value instead of the live position. Capture it first.
            val pos = player.currentPosition
            if (pos > 0) PlaybackSession.resumePositionMs = pos
            failoverLabel = "Trying another server…"
            rawPlayError = null
            vm.selectFailover(next)
            touch()
        }
    }

    LaunchedEffect(sel?.link) {
        while (failoverLabel != null &&
            player.playbackState != androidx.media3.common.Player.STATE_READY &&
            player.playbackState != androidx.media3.common.Player.STATE_ENDED &&
            playError == null
        ) {
            delay(200)
        }
        failoverLabel = null
    }

    // Remember which labelled server/quality is actually on screen.
    // History stores the label pair rather than the URL because links
    // rotate between resolves; the next resume uses it to start on the
    // same server instead of silently jumping to the best one.
    LaunchedEffect(sel?.link) {
        val cur = sel ?: return@LaunchedEffect
        PlaybackSession.playingStreamKey = cur.identityKey()
    }

    // A reloaded source that keeps playing earns every server back:
    // a transient CDN error hours into a film must not permanently
    // poison the list for the rest of the episode.
    LaunchedEffect(sel?.link, isPlayingNow) {
        val cur = sel ?: return@LaunchedEffect
        if (!isPlayingNow) return@LaunchedEffect
        delay(60_000)
        if (player.currentMediaItem?.mediaId == cur.link) failedLinks = emptySet()
    }

    // External-player setting (mirrors web useExternalPlayer).
    LaunchedEffect(sel?.link) {
        val cur = sel ?: return@LaunchedEffect
        val ext = ServiceLocator.settings.useExternalPlayer.first()
        // An external player receives only a URL: inline ClearKey and licence
        // endpoints cannot travel with it, so DRM streams stay in the app.
        if (ext && cur.link.isNotBlank() && cur.drm == null) {
            openExternal(ctx, cur)
            onBack()
        }
    }

    // Rebuild key bumps when external subs are added (position kept).
    var subsRev by remember(sel?.link) { mutableStateOf(PlaybackSession.subsRevision) }
    // Two provider servers can legally expose the same URL with
    // different cookies/referer/token headers. Key on headers too, or
    // switching servers appears to do nothing and keeps the old auth.
    LaunchedEffect(sel?.link, sel?.headers, subsRev, mediaRev) {
        val cur = sel ?: return@LaunchedEffect
        if (cur.link.isBlank()) return@LaunchedEffect
        // Never hand an unplayable DRM configuration to ExoPlayer: a raw
        // ERROR_CODE_DRM_SCHEME_UNSUPPORTED card is what made DRM-only live
        // channels look like a player crash. The card above offers the server
        // list, and a clear mirror (if any) is one press away.
        cur.drmUnsupportedReason()?.let { reason ->
            PlaybackSession.resumePositionMs = 0L
            playError = reason
            showUI = true
            touch()
            return@LaunchedEffect
        }
        runCatching {
            dataSourceWith(cur.headers)
            // The wrapper probes only what the provider declares as Matroska
            // (or a URL that plainly names an .mkv). HLS and MP4 keep the
            // direct, zero-overhead data path.
            matroskaHttp.treatAsMatroska = cur.type.contains("mkv", ignoreCase = true)
            drmByLink.clear()
            cur.drm?.let { drmByLink[cur.link] = it }
            val keep = if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                PlaybackSession.resumePositionMs.also { PlaybackSession.resumePositionMs = 0L }
            } else player.currentPosition
            val subs = cur.subtitles.mapNotNull { t ->
                if (t.uri.isBlank()) null else MediaItem.SubtitleConfiguration.Builder(t.uri.toUri())
                    .setMimeType(mimeOf(t.uri, t.type))
                    .setLanguage(t.language.ifBlank { "und" })
                    .setLabel(t.title.ifBlank { t.language })
                    .build()
            } + PlaybackSession.externalSubs.map { e ->
                MediaItem.SubtitleConfiguration.Builder(e.uri.toUri())
                    .setMimeType(e.mime)
                    .setLanguage(e.lang)
                    .setLabel(e.label.ifBlank { e.lang })
                    .build()
            }
            val item = MediaItem.Builder().setUri(cur.link)
                // Lets auto-next prove the ENDED it sees belongs to the
                // link that is still loaded, not to the player that is
                // being replaced.
                .setMediaId(cur.link)
                .setSubtitleConfigurations(subs).build()
            player.setMediaItem(item)
            player.prepare()
            // The stream is selected: tear down the provider WebView
            // now rather than waiting for the first isPlaying tick, so
            // its JS/network work cannot compete with the first
            // seconds of decoding.
            ServiceLocator.providers.js.suspendForPlayback()
            if (keep > 5000) player.seekTo(keep)
            player.play()
        }
    }

    // A stall past the point where the user has watch a spinner of TV
    // static is a slow server, and saying so is the only way they learn
    // there is another one to try.
    var bufferingFor by remember(sel?.link) { mutableLongStateOf(0L) }
    LaunchedEffect(stalledSince, sel?.link) {
        bufferingFor = if (stalledSince == 0L) 0L
        else {
            while (stalledSince != 0L) {
                delay(1000)
                bufferingFor = (System.currentTimeMillis() - stalledSince) / 1000
            }
            0L
        }
    }
    // Do not reveal the chrome when a buffer crosses the slow-server
    // threshold: it used to pop up just as the stall ended (the tick
    // that noticed 6s often lands after READY), covering the resumed
    // picture. The spinner and the hint below stay visible on their
    // own, and MENU still opens the server panel directly.
    val skips = remember(sel) { sel?.let { collectSkips(it) } ?: emptyList() }
    val hasNext = PlaybackSession.startIndex + 1 < PlaybackSession.episodes.size
    val hasPrev = PlaybackSession.startIndex > 0

    PlayerChrome(
        player = player,
        trackSelector = trackSelector,
        playError = playError,
        nextError = nextError,
        nextErrorTitle = nextErrorTitle,
        switching = switching,
        fallbackLabel = failoverLabel,
        onRetryNext = { vm.retryAdvance() },
        onDismissNextError = vm::dismissNextError,
        streams = streams,
        selectedIndex = vm.selectedIndex,
        onServerSelect = {
            vm.selectedIndex = it
            mediaRev++
            playError = null
            rawPlayError = null
            failoverLabel = null
            failedLinks = emptySet()
            // A deliberate choice is not an "initial open" anymore.
            autoFailoverAllowed = false
            touch()
        },
        onQualityLink = {
            playError = null
            rawPlayError = null
            failoverLabel = null
            failedLinks = emptySet()
            autoFailoverAllowed = false
            vm.switchLink(it)
        },
        title = PlaybackSession.title.ifBlank { PlaybackSession.pendingTitle },
        // A movie has one item, and its title is a rendition label
        // ("4K HDR …"): "Episode 1 · …" would be nonsense. A direct
        // Continue Watching launch has no meta object, so fall back to
        // the saved type and the saved episode label.
        episodeLabel = if (
            PlaybackSession.info?.let { isMovieInfo(it) } == true ||
            (PlaybackSession.info == null &&
                PlaybackSession.pendingType.equals("movie", true))
        ) {
            ""
        } else {
            PlaybackSession.episodes
                .getOrNull(PlaybackSession.startIndex)
                ?.let { episodeSubtitle(it) }
                ?: PlaybackSession.pendingEpisodeTitle
        },
        serverLabel = sel?.let { streamLabel(it) }.orEmpty(),
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedMs = bufferedMs,
        skips = skips,
        isBuffering = stalledSince != 0L,
        bufferingSlow = bufferingFor > 6,
        isPlaying = isPlayingNow,
        speed = speedNow,
        statsOpen = statsOpen,
        statsVideo = statsVideo,
        statsAudio = statsAudio,
        statsDropped = statsDropped,
        statsSource = statsSource,
        onToggleStats = { statsOpen = !statsOpen; touch() },
        hasNext = hasNext,
        hasPrev = hasPrev,
        autoNextLeft = autoNextLeft,
        autoNextTitle = PlaybackSession.episodes
            .getOrNull(PlaybackSession.startIndex + 1)
            ?.let { episodeSubtitle(it) }
            .orEmpty(),
        onPlayPause = {
            if (player.isPlaying) player.pause() else player.play()
            isPlayingNow = !isPlayingNow
            touch()
        },
        onSpeedChange = {
            player.setPlaybackSpeed(it)
            touch()
        },
        onSeekBy = { ms ->
            if (player.isCurrentMediaItemSeekable) {
                val d = player.duration
                val target = (player.currentPosition + ms)
                    .coerceIn(0L, if (d > 0) d else Long.MAX_VALUE)
                player.seekTo(target)
                seekOsd = SeekOsd(if (ms < 0) -1 else 1, target, ms)
                touch()
            } else {
                showSeekBlocked()
                touch()
            }
        },
        onSeekOsd = seekOsd,
        onNext = { vm.next(); touch() },
        onPrev = { vm.prev(); touch() },
        onCancelAutoNext = {
            autoNextDismissed = true
            autoNextLeft = null
        },
        onExit = onBack,
        resizeMode = resizeMode,
        onResizeToggle = {
            resizeMode = when (resizeMode) {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL ->
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            touch()
        },
        onRetryError = {
            playError = null
            rawPlayError = null
            failoverLabel = null
            failedLinks = emptySet()
            autoFailoverAllowed = false
            // Re-resolve the source: signed URLs and challenge
            // cookies commonly expire, so preparing the same URL
            // would repeat a 403 forever.
            vm.load()
        },
        onSwitchServer = {
            playError = null
            rawPlayError = null
            failoverLabel = null
            menuTab = 0
            menuOpen = true
            showUI = true
            lastInteraction = System.currentTimeMillis()
        },
        onSubAdded = { subsRev++; touch() },
        menuOpen = menuOpen,
        menuTab = menuTab,
        onMenuTab = { menuTab = it; touch() },
        onMenuOpenChange = { open ->
            menuOpen = open
            touch()
        },
        showUI = showUI,
        onTouch = { touch() },
        onControlFocus = { controlFocused = it },
        onScrubberFocus = { scrubberFocused = it },
        // The stage reports its own resolve state: the chrome draws the
        // "starting" pill or the failure card in place, over the surface,
        // instead of the screen being swapped out underneath it.
        resolving = resolving,
        resolveError = resolveError,
        wafUrl = wafUrl,
        onRetryResolve = vm::load,
        onSolveWaf = onSolveWaf,
        hasMedia = hasMedia,
        modifier = Modifier.fillMaxSize(),
    )

    // Auto-next: only when the episode really is finishing. Dismissal is
    // per-episode, so a rewind after "Next episode now" does not restart
    // the countdown the user just said no to.
    LaunchedEffect(sel?.link) {
        autoNextDismissed = false
        tailFailed = false
    }
    LaunchedEffect(hasNext, autoNextDismissed, menuOpen, switching, sel?.link) {
        val cur = sel ?: return@LaunchedEffect
        // While the next episode resolves, the outgoing player is still
        // ENDED. Counting against it queued a second countdown (for the
        // episode after next) that vanished when the real jump landed:
        // the "episode 2 … then episode 3 … then episode 2 plays" box.
        if (switching != null) {
            autoNextLeft = null
            return@LaunchedEffect
        }
        while (isActive) {
            delay(1000)
            val d = player.duration
            // `playWhenReady` rather than `isPlaying`: a video that has
            // just reached its end reports isPlaying == false, and that
            // is exactly the moment the next episode is wanted. A tail
            // failure skips the liveness check, because the error that
            // ended it has already put the player back into IDLE.
            val live = player.playWhenReady &&
                player.playbackState != androidx.media3.common.Player.STATE_IDLE
            // Only the real end of the stream starts the countdown.
            // Treating the last few percent as "finishing" cut the
            // final minute of every episode — the credits, in a show,
            // but also the payoff of a cliffhanger.
            // The ENDED state must also belong to the selected link:
            // between "next" and the new media being set, the outgoing
            // player still reports the old episode's end.
            val loaded = player.currentMediaItem?.mediaId == cur.link
            val ended = loaded && (
                tailFailed ||
                    player.playbackState == androidx.media3.common.Player.STATE_ENDED ||
                    player.currentPosition >= d - 1_500
                )
            val finishing = hasNext && !autoNextDismissed && !menuOpen && d > 0 &&
                (live || tailFailed) && ended
            if (finishing) {
                val left = autoNextLeft
                if (left == null) autoNextLeft = AUTO_NEXT_SECONDS
                else if (left <= 1) {
                    autoNextLeft = null
                    vm.next()
                } else autoNextLeft = left - 1
            } else if (autoNextLeft != null) {
                autoNextLeft = null
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chrome
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Everything drawn on top of the video.
 *
 * The surface itself is always full-bleed: [PlayerView] is MATCH_PARENT with
 * the resize mode applied, so a 4:3 source letterboxes inside the *widget*
 * rather than inside a Compose box that would also black-bar the overlays.
 */
@Composable
private fun PlayerChrome(
    player: ExoPlayer,
    trackSelector: DefaultTrackSelector,
    playError: String?,
    nextError: String?,
    nextErrorTitle: String,
    switching: String?,
    fallbackLabel: String?,
    /** First resolve in flight: the stage is up, the source is not. */
    resolving: Boolean,
    /** Why the provider could not produce a source, if it could not. */
    resolveError: String?,
    wafUrl: String?,
    onRetryResolve: () -> Unit,
    onSolveWaf: (String) -> Unit,
    /** True once a stream is selected; before that there is nothing to drive. */
    hasMedia: Boolean,
    onRetryNext: () -> Unit,
    onDismissNextError: () -> Unit,
    streams: List<VegaStream>,
    selectedIndex: Int,
    onServerSelect: (Int) -> Unit,
    onQualityLink: (String) -> Unit,
    title: String,
    episodeLabel: String,
    serverLabel: String,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    skips: List<Skip>,
    isBuffering: Boolean,
    bufferingSlow: Boolean,
    isPlaying: Boolean,
    speed: Float,
    statsOpen: Boolean,
    statsVideo: String,
    statsAudio: String,
    statsDropped: Int,
    statsSource: String,
    onToggleStats: () -> Unit,
    hasNext: Boolean,
    hasPrev: Boolean,
    autoNextLeft: Int?,
    autoNextTitle: String,
    onPlayPause: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekOsd: SeekOsd?,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onCancelAutoNext: () -> Unit,
    onExit: () -> Unit,
    resizeMode: Int,
    onResizeToggle: () -> Unit,
    onRetryError: () -> Unit,
    onSwitchServer: () -> Unit,
    onSubAdded: () -> Unit,
    menuOpen: Boolean,
    menuTab: Int,
    onMenuTab: (Int) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    showUI: Boolean,
    onTouch: () -> Unit,
    onControlFocus: (Boolean) -> Unit,
    onScrubberFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val subStyle by ServiceLocator.settings.subtitleStyle
        .collectAsStateWithLifecycle(initialValue = tv.vega.tv.data.settings.SubtitleStyle())
    // The frame-rate cap setting, actually applied to the surface below.
    val maxFps by ServiceLocator.settings.maxFps
        .collectAsStateWithLifecycle(initialValue = "auto")
    // A Continue Watching resume rebuilds no episode list, so the session's
    // current link lives in `pendingLink` instead. Without this fallback the
    // chip read "Auto" and the Quality tab ticked nothing, even though the
    // saved server and rendition were playing correctly.
    val currentEpLink = PlaybackSession.episodes
        .getOrNull(PlaybackSession.startIndex)?.link
        ?.takeIf { it.isNotBlank() }
        ?: PlaybackSession.pendingLink
    // Distinct qualities the provider offered, best first, each mapped to its
    // best stream: the quality dropdown is the server list minus server names.
    // Movie quality cards are whole alternate links and are appended.
    val choices = remember(streams) { qualityChoices(streams) }
    val currentQuality = PlaybackSession.qualityAlternates
        .firstOrNull { it.link == currentEpLink }?.label
        ?: (streams.getOrNull(selectedIndex)?.quality ?: "").trim().ifBlank { "Auto" }

    // How many control regions (top bar, center row, action row) hold focus.
    // A counter, not a boolean: while focus moves between regions, one fires
    // "lost" and another fires "gained", and only the running total is stable.
    var controlFocusCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(showUI, menuOpen, switching, hasMedia) {
        if (showUI && !menuOpen && switching == null && hasMedia) {
            // Also restores focus after a jump: the chrome is withdrawn while
            // the next source resolves, taking the focused Next/Prev button
            // with it.
            runCatching { playFocus.requestFocus() }
        } else if (!showUI || menuOpen || !hasMedia) {
            // Controls leaving the screen must not leave a phantom focus count.
            controlFocusCount = 0
            onControlFocus(false)
            onScrubberFocus(false)
        }
    }
    LaunchedEffect(controlFocusCount) { onControlFocus(controlFocusCount > 0) }

    fun trackControlFocus(focused: Boolean) {
        if (focused) controlFocusCount++ else if (controlFocusCount > 0) controlFocusCount--
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    // A quality/server switch must not flash a black surface
                    // while the new manifest resolves; the last frame stays up
                    // under the loading pill until the first new frame arrives.
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = {
                it.resizeMode = resizeMode
                it.useController = false
                // AFR: hand the display the requested frame rate (API 30+).
                runCatching { applyFrameRateCap(it, maxFps) }
                runCatching { applySubtitleStyle(it, subStyle) }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (playError != null) {
            PlaybackError(
                title = "Playback stopped",
                message = playError,
                retryLabel = "Retry this server",
                serverLabel = "Choose another server",
                onRetry = onRetryError,
                onServer = onSwitchServer,
                onClose = onExit,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        // Auto-next resolve failure: the finished episode stays up behind this
        // card, so the viewer can retry, pick another episode, or keep the
        // frame they already watched — never a dead-end error screen.
        if (nextError != null) {
            PlaybackError(
                title = nextErrorTitle,
                message = nextError,
                retryLabel = "Try again",
                onRetry = onRetryNext,
                serverLabel = "Choose episode",
                onServer = onExit,
                closeLabel = "Close",
                onClose = onDismissNextError,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        // Resolve failure: the card sits on the stage that is already up, with
        // the only actions that can help (retry the provider, or solve a
        // challenge) plus the way out. It deliberately does not `return@Box`:
        // MENU still opens the panel on top, so a failure is never a dead end.
        if (resolveError != null) {
            PlaybackError(
                title = "Couldn't start this title",
                message = resolveError,
                retryLabel = "Try again",
                serverLabel = if (wafUrl != null) "Solve challenge" else null,
                onRetry = onRetryResolve,
                onServer = { wafUrl?.let(onSolveWaf) },
                onClose = onExit,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Scrims only while the chrome is up — a permanent gradient dims the
        // picture for no reason once it auto-hides.
        if (showUI && !menuOpen) {
            Box(
                Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)),
                    ),
            )
            Box(
                Modifier.fillMaxWidth().height(210.dp).align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))),
                    ),
            )
        }

        // Buffering: a spinner is only honest for the first seconds; after that
        // the honest label is the stall, because a TV that buffers for a minute
        // while showing a spinner looks identical to one that is working.
        if (isBuffering && switching == null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VegaSpinner()
                if (bufferingSlow) {
                    // Keep the choice visible even after the chrome auto-hides:
                    // a stalled server must never look like an idle spinner.
                    Text(
                        "This server is slow — press MENU ▸ Server to choose another",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xCCFFFFFF),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        // First resolve: the stage is up, the source is not yet. Outside the
        // `showUI` gate on purpose — a slow provider must never leave a black
        // screen that reads as a hang, and the hint is the only thing saying
        // the remote still does something.
        if (resolving && switching == null && resolveError == null) {
            Column(
                Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xB314100F))
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VegaSpinner()
                Text(
                    "Starting stream…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "MENU for settings · BACK to cancel",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xB3FFFFFF),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // Immediate acknowledgement of Prev/Next/quality: resolving the next
        // source can take seconds, and the old behaviour left the finished
        // frame (or the old stream) on screen with no sign anything happened.
        // This stays up through auto-hide; it is the only feedback until the
        // new media is ready.
        val busyLabel = switching ?: fallbackLabel
        if (busyLabel != null) {
            Column(
                Modifier.align(Alignment.Center)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xE614100F))
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VegaSpinner()
                Text(
                    busyLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }

        // Skip stays reachable while the chrome is hidden, so it carries its
        // own coarse clock — the chrome tick is parked when the UI is away.
        if (!menuOpen && skips.isNotEmpty()) {
            SkipChip(
                player = player,
                skips = skips,
                showUI = showUI,
                onTouch = onTouch,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        // Stats stays up through auto-hide: it is something you turned on *to*
        // watch, like VLC's info pane, not chrome to be hidden after 5s.
        if (statsOpen) {
            StatsOverlay(
                video = statsVideo,
                audio = statsAudio,
                dropped = statsDropped,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                speed = speed,
                resizeMode = resizeMode,
                serverLabel = serverLabel,
                source = statsSource,
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 28.dp, bottom = if (showUI) 150.dp else 24.dp),
            )
        }

        if (autoNextLeft != null && !menuOpen && switching == null) {
            AutoNextOverlay(
                secondsLeft = autoNextLeft,
                title = autoNextTitle.ifBlank { "Next episode" },
                onPlayNow = { onNext() },
                onCancel = onCancelAutoNext,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (showUI && !menuOpen && autoNextLeft == null && switching == null) {
            PlayerTopBar(
                onExit = onExit,
                title = title,
                episodeLabel = episodeLabel,
                serverLabel = serverLabel,
                modifier = Modifier.align(Alignment.TopCenter)
                    .onFocusEvent { trackControlFocus(it.hasFocus) },
            )
            // Transport is meaningless before a source exists: showing a pause
            // glyph over an empty stage would claim playback that is not there.
            if (hasMedia) {
                PlayerCenterControls(
                    isPlaying = isPlaying,
                    onPlayPause = onPlayPause,
                    onSeekBy = onSeekBy,
                    playFocus = playFocus,
                    modifier = Modifier.align(Alignment.Center)
                        .onFocusEvent { trackControlFocus(it.hasFocus) },
                )
                PlayerBottomControls(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    skips = skips,
                    speed = speed,
                    qualityLabel = currentQuality,
                    hasQualities = choices.size >= 2,
                    hasNext = hasNext,
                    hasPrev = hasPrev,
                    onPlayPause = onPlayPause,
                    onOpenMenu = { tab -> if (tab >= 0) onMenuTab(tab); onMenuOpenChange(true) },
                    resizeLabel = resizeLabel(resizeMode),
                    onResizeToggle = onResizeToggle,
                    statsOpen = statsOpen,
                    onToggleStats = onToggleStats,
                    onNext = onNext,
                    onPrev = onPrev,
                    onScrubberFocus = onScrubberFocus,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .onFocusEvent { trackControlFocus(it.hasFocus) },
                )
            }
        }

        if (menuOpen) {
            MenuPanel(
                streams = streams,
                selected = selectedIndex,
                tab = menuTab,
                onTab = onMenuTab,
                onServer = onServerSelect,
                choices = choices,
                playingLink = currentEpLink,
                onQualityLink = onQualityLink,
                player = player,
                trackSelector = trackSelector,
                onSpeed = onSpeedChange,
                speed = speed,
                statsOpen = statsOpen,
                onToggleStats = onToggleStats,
                resizeMode = resizeMode,
                onResize = onResizeToggle,
                onNext = onNext,
                hasNext = hasNext,
                onPrev = onPrev,
                hasPrev = hasPrev,
                onSubAdded = onSubAdded,
                onClose = { onMenuOpenChange(false) },
                onExit = onExit,
                firstTabFocus = menuFocus,
                emptyHint = if (resolveError != null) {
                    "The provider returned no sources — close this panel and try again."
                } else {
                    "No sources yet — the stream is still resolving."
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp),
            )
        }

        // The seek overlay speaks for a jump the play head has not made yet.
        onSeekOsd?.let { osd ->
            SeekOsdBadge(osd = osd, modifier = Modifier.align(Alignment.Center).padding(top = 40.dp))
        }
    }
}

/**
 * "Skip intro" chip. Unlike the rest of the chrome it stays on screen while
 * the controls are hidden, so it polls its own position instead of riding the
 * chrome clock. The poll is fast enough that the chip appears the moment an
 * interval starts, but state changes only when the active interval changes, so
 * a four-times-a-second clock does not recompose anything.
 */
@Composable
private fun SkipChip(
    player: ExoPlayer,
    skips: List<Skip>,
    showUI: Boolean,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var active by remember(skips) { mutableStateOf<Skip?>(null) }
    LaunchedEffect(skips) {
        while (true) {
            val posMs = player.currentPosition.coerceAtLeast(0L)
            val next = skips.firstOrNull { posMs >= it.fromMs && posMs < it.toMs }
            if (next != active) active = next
            delay(250)
        }
    }
    val skip = active ?: return
    Button(
        onClick = {
            player.seekTo(skip.toMs)
            // Hide on the press itself; waiting for the next position tick
            // leaves a button that has already done its job on screen.
            active = null
            onTouch()
        },
        modifier = modifier.padding(end = 28.dp, bottom = if (showUI) 150.dp else 28.dp),
    ) { Text(skipLabel(skip.title)) }
}

/**
 * Official label rule: a provider that already sends "Skip Intro" must not
 * become "Skip Skip Intro", while a bare "Outro" becomes "Skip Outro".
 */
private fun skipLabel(title: String): String {
    val text = title.trim()
    return when {
        text.isEmpty() -> "Skip Intro"
        text.startsWith("skip", ignoreCase = true) -> text
        else -> "Skip $text"
    }
}

/** Top row: exit, what is playing, and which server is carrying it. */
@Composable
private fun PlayerTopBar(
    onExit: () -> Unit,
    title: String,
    episodeLabel: String,
    serverLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            onClick = onExit,
            containerColor = Color(0xCC14100F),
            contentColor = Color.White,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit player")
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { "Vega" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episodeLabel.isNotBlank()) {
                Text(
                    episodeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xB3FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (serverLabel.isNotBlank()) {
            Spacer(Modifier.width(16.dp))
            PlayerTag(serverLabel)
        }
    }
}

/** Center row: ±10s and play/pause — the three buttons a remote user wants. */
@Composable
private fun PlayerCenterControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    playFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerRoundButton(onClick = { onSeekBy(-SEEK_STEP_MS) }, size = 56.dp) {
            Icon(
                Icons.Filled.Replay10,
                contentDescription = "Rewind 10 seconds",
                modifier = Modifier.size(28.dp),
            )
        }
        PlayerRoundButton(
            onClick = onPlayPause,
            size = 76.dp,
            containerColor = Color.White,
            contentColor = Color(0xFF14100F),
            focusRequester = playFocus,
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(40.dp),
            )
        }
        PlayerRoundButton(onClick = { onSeekBy(SEEK_STEP_MS) }, size = 56.dp) {
            Icon(
                Icons.Filled.Forward10,
                contentDescription = "Forward 10 seconds",
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * Bottom row: the scrubber and the action buttons over it.
 *
 * The action row is deliberately single-line and always in the same order —
 * muscle memory is the whole point on a remote.
 */
@Composable
private fun PlayerBottomControls(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    skips: List<Skip>,
    speed: Float,
    qualityLabel: String,
    hasQualities: Boolean,
    hasNext: Boolean,
    hasPrev: Boolean,
    onPlayPause: () -> Unit,
    onOpenMenu: (Int) -> Unit,
    resizeLabel: String,
    onResizeToggle: () -> Unit,
    statsOpen: Boolean,
    onToggleStats: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onScrubberFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 24.dp)) {
        PlayerScrubber(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            skips = skips,
            onScrubberFocus = onScrubberFocus,
        )
        Spacer(Modifier.height(14.dp))
        // Two fixed points: Prev/Next stay on the right, the rest scrolls left
        // to right under the remote. A plain Row overflowed once the quality
        // chip carried the full provider card title.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item(key = "audio") {
                    PlayerActionButton(
                        icon = Icons.Filled.Audiotrack,
                        label = "Audio",
                        onClick = { onOpenMenu(2) },
                    )
                }
                item(key = "subs") {
                    PlayerActionButton(
                        icon = Icons.Filled.Subtitles,
                        label = "Subs",
                        onClick = { onOpenMenu(3) },
                    )
                }
                item(key = "speed") {
                    PlayerActionButton(
                        icon = Icons.Filled.Speed,
                        label = formatSpeed(speed),
                        onClick = { onOpenMenu(4) },
                    )
                }
                if (hasQualities) {
                    item(key = "quality") {
                        PlayerActionButton(
                            icon = Icons.Filled.HighQuality,
                            // The provider's whole card name, size included
                            // ("4K HDR REMUX HEVC H265 IMAX 80.2GB"): the chip
                            // grows instead of reducing it to "2160p".
                            label = qualityLabel,
                            onClick = { onOpenMenu(1) },
                            modifier = Modifier.widthIn(max = 480.dp),
                        )
                    }
                }
                item(key = "server") {
                    PlayerActionButton(
                        icon = Icons.Filled.Storage,
                        label = "Server",
                        onClick = { onOpenMenu(0) },
                    )
                }
                // The Video tab's own footer actions, where the Video chip
                // used to sit: one press instead of menu → tab → action.
                item(key = "resize") {
                    PlayerActionButton(
                        icon = Icons.Filled.AspectRatio,
                        label = resizeLabel,
                        onClick = onResizeToggle,
                    )
                }
                item(key = "stats") {
                    PlayerActionButton(
                        icon = Icons.Filled.Info,
                        label = if (statsOpen) "Stats ✓" else "Stats",
                        onClick = onToggleStats,
                    )
                }
            }
            if (hasPrev) {
                Button(
                    onClick = onPrev,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Previous")
                }
            }
            if (hasNext) {
                Button(
                    onClick = onNext,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Next")
                }
            }
        }
    }
}

/**
 * Timeline: played / buffered / total, skip ticks, times, and a focusable
 * thumb. The thumb takes focus so the arrows can seek while it is focused —
 * see the key handler in [PlayerScreen], which treats "scrubber focused" as
 * "arrows belong to playback".
 */
@Composable
private fun PlayerScrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    skips: List<Skip>,
    onScrubberFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasDuration = durationMs > 0
    val playFraction = if (hasDuration) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f
    val bufferFraction = if (hasDuration) {
        (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f

    var focused by remember { mutableStateOf(false) }
    // The whole timeline row is the focus target. The thumb used to be the only
    // focusable thing and, at 16dp, was effectively unreachable with a D-pad —
    // so the seek keys could never be armed. One full-width row cannot be
    // missed; LEFT/RIGHT then step 10% (see the key handler in [PlayerScreen]).
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .onFocusEvent {
                    focused = it.isFocused
                    onScrubberFocus(it.isFocused)
                }
                .focusable()
                .then(
                    if (focused) {
                        Modifier
                            .background(Color(0x1FFFFFFF))
                            .border(2.dp, VegaPrimary, RoundedCornerShape(15.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            // Total track
            Box(
                Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
            )
            // Buffered, then played, then the skip markers, then the thumb — each
            // positioned by its own fraction so nothing depends on knowing the
            // pixel width of the track. The floor keeps the thumb measurable at
            // position zero, where a 0%-width box would collapse it to nothing.
            if (bufferFraction > 0f) {
                Box(
                    Modifier.fillMaxWidth(bufferFraction.coerceAtLeast(MIN_TRACK))
                        .height(4.dp).align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.35f)),
                )
            }
            Box(
                Modifier.fillMaxWidth(playFraction.coerceAtLeast(MIN_TRACK))
                    .height(4.dp).align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.dp))
                    .background(VegaPrimary),
            )
            // Official skip rendering: two black boundary marks per interval,
            // at the start and the end, so the skipped section reads as a gap
            // in the track. A boundary at 0 or at the duration lies outside the
            // track and is skipped, exactly as in the mobile client.
            if (hasDuration) {
                skips.forEach { skip ->
                    listOf(skip.fromMs, skip.toMs).forEach { at ->
                        if (at > 0 && at < durationMs) {
                            val f = (at.toFloat() / durationMs).coerceIn(0f, 1f)
                            Box(
                                Modifier.fillMaxWidth(f.coerceAtLeast(MIN_TRACK)).fillMaxHeight()
                                    .wrapContentWidth(Alignment.End)
                                    .wrapContentHeight(Alignment.CenterVertically),
                            ) {
                                Box(
                                    Modifier.width(3.dp).height(4.dp)
                                        .background(Color.Black),
                                )
                            }
                        }
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth(playFraction.coerceAtLeast(MIN_TRACK)).fillMaxHeight()
                    .wrapContentWidth(Alignment.End)
                    .wrapContentHeight(Alignment.CenterVertically),
            ) {
                // Decorative only: the row above owns focus, so seeking never
                // depends on hitting a 16dp circle with a D-pad.
                Box(
                    Modifier.size(16.dp)
                        .clip(CircleShape)
                        .background(VegaPrimary),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                fmtMs(positionMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xCCFFFFFF),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (hasDuration) "-${fmtMs((durationMs - positionMs).coerceAtLeast(0L))}" else "live",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xCCFFFFFF),
            )
        }
    }
}

@Composable
private fun PlayerTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xCC14100F))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** Text button used in the bottom action row. */
@Composable
private fun PlayerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xB314100F),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF14100F),
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        colors = IconButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF14100F),
        ),
    ) { content() }
}

@Composable
private fun PlayerRoundButton(
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    containerColor: Color = Color(0xB314100F),
    contentColor: Color = Color.White,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(size).then(
            if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
        ),
        shape = ButtonDefaults.shape(shape = CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = VegaPrimary,
            focusedContentColor = Color.White,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/** "Up next" countdown: the web player's auto-next, as a TV overlay. */
@Composable
private fun AutoNextOverlay(
    secondsLeft: Int,
    title: String,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier.fillMaxWidth(0.5f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xE614100F))
            .padding(28.dp),
    ) {
        Text(
            "Up next",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = VegaPrimary,
        )
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Playing in ${secondsLeft}s",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xB3FFFFFF),
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPlayNow, modifier = Modifier.focusRequester(focus)) {
                Text("Play now")
            }
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}

/** What a seek *is going to* land on: direction, step, target time. */
@Composable
private fun SeekOsdBadge(osd: SeekOsd, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC14100F))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (osd.blocked) {
            Icon(
                Icons.Filled.Block,
                contentDescription = null,
                tint = VegaPrimary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (osd.live) "Live stream — seeking is unavailable"
                else "Seeking is unavailable for this stream",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        } else {
            Icon(
                if (osd.direction < 0) Icons.Filled.Replay10 else Icons.Filled.Forward10,
                contentDescription = null,
                tint = VegaPrimary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${if (osd.direction < 0) "-" else "+"}${kotlin.math.abs(osd.stepMs) / 1000}s",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                fmtMs(osd.targetMs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = VegaPrimary,
            )
        }
    }
}

/** "Stats for nerds": what is actually playing, in one small panel. */
@Composable
private fun StatsOverlay(
    video: String,
    audio: String,
    dropped: Int,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    speed: Float,
    resizeMode: Int,
    serverLabel: String,
    source: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC14100F))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "Stats",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = VegaPrimary,
        )
        val lines = listOf(
            "Video: " + video.ifBlank { "—" },
            "Audio: " + audio.ifBlank { "—" },
            "Buffer: ${fmtMs(bufferedMs)} / ${fmtMs(durationMs)}",
            "Position: ${fmtMs(positionMs)}",
            "Dropped frames: $dropped",
            "Speed: ${formatSpeed(speed)} · ${resizeLabel(resizeMode)}",
            "Server: " + serverLabel.ifBlank { "—" },
            "Source: " + source.ifBlank { "—" },
        )
        lines.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xE6FFFFFF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * What a playback failure looks like on a TV.
 *
 * Three things, because "it didn't play" has three different causes that need
 * three different answers: the same server usually fails the same way twice, so
 * Retry is the first thing to reach for; a different rendition is the second;
 * and leaving is always available, so the screen is never a dead end. [message]
 * is the decoder's own error code, which is what turns "it didn't play" into
 * "the host refused the request" or "the codec is not supported on this box".
 */
@Composable
private fun PlaybackError(
    message: String,
    onRetry: () -> Unit,
    onServer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Playback failed",
    retryLabel: String = "Retry",
    /** Null hides the second button (a failure with nothing else to offer). */
    serverLabel: String? = "Try another server",
    closeLabel: String = "Back",
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier
            .fillMaxWidth(0.62f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xE614100F))
            .padding(28.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xB3FFFFFF),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text(retryLabel) }
            if (serverLabel != null) {
                Button(onClick = onServer) { Text(serverLabel) }
            }
            Button(onClick = onClose) { Text(closeLabel) }
        }
    }
}

/** Placeholder for a MENU tab whose list does not exist yet (first resolve). */
@Composable
private fun MenuEmpty(text: String) {
    Box(Modifier.height(280.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xB3FFFFFF),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * MENU panel: servers, quality cards, audio, subtitles (plus online search), speed
 * search — the long tail of what a stream offers, kept off the bottom bar so
 * the frequently used controls stay one press away.
 */
@Composable
private fun MenuPanel(
    streams: List<VegaStream>,
    selected: Int,
    tab: Int,
    onTab: (Int) -> Unit,
    onServer: (Int) -> Unit,
    choices: List<QualityChoice>,
    playingLink: String,
    onQualityLink: (String) -> Unit,
    player: ExoPlayer,
    trackSelector: DefaultTrackSelector,
    onSpeed: (Float) -> Unit,
    speed: Float,
    statsOpen: Boolean,
    onToggleStats: () -> Unit,
    resizeMode: Int,
    onResize: () -> Unit,
    onNext: () -> Unit,
    hasNext: Boolean,
    onPrev: () -> Unit,
    hasPrev: Boolean,
    onSubAdded: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit,
    firstTabFocus: FocusRequester,
    /** What to say when a tab's list is empty (resolving vs. failed). */
    emptyHint: String,
    modifier: Modifier = Modifier,
) {
    // Find was a second, weaker episode picker; the bar now carries the panel's
    // own Video actions (Resize/Stats) instead of a tab-opening chip, so both
    // tabs are gone.
    val tabs = listOf("Server", "Quality", "Audio", "Subs", "Speed")
    // The panel replaced the control bar, so focus left the screen with it.
    // Start it back on the first tab rather than leaving the remote with
    // nothing to point at.
    LaunchedEffect(Unit) { runCatching { firstTabFocus.requestFocus() } }
    Column(
        modifier
            .fillMaxWidth(0.44f)
            .clip(RoundedCornerShape(20.dp))
            .background(ScrimHeavy)
            .padding(18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEachIndexed { i, t ->
                // Equal widths rather than intrinsic ones: five labels at their
                // natural width overflow the panel and squeeze the last one —
                // "Speed" — into a column of single characters.
                Button(
                    onClick = { onTab(i) },
                    modifier = Modifier.weight(1f).then(
                        if (i == 0) Modifier.focusRequester(firstTabFocus) else Modifier,
                    ),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                ) {
                    Text(
                        (if (i == tab) "• " else "") + t,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        when (tab) {
            0 -> {
                if (streams.isEmpty()) MenuEmpty(emptyHint)
                else LazyColumn(Modifier.height(280.dp)) {
                    itemsIndexed(streams, key = { i, _ -> "srv-$i" }) { i, st ->
                        Button(
                            onClick = { onServer(i) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Text(
                                "${if (i == selected) "✓ " else ""}${st.server.ifBlank { "Server ${i + 1}" }} · ${(st.quality ?: "").ifBlank { "Auto" }}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            1 -> {
                // Quality dropdown: the same shape as the server list, but the
                // entries are only the renditions the provider actually named
                // (plus "Auto" when it named nothing) — no server or title.
                // A movie's quality cards are separate links, so selecting one
                // swaps the source link instead of an in-stream rendition.
                val playing = (streams.getOrNull(selected)?.quality ?: "")
                    .trim().ifBlank { "Auto" }.lowercase()
                if (choices.isEmpty()) MenuEmpty(emptyHint)
                else LazyColumn(Modifier.height(280.dp)) {
                    itemsIndexed(choices, key = { i, _ -> "q-$i" }) { _, choice ->
                        val active = if (choice.streamIndex >= 0) {
                            choice.label.lowercase() == playing
                        } else {
                            choice.link == playingLink
                        }
                        Button(
                            onClick = {
                                if (choice.streamIndex >= 0) onServer(choice.streamIndex)
                                else onQualityLink(choice.link)
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Text(
                                (if (active) "✓ " else "") + choice.label,
                                // A provider card title carries the whole
                                // rendition ("4K HDR REMUX HEVC H265 IMAX
                                // 80.2GB"); two lines show it without
                                // truncating the size away.
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            2 -> AudioList(player, trackSelector)
            3 -> SubList(player, trackSelector, onSubAdded)
            4 -> SpeedAndFps(speed = speed, onSpeed = onSpeed)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onResize) { Text("Resize: ${resizeLabel(resizeMode)}") }
            if (hasPrev) Button(onClick = onPrev) { Text("Prev") }
            if (hasNext) Button(onClick = onNext) { Text("Next") }
            Button(onClick = onToggleStats) { Text(if (statsOpen) "Stats ✓" else "Stats") }
            Button(onClick = onClose) { Text("Close") }
            Button(onClick = onExit) { Text("Exit") }
        }
    }
}

/**
 * Playback speed, plus the frame-rate ceiling.
 *
 * "Auto" is not a placeholder — it is the only correct answer for most content.
 * A 24fps film forced to 60fps judders visibly, and a 60fps source the hardware
 * cannot sustain drops frames either way; letting the source's own rate through
 * is what a TV does. The explicit numbers exist for the case where a source
 * advertises a rate the box cannot decode — capping it is a visible, deliberate
 * choice rather than a stutter.
 */
@Composable
private fun SpeedAndFps(speed: Float, onSpeed: (Float) -> Unit) {
    val scope = rememberCoroutineScope()
    val fps by ServiceLocator.settings.maxFps
        .collectAsStateWithLifecycle(initialValue = "auto")
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                Button(onClick = { onSpeed(sp) }) {
                    Text((if (kotlin.math.abs(speed - sp) < 0.01f) "▶ " else "") + "${sp}x")
                }
            }
        }
        Text(
            "Frame rate cap",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("auto" to "Auto", "24" to "24", "30" to "30", "50" to "50", "60" to "60")
                .forEach { (v, label) ->
                    Button(
                        onClick = { scope.launch { ServiceLocator.settings.setMaxFps(v) } },
                    ) { Text((if (fps == v) "▶ " else "") + label) }
                }
        }
    }
}

/** Alternate audio tracks, when the provider offers more than one. */
@Composable
private fun AudioList(player: ExoPlayer, trackSelector: DefaultTrackSelector) {
    val groups = remember(player.currentTracks) {
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    }
    if (groups.isEmpty()) {
        Text(
            "No alternate audio",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        return
    }
    groups.forEach { g ->
        for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            val label = buildString {
                if (g.isSelected) append("✓ ")
                append(f.label ?: f.language?.uppercase() ?: "Track ${i + 1}")
                if (f.channelCount > 0) append(" · ${f.channelCount}ch")
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(
                    onClick = {
                        trackSelector.buildUponParameters()
                            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                            .build().let { trackSelector.parameters = it }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Embedded + external subtitle tracks, plus off, plus file delay. */
@Composable
private fun SubList(
    player: ExoPlayer,
    trackSelector: DefaultTrackSelector,
    onSubAdded: () -> Unit,
) {
    // Mirrored into local state so the label repaints; the parent may skip
    // recomposing this composable when only the shared counter changed.
    var delayMs by remember { mutableLongStateOf(PlaybackSession.subDelayMs) }
    var delayNote by remember { mutableStateOf<String?>(null) }
    // The Find tab is gone from the bar; searching/importing an online
    // subtitle still belongs next to the track list it feeds.
    var finding by remember { mutableStateOf(false) }
    fun shift(delta: Long) {
        val ok = applySubDelay(delta) { onSubAdded() }
        delayMs = PlaybackSession.subDelayMs
        delayNote = if (ok) null else "This subtitle format can't shift"
    }
    if (finding) {
        Column {
            SubFind(onSubAdded = onSubAdded)
            Button(onClick = { finding = false }, modifier = Modifier.padding(top = 10.dp)) {
                Text("Back to tracks")
            }
        }
        return
    }
    Column {
        Button(
            onClick = {
                trackSelector.buildUponParameters()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build().let { trackSelector.parameters = it }
            },
        ) { Text("Disabled") }
        // VLC's subtitle delay. Files are rewritten in place (±0.1s / ±0.5s),
        // which is what a desynced downloaded subtitle actually needs.
        if (PlaybackSession.externalSubs.isNotEmpty()) {
            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { shift(-500) }) { Text("-0.5s") }
                Button(onClick = { shift(-100) }) { Text("-0.1s") }
                Text(
                    "Delay ${"%+.1f".format(delayMs / 1000.0)}s",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(onClick = { shift(100) }) { Text("+0.1s") }
                Button(onClick = { shift(500) }) { Text("+0.5s") }
                if (delayMs != 0L) {
                    Button(onClick = { shift(-delayMs) }) { Text("Reset") }
                }
            }
            delayNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        val groups = remember(player.currentTracks) {
            player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        }
        groups.forEach { g ->
            for (i in 0 until g.length) {
                val f = g.getTrackFormat(i)
                val label = (if (g.isSelected) "✓ " else "") +
                    (f.label ?: f.language?.uppercase() ?: "Sub ${i + 1}")
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Button(
                        onClick = {
                            trackSelector.buildUponParameters()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                .build().let { trackSelector.parameters = it }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        Button(
            onClick = { finding = true },
            modifier = Modifier.padding(top = 10.dp),
        ) { Text("Add subtitle…") }
    }
}

/** Web SearchSubtitles twin: online search + external file import. */
@Composable
private fun SubFind(onSubAdded: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(PlaybackSession.title) }
    var status by remember { mutableStateOf<String?>(null) }
    var results by remember {
        mutableStateOf<List<tv.vega.tv.data.subtitles.SubResult>>(emptyList())
    }

    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = "Importing…"
                status = runCatching {
                    val name = uri.lastPathSegment.orEmpty()
                    val mime = when {
                        name.endsWith(".srt", true) -> "application/x-subrip"
                        name.endsWith(".ttml", true) || name.endsWith(".xml", true) -> "application/ttml+xml"
                        else -> "text/vtt"
                    }
                    val dest = java.io.File(ctx.cacheDir, "subs/import_${System.currentTimeMillis()}.vtt")
                        .apply { parentFile?.mkdirs() }
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        dest.outputStream().use { ins.copyTo(it) }
                    } ?: error("Cannot read file")
                    PlaybackSession.externalSubs = PlaybackSession.externalSubs +
                        tv.vega.tv.player.ExternalSub(android.net.Uri.fromFile(dest).toString(), mime, "ext", name)
                    // A freshly added file is unshifted; the running delay
                    // total describes the *old* files and must not leak onto it.
                    PlaybackSession.subDelayMs = 0L
                    PlaybackSession.subsRevision++
                    onSubAdded()
                    "Added ✓ (rebuilding)"
                }.getOrElse { "Failed" }
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Title or IMDb id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    status = "Searching…"
                    results = runCatching {
                        tv.vega.tv.data.subtitles.SubApi.search(query)
                    }.getOrDefault(emptyList())
                    status = if (results.isEmpty()) "No results" else null
                }
            }) { Text("Search") }
            Button(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Open file") }
        }
        status?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        results.take(8).forEach { r ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Button(onClick = {
                    scope.launch {
                        status = "Downloading…"
                        status = runCatching {
                            val uri = tv.vega.tv.data.subtitles.SubApi.fetchToCache(ctx, r)
                            PlaybackSession.externalSubs = PlaybackSession.externalSubs +
                                tv.vega.tv.player.ExternalSub(uri, "text/vtt", r.lang.ifBlank { "und" }, r.title)
                            PlaybackSession.subDelayMs = 0L
                            PlaybackSession.subsRevision++
                            onSubAdded()
                            "Added ✓ (rebuilding)"
                        }.getOrElse { "Failed" }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("${r.lang} · ${r.title}".take(48), maxLines = 1)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun fmtMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSpeed(speed: Float): String {
    val rounded = Math.round(speed * 100f) / 100f
    return if (rounded % 1f == 0f) "${rounded.toInt()}x" else "${rounded}x"
}

private fun resizeLabel(mode: Int): String = when (mode) {
    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
    else -> "Fit"
}

private fun streamLabel(sel: VegaStream) = buildString {
    append(sel.server.ifBlank { "Server" })
    val q = sel.quality?.trim().orEmpty()
    if (q.isNotBlank()) append(" · $q")
}

/**
 * One entry of the Quality tab: either an in-stream rendition ([streamIndex])
 * or a whole alternate link from the provider's movie quality cards ([link]).
 */
private data class QualityChoice(
    val label: String,
    val streamIndex: Int = -1,
    val link: String = "",
)

/**
 * The player's quality list.
 *
 * A movie's provider hands over named quality cards ("4K HDR REMUX HEVC H265
 * IMAX 80.2GB"). Those are the renditions the viewer picked between on the
 * detail page, so they *are* the list — the streams behind any one of them
 * carry a resolution label ("2160p") that would replace the richer card title
 * and pretend the other cards do not exist. Best resolution, then largest file,
 * comes first. Shows have no alternates and keep their stream qualities.
 */
private fun qualityChoices(streams: List<VegaStream>): List<QualityChoice> {
    val alternates = PlaybackSession.qualityAlternates
    if (alternates.isNotEmpty()) {
        return alternates
            .distinctBy { it.link }
            .sortedByDescending { qualityScore(it.label) }
            .map { QualityChoice(it.label, link = it.link) }
    }
    return qualityOptions(streams).map { (label, i) ->
        QualityChoice(label, streamIndex = i)
    }
}

/**
 * Distinct quality labels the provider named, best first, each mapped to the
 * index of the best stream carrying it. Labels are deduplicated
 * case-insensitively; streams whose quality is blank collapse into one "Auto"
 * entry that points at the best-ranked of them.
 */
private fun qualityOptions(streams: List<VegaStream>): List<Pair<String, Int>> {
    val best = LinkedHashMap<String, Int>()
    streams.forEachIndexed { i, s ->
        val key = s.quality?.trim().orEmpty().lowercase()
        val cur = best[key]
        if (cur == null || streamHeight(s) >= streamHeight(streams[cur])) best[key] = i
    }
    return best.values
        .sortedByDescending { streamHeight(streams[it]) }
        .map { i -> streams[i].quality?.trim().orEmpty().ifBlank { "Auto" } to i }
}

/** "Episode 4" / "Episode 4 · The Heist" — never a provider file name. */
private fun episodeSubtitle(ep: tv.vega.tv.data.model.EpisodeItem): String {
    val label = tv.vega.tv.ui.common.episodeLabel(ep)
    if (label.startsWith("Episode")) return label
    val num = tv.vega.tv.ui.common.episodeNumber(ep)
    return if (num.isNotBlank()) "Episode $num · $label" else label
}

/**
 * Every skip interval the current episode can carry, merged the way the
 * official client merges them: the episode, the selected stream, and a direct
 * link card that names the playing link. Providers use `skip` and `skips`
 * interchangeably, so both are read.
 *
 * Malformed intervals are rejected rather than trusted: a negative start or an
 * empty range would draw a marker in the wrong place and seek backwards.
 */
private fun collectSkips(sel: VegaStream): List<Skip> {
    val out = mutableListOf<Skip>()
    fun add(list: List<SkipInterval>?) {
        if (list == null) return
        for (item in list) {
            if (item.from < 0.0 || item.to <= item.from) continue
            val fromMs = (item.from * 1000).toLong()
            val toMs = (item.to * 1000).toLong()
            if (fromMs < 0 || toMs <= fromMs) continue
            // The official client treats intervals within a second of each
            // other as the same one; providers repeat them across sources with
            // slightly different rounding.
            val exists = out.any { skip ->
                abs(skip.fromMs - fromMs) < 1_000 && abs(skip.toMs - toMs) < 1_000
            }
            if (!exists) {
                out.add(
                    Skip(
                        title = item.title?.trim().orEmpty().ifBlank { "Intro" },
                        fromMs = fromMs,
                        toMs = toMs,
                    ),
                )
            }
        }
    }

    val episode = PlaybackSession.episodes.getOrNull(PlaybackSession.startIndex)
    add(sel.skip)
    add(sel.skips)
    episode?.let {
        add(it.skip)
        add(it.skips)
    }

    // Direct links are playable episodes too, but providers attach their
    // intervals to the linkList card instead of the episode row. Match it by
    // URL, as the official client does.
    val episodeLink = episode?.link
    if (!episodeLink.isNullOrBlank()) {
        PlaybackSession.info?.linkList?.forEach { season ->
            season.directLinks.firstOrNull { it.link == episodeLink }?.let {
                add(it.skip)
                add(it.skips)
            }
        }
    }

    return out.sortedBy { it.fromMs }
}

private fun mimeOf(uri: String, declared: String): String {
    if (declared.isNotBlank()) {
        if (declared.contains("vtt")) return MimeTypes.TEXT_VTT
        if (declared.contains("ttml")) return MimeTypes.APPLICATION_TTML
        if (declared.contains("subrip") || declared.contains("srt")) return MimeTypes.APPLICATION_SUBRIP
    }
    return when {
        uri.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
        uri.endsWith(".srt", true) -> MimeTypes.APPLICATION_SUBRIP
        uri.endsWith(".ttml", true) || uri.endsWith(".xml", true) -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
}

/** Applies web SubtitleSettings (size/color/edge) to the native caption view. */
private fun applySubtitleStyle(
    view: PlayerView,
    style: tv.vega.tv.data.settings.SubtitleStyle,
) {
    runCatching {
        val sv = view.subtitleView ?: return
        val fg = runCatching { android.graphics.Color.parseColor(style.color) }
            .getOrDefault(android.graphics.Color.WHITE)
        val edgeType = when (style.edge) {
            "dropShadow" -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            "raised" -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_RAISED
            "depressed" -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DEPRESSED
            "none" -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
            else -> androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
        sv.setStyle(
            androidx.media3.ui.CaptionStyleCompat(
                fg,
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                edgeType,
                android.graphics.Color.BLACK,
                null,
            ),
        )
        sv.setFractionalTextSize(0.0533f * style.scale.coerceIn(0.5f, 2.5f))
    }
}

/**
 * Applies the frame-rate cap setting to the player's own surface (API 30+).
 *
 * Without this the setting was a ghost: it is offered in Settings *and* in the
 * player's Speed tab, but nothing ever told the display about it. "auto" clears
 * the hint so the panel follows the source on its own.
 */
private fun applyFrameRateCap(view: PlayerView, cap: String) {
    if (android.os.Build.VERSION.SDK_INT < 30) return
    val sv = view.videoSurfaceView as? android.view.SurfaceView ?: return
    // SurfaceView does not carry the frame-rate API; the surface it holds does.
    val surface = sv.holder.surface ?: return
    if (!surface.isValid) return
    val fps = cap.toFloatOrNull()
    if (fps == null) {
        surface.clearFrameRate()
    } else {
        surface.setFrameRate(fps, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
    }
}

/**
 * Rewrites every external subtitle file shifted by [deltaMs] and asks the
 * player to rebuild.
 *
 * This is the VLC-style subtitle delay, scoped honestly: Media3 has no timing
 * offset for a `SubtitleConfiguration`, so the only place a delay can live is
 * the text itself. Files this app owns (imported / downloaded) are rewritten in
 * place; provider-hosted URLs are not touched and report false.
 *
 * The running total is tracked in [PlaybackSession.subDelayMs] because each
 * call shifts the file that is already shifted — the deltas are cumulative.
 */
private fun applySubDelay(deltaMs: Long, onChanged: () -> Unit): Boolean {
    var changed = false
    PlaybackSession.externalSubs = PlaybackSession.externalSubs.map { e ->
        if (shiftSubtitleFile(e.uri, deltaMs)) {
            changed = true
            e
        } else e
    }
    if (changed) {
        PlaybackSession.subDelayMs += deltaMs
        PlaybackSession.subsRevision++
        onChanged()
    }
    return changed
}

/** Shifts timestamps in a local subtitle file in place; false if not shiftable. */
private fun shiftSubtitleFile(uri: String, deltaMs: Long): Boolean {
    val u = android.net.Uri.parse(uri)
    if (u.scheme != "file") return false
    val path = u.path ?: return false
    val f = java.io.File(path)
    if (!f.isFile) return false
    return runCatching {
        val text = f.readText()
        val shifted = shiftSubtitleTimes(text, deltaMs)
        if (shifted == text) {
            false
        } else {
            f.writeText(shifted)
            true
        }
    }.getOrDefault(false)
}

// SRT "00:00:01,000", VTT "00:00:01.000" / "00:01.000", ASS "0:00:01.00".
// The fraction width is preserved so ASS centiseconds stay centiseconds.
private val SUB_TIME = Regex(
    """(?:(?<hh>\d{1,3}):)?(?<mm>\d{1,2}):(?<ss>\d{2})[,.](?<frac>\d{1,3})""",
)

private fun shiftSubtitleTimes(text: String, deltaMs: Long): String =
    SUB_TIME.replace(text) { m ->
        val hh = m.groups["hh"]?.value?.toLongOrNull() ?: 0L
        val mm = m.groups["mm"]!!.value.toLong()
        val ss = m.groups["ss"]!!.value.toLong()
        val fracRaw = m.groups["frac"]!!.value
        val fracMs = when (fracRaw.length) {
            1 -> fracRaw.toLong() * 100
            2 -> fracRaw.toLong() * 10
            else -> fracRaw.toLong()
        }
        var total = hh * 3_600_000 + mm * 60_000 + ss * 1000 + fracMs + deltaMs
        if (total < 0) total = 0
        val sep = if (m.value.contains(',')) ',' else '.'
        val fracOut = when (fracRaw.length) {
            1 -> (total % 1000) / 100
            2 -> (total % 1000) / 10
            else -> total % 1000
        }.toString().padStart(fracRaw.length, '0')
        val mOut = "%02d".format((total / 60_000) % 60)
        val sOut = "%02d".format((total / 1000) % 60)
        if (m.groups["hh"] != null) {
            "${total / 3_600_000}:$mOut:$sOut$sep$fracOut"
        } else {
            "${total / 60_000}:$sOut$sep$fracOut"
        }
    }

/**
 * Preferred-audio language codes. Both the 2-letter and 3-letter form are
 * supplied because providers label tracks inconsistently ("hi" vs "hin").
 * Media3 normalises them and falls back to the source's default if neither
 * exists.
 */
private fun preferredAudioCodes(value: String): Array<String> = when (value.lowercase()) {
    "hi", "hin", "hindi" -> arrayOf("hi", "hin")
    "en", "eng", "english" -> arrayOf("en", "eng")
    else -> emptyArray()
}

/**
 * Matches both real language tags and the label providers actually ship.
 * ExoPlayer leaves [Format.language] null/und when the container does not
 * declare it, even though the label says "Hindi" or "हिंदी".
 */
private fun audioTrackMatches(format: Format, preference: String): Boolean {
    val want = preference.lowercase()
    val hindi = want == "hi" || want == "hin" || want == "hindi"
    val english = want == "en" || want == "eng" || want == "english"
    if (!hindi && !english) return false
    val lang = format.language?.trim()?.lowercase().orEmpty()
    val label = format.label?.trim()?.lowercase().orEmpty()
    val labelMatch = if (hindi) {
        label.contains("hindi") || label.contains("हिंदी") || label.contains("हिन्दी")
    } else {
        label.contains("english") || label.contains("अंग्रेज़ी") || label.contains("अंग्रेजी")
    }
    if (labelMatch) return true
    return if (hindi) {
        lang == "hi" || lang == "hin" || lang.startsWith("hi-") || lang.startsWith("hi_")
    } else {
        lang == "en" || lang == "eng" || lang.startsWith("en-") || lang.startsWith("en_")
    }
}

private suspend fun saveProgress(pos: Long, dur: Long, sync: Boolean = false) {
    runCatching {
        val ep = PlaybackSession.episodes.getOrNull(PlaybackSession.startIndex)
        // Save the link playback actually used, not the post/detail page that
        // opened the title. Continue Watching calls getStream directly with
        // this value; the post link is only an input to getMeta. Storing the
        // post link here was why resuming failed with "Blocked private host"
        // while the same title played from its feed.
        val playable = ep?.link?.takeIf { it.isNotBlank() }
            ?: PlaybackSession.pendingLink
        val title = PlaybackSession.title.ifBlank { PlaybackSession.pendingTitle }
        val episodeTitle = ep?.title ?: PlaybackSession.pendingEpisodeTitle
        val provider = ServiceLocator.providers.active()?.ext?.value.orEmpty()
        val mediaType = PlaybackSession.info?.type
            ?.takeIf { it.isNotBlank() }
            ?: PlaybackSession.pendingType
        ServiceLocator.history.upsert(
            WatchEntry(
                mediaId = playable.ifBlank { return },
                title = title,
                episodeTitle = episodeTitle,
                posterUrl = PlaybackSession.posterUrl.ifBlank { PlaybackSession.pendingPoster },
                positionMs = pos, durationMs = dur, updatedAt = System.currentTimeMillis(),
                link = playable,
                provider = provider,
                episodeIndex = PlaybackSession.startIndex,
                mediaType = mediaType,
                // One row per title/episode across server, quality and URL
                // changes. The link is mutable playback state, not identity.
                historyKey = tv.vega.tv.data.history.WatchHistoryStore.keyOf(
                    provider, title, episodeTitle, PlaybackSession.startIndex,
                ),
                // Keep every known server/quality and the movie quality cards
                // so a resume offers the whole list, not only the last pick.
                streams = PlaybackSession.streams,
                qualityLinks = PlaybackSession.qualityAlternates,
                lastStreamKey = PlaybackSession.playingStreamKey,
            ),
        )
        // A periodic checkpoint is a local DataStore write only. Publishing a
        // sync manifest every five seconds made file I/O and shared-folder work
        // compete with the decoder; publish once, when playback ends.
        if (sync) ServiceLocator.syncSoon()
    }
}

private fun saveProgressSync(pos: Long, dur: Long) {
    // Fire-and-forget from onDispose (no scope): use a daemon thread.
    Thread {
        runCatching {
            kotlinx.coroutines.runBlocking { saveProgress(pos, dur, sync = true) }
        }
    }.apply { isDaemon = true }.start()
}

private fun openExternal(ctx: android.content.Context, sel: VegaStream) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndTypeAndNormalize(Uri.parse(sel.link), "video/*")
            putExtra("title", PlaybackSession.title)
            sel.headers.forEach { (k, v) -> putExtra(k, v) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "Play with"))
    }
}
