package tv.vega.tv.ui.player

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
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
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.history.WatchEntry
import tv.vega.tv.data.model.VegaStream
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.player.PlayerKeyBus
import tv.vega.tv.ui.common.ErrorScreen
import tv.vega.tv.ui.common.LoadingScreen
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.WafError
import tv.vega.tv.ui.theme.ScrimHeavy
import tv.vega.tv.ui.theme.VegaPrimary

private const val AUTO_HIDE_MS = 5_000L
private const val UI_TICK_MS = 200L
private const val SEEK_STEP_MS = 10_000L
private const val AUTO_NEXT_SECONDS = 8
private const val AUTO_NEXT_FRACTION = 0.98f
private const val MIN_TRACK = 0.001f

private data class Skip(val title: String, val fromMs: Long, val toMs: Long)

/**
 * What the seek overlay shows: direction, the accumulated step of the current
 * key burst (±10s per press, so a held key reads as ±30s), and the target time.
 */
private data class SeekOsd(val direction: Int, val targetMs: Long, val stepMs: Long)

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
 *   │   🎧 Audio  CC Subs  1.0x  Server  Fit  ⋯ More   Next ▶  │  action row
 *   └──────────────────────────────────────────────────────────┘
 *
 * Keys (the remote, not the surface, drives playback — see [PlayerKeyBus]):
 *   OK          play/pause — or reveal the UI when it is hidden
 *   ← / →       ±10s when the UI is hidden or the scrubber has focus,
 *               otherwise D-pad movement between the control buttons
 *   ↑ / ↓       spatial navigation / reveal the UI when hidden
 *   MENU        server · audio · subs · speed · video · find panel
 *   BACK        closes the panel, cancels auto-next, hides the UI, exits
 */
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onSolveWaf: (String) -> Unit = {},
    vm: PlayerViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val wafUrl by vm.wafUrl.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var menuTab by remember { mutableStateOf(0) } // 0 server 1 audio 2 subs 3 speed 4 video 5 find
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var resizeMode by remember {
        mutableStateOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
    }
    var playError by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(UI_TICK_MS)
            // Never hide while paused: a frozen frame with no controls reads as
            // a crash, and every TV player keeps its chrome up when paused.
            if (showUI && !menuOpen && playError == null && autoNextLeft == null &&
                isPlayingNow &&
                System.currentTimeMillis() - lastInteraction > AUTO_HIDE_MS
            ) {
                showUI = false
                // Focus must leave the (now uncomposed) controls, or the next
                // arrow press is consumed by an invisible focus target.
                focusManager.clearFocus()
            }
        }
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
                .build()
        }
    }
    // Header-aware HTTP stack: the factory reads currentHeaders on every
    // request, so per-stream provider auth applies to media + segments.
    val headerHttp = remember {
        object : DataSource.Factory {
            @Volatile
            var headers: Map<String, String> = emptyMap()
            override fun createDataSource(): DataSource =
                DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0")
                    // Merged, not overwritten: a second setDefaultRequestProperties
                    // call replaces the first, which would silently drop every
                    // provider authorization header the moment an Accept were added.
                    .setDefaultRequestProperties(
                        // Cookies are provider credentials too. Dropping them
                        // makes otherwise valid signed/HLS servers return 403.
                        headers + ("Accept" to "*/*"),
                    )
                    .createDataSource()
        }
    }
    // The listener outlives any single composition, so it reaches the UI
    // through this holder rather than through a state it captured. Without the
    // assignment below the listener would fire into a no-op and a 403 would
    // look exactly like a video that never started.
    val showError = remember { mutableStateOf<(String?) -> Unit>({}) }
    LaunchedEffect(Unit) { showError.value = { playError = it } }
    val player = remember {
        ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx))
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(headerHttp))
            .setTrackSelector(trackSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(8_000, 20_000, 1_500, 3_000)
                    .build(),
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
            }
    }
    // Swap provider-authorized headers per stream.
    fun dataSourceWith(headers: Map<String, String>) {
        headerHttp.headers = headers
    }
    // Wall-clock, not a coroutine: the listener below is called on the main
    // thread and must not start work of its own.
    var stalledSince by remember { mutableLongStateOf(0L) }

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
                val cause = error.errorCodeName + ": " +
                    (error.cause?.message ?: error.localizedMessage ?: "unknown")
                showError.value(cause.take(200))
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Anything that leaves the player buffering for this long while
                // the user is watching is a stall, not a slow network.
                stalledSince = if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    System.currentTimeMillis()
                } else 0L
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
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                statsDropped = droppedFrames
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
    DisposableEffect(player, menuOpen, showUI, controlFocused, scrubberFocused) {
        fun playing() = player.playbackState != androidx.media3.common.Player.STATE_IDLE

        fun seekBy(ms: Long): Boolean {
            if (!playing()) return false
            // TV remotes emit repeated key events in bursts. Coalesce them so
            // the decoder performs one seek instead of thrashing on every key.
            // The accumulator lives in `seekCoalescer` (remember-scoped), not
            // in this effect: the effect is re-keyed the moment the UI shows
            // and anything owned by it would be cancelled mid-seek.
            val c = seekCoalescer
            c.pendingMs = (c.pendingMs + ms).coerceIn(-10 * 60_000L, 10 * 60_000L)
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
            touch()
            return true
        }

        val onKey: (Int) -> Boolean = { key ->
            when (key) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                -> {
                    if (!showUI) {
                        // Hidden: the first press only reveals the UI, so an
                        // accidental OK does not pause what is being watched.
                        touch()
                        true
                    } else if (menuOpen || controlFocused) {
                        // A button owns this press.
                        false
                    } else {
                        // Controls up, focus not on one of them (scrubber,
                        // nowhere): OK stays the universal play/pause.
                        if (player.isPlaying) player.pause() else player.play()
                        isPlayingNow = !isPlayingNow
                        touch()
                        true
                    }
                }

                // Menu open means focus is on the panel, and LEFT/RIGHT are
                // spatial navigation between its buttons there. Stepping the play
                // head instead would fight the D-pad the user is actually using.
                android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                    when {
                        !showUI -> seekBy(-SEEK_STEP_MS) || true
                        menuOpen -> false
                        controlFocused -> false
                        else -> seekBy(-SEEK_STEP_MS)
                    }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                    when {
                        !showUI -> seekBy(SEEK_STEP_MS) || true
                        menuOpen -> false
                        controlFocused -> false
                        else -> seekBy(SEEK_STEP_MS)
                    }
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    // Any D-pad press shows UI if hidden
                    if (!showUI) {
                        touch()
                        true
                    } else {
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
                        // Standard media convention: restart the current item.
                        player.seekTo(0)
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

    DisposableEffect(Unit) {
        PlayerKeyBus.onBack = {
            when {
                menuOpen -> {
                    menuOpen = false
                    touch()
                    true
                }
                autoNextLeft != null -> {
                    autoNextDismissed = true
                    autoNextLeft = null
                    true
                }
                showUI -> {
                    // With controls visible, BACK has the standard TV meaning:
                    // leave playback. When controls are hidden, the first BACK
                    // reveals them so an accidental press is recoverable.
                    onBack()
                    true
                }
                else -> {
                    // Give the user one visible, predictable step before exit.
                    touch()
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
    // 5s progress save. 200ms keeps the scrubber from visibly stepping while
    // staying far below one frame of a remote's repeat rate.
    LaunchedEffect(Unit) {
        var lastSave = 0L
        while (isActive) {
            delay(UI_TICK_MS)
            positionMs = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            if (d > 0) durationMs = d
            bufferedMs = player.bufferedPosition
            isPlayingNow = player.isPlaying
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
            val streaming = PlaybackSession.episodes.isNotEmpty()
            if (streaming && positionMs - lastSave > 5_000 && durationMs > 0) {
                lastSave = positionMs
                saveProgress(positionMs, durationMs)
            }
        }
    }

    when (val s = state) {
        is UiState.Loading -> LoadingScreen(Modifier.fillMaxSize())
        is UiState.Error -> WafError(
            message = s.message,
            wafUrl = wafUrl,
            onRetry = vm::load,
            onSolveWaf = onSolveWaf,
            modifier = Modifier.fillMaxSize(),
        )
        is UiState.Success -> {
            val streams = s.data
            if (streams.isEmpty()) {
                ErrorScreen("No streams available", vm::load, Modifier.fillMaxSize())
                return
            }
            val sel = streams.getOrNull(vm.selectedIndex) ?: streams[0]

            // External-player setting (mirrors web useExternalPlayer).
            LaunchedEffect(sel.link) {
                val ext = ServiceLocator.settings.useExternalPlayer.first()
                if (ext && sel.link.isNotBlank()) {
                    openExternal(ctx, sel)
                    onBack()
                }
            }

            // Rebuild key bumps when external subs are added (position kept).
            var subsRev by remember(sel.link) { mutableStateOf(PlaybackSession.subsRevision) }
            // Two provider servers can legally expose the same URL with
            // different cookies/referer/token headers. Key on headers too, or
            // switching servers appears to do nothing and keeps the old auth.
            LaunchedEffect(sel.link, sel.headers, subsRev) {
                if (sel.link.isBlank()) return@LaunchedEffect
                runCatching {
                    dataSourceWith(sel.headers)
                    val keep = if (player.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                        PlaybackSession.resumePositionMs.also { PlaybackSession.resumePositionMs = 0L }
                    } else player.currentPosition
                    val subs = sel.subtitles.mapNotNull { t ->
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
                    val item = MediaItem.Builder().setUri(sel.link)
                        .setSubtitleConfigurations(subs).build()
                    player.setMediaItem(item)
                    player.prepare()
                    if (keep > 5000) player.seekTo(keep)
                    player.play()
                }
            }

            // A stall past the point where the user has watch a spinner of TV
            // static is a slow server, and saying so is the only way they learn
            // there is another one to try.
            var bufferingFor by remember(sel.link) { mutableLongStateOf(0L) }
            LaunchedEffect(stalledSince, sel.link) {
                bufferingFor = if (stalledSince == 0L) 0L
                else {
                    while (stalledSince != 0L) {
                        delay(1000)
                        bufferingFor = (System.currentTimeMillis() - stalledSince) / 1000
                    }
                    0L
                }
            }
            val skips = remember(sel) { collectSkips(sel) }
            val hasNext =
                PlaybackSession.startIndex + 1 < PlaybackSession.episodes.size
            val showNext = hasNext && durationMs > 0 && positionMs.toFloat() / durationMs > 0.8f

            PlayerChrome(
                player = player,
                trackSelector = trackSelector,
                playError = playError,
                streams = streams,
                selectedIndex = vm.selectedIndex,
                onServerSelect = {
                    vm.selectedIndex = it
                    playError = null
                    touch()
                },
                title = PlaybackSession.title.ifBlank { PlaybackSession.pendingTitle },
                episodeLabel = PlaybackSession.episodes
                    .getOrNull(PlaybackSession.startIndex)
                    ?.let { episodeSubtitle(it) }
                    .orEmpty(),
                serverLabel = streamLabel(sel),
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
                onToggleStats = { statsOpen = !statsOpen; touch() },
                hasNext = hasNext,
                showNext = showNext,
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
                    val d = player.duration
                    val target = (player.currentPosition + ms)
                        .coerceIn(0L, if (d > 0) d else Long.MAX_VALUE)
                    player.seekTo(target)
                    seekOsd = SeekOsd(if (ms < 0) -1 else 1, target, ms)
                    touch()
                },
                onSeekOsd = seekOsd,
                onNext = { vm.next() },
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
                    // Re-resolve the source: signed URLs and challenge
                    // cookies commonly expire, so preparing the same URL
                    // would repeat a 403 forever.
                    vm.load()
                },
                onSwitchServer = {
                    playError = null
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
                modifier = Modifier.fillMaxSize(),
            )

            // Auto-next: only when the episode really is finishing. Dismissal is
            // per-episode, so a rewind after "Next episode now" does not restart
            // the countdown the user just said no to.
            LaunchedEffect(sel.link) { autoNextDismissed = false }
            LaunchedEffect(hasNext, autoNextDismissed, menuOpen) {
                while (isActive) {
                    delay(1000)
                    val d = player.duration
                    // `playWhenReady` rather than `isPlaying`: a video that has
                    // just reached its end reports isPlaying == false, and that
                    // is exactly the moment the next episode is wanted.
                    val finishing = hasNext && !autoNextDismissed && !menuOpen && d > 0 &&
                        player.playWhenReady &&
                        player.playbackState != androidx.media3.common.Player.STATE_IDLE &&
                        player.currentPosition >= d * AUTO_NEXT_FRACTION
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
    streams: List<VegaStream>,
    selectedIndex: Int,
    onServerSelect: (Int) -> Unit,
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
    onToggleStats: () -> Unit,
    hasNext: Boolean,
    showNext: Boolean,
    autoNextLeft: Int?,
    autoNextTitle: String,
    onPlayPause: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekOsd: SeekOsd?,
    onNext: () -> Unit,
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

    // How many control regions (top bar, center row, action row) hold focus.
    // A counter, not a boolean: while focus moves between regions, one fires
    // "lost" and another fires "gained", and only the running total is stable.
    var controlFocusCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(showUI, menuOpen) {
        if (showUI && !menuOpen) {
            runCatching { playFocus.requestFocus() }
        } else {
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
                message = playError,
                onRetry = onRetryError,
                onServer = onSwitchServer,
                onClose = onExit,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
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
        if (isBuffering) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BufferingSpinner()
                if (bufferingSlow && showUI) {
                    Text(
                        "This server is slow — MENU ▸ Server to try another",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xCCFFFFFF),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }

        // Skip stays reachable while the chrome is hidden: an intro the viewer
        // has already seen once is the one control they want without pressing
        // anything to wake the screen up.
        val activeSkip = skips.firstOrNull { positionMs >= it.fromMs && positionMs < it.toMs }
        if (activeSkip != null && !menuOpen) {
            Button(
                onClick = { player.seekTo(activeSkip.toMs); onTouch() },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 28.dp, bottom = if (showUI) 150.dp else 28.dp),
            ) { Text("Skip ${activeSkip.title.ifBlank { "Intro" }}".trim()) }
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
                modifier = Modifier.align(Alignment.BottomStart)
                    .padding(start = 28.dp, bottom = if (showUI) 150.dp else 24.dp),
            )
        }

        if (autoNextLeft != null && !menuOpen) {
            AutoNextOverlay(
                secondsLeft = autoNextLeft,
                title = autoNextTitle.ifBlank { "Next episode" },
                onPlayNow = { onNext() },
                onCancel = onCancelAutoNext,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (showUI && !menuOpen && autoNextLeft == null) {
            PlayerTopBar(
                onExit = onExit,
                title = title,
                episodeLabel = episodeLabel,
                serverLabel = serverLabel,
                modifier = Modifier.align(Alignment.TopCenter)
                    .onFocusEvent { trackControlFocus(it.isFocused) },
            )
            PlayerCenterControls(
                isPlaying = isPlaying,
                onPlayPause = onPlayPause,
                onSeekBy = onSeekBy,
                playFocus = playFocus,
                modifier = Modifier.align(Alignment.Center)
                    .onFocusEvent { trackControlFocus(it.isFocused) },
            )
            PlayerBottomControls(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                skips = skips,
                speed = speed,
                serverLabel = serverLabel,
                hasStreams = streams.isNotEmpty(),
                resizeMode = resizeMode,
                hasNext = hasNext,
                showNext = showNext,
                onPlayPause = onPlayPause,
                onOpenMenu = { tab -> if (tab >= 0) onMenuTab(tab); onMenuOpenChange(true) },
                onResizeToggle = onResizeToggle,
                onNext = onNext,
                onScrubberFocus = onScrubberFocus,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .onFocusEvent { trackControlFocus(it.isFocused) },
            )
        }

        if (menuOpen) {
            MenuPanel(
                streams = streams,
                selected = selectedIndex,
                tab = menuTab,
                onTab = onMenuTab,
                onServer = onServerSelect,
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
                onSubAdded = onSubAdded,
                onClose = { onMenuOpenChange(false) },
                onExit = onExit,
                firstTabFocus = menuFocus,
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp),
            )
        }

        // The seek overlay speaks for a jump the play head has not made yet.
        onSeekOsd?.let { osd ->
            SeekOsdBadge(osd = osd, modifier = Modifier.align(Alignment.Center).padding(top = 40.dp))
        }
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
    serverLabel: String,
    hasStreams: Boolean,
    resizeMode: Int,
    hasNext: Boolean,
    showNext: Boolean,
    onPlayPause: () -> Unit,
    onOpenMenu: (Int) -> Unit,
    onResizeToggle: () -> Unit,
    onNext: () -> Unit,
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
            onTogglePlay = onPlayPause,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerActionButton(
                icon = Icons.Filled.Audiotrack,
                label = "Audio",
                onClick = { onOpenMenu(1) },
            )
            PlayerActionButton(
                icon = Icons.Filled.Subtitles,
                label = "Subs",
                onClick = { onOpenMenu(2) },
            )
            PlayerActionButton(
                icon = Icons.Filled.Speed,
                label = formatSpeed(speed),
                onClick = { onOpenMenu(3) },
            )
            if (hasStreams) {
                PlayerActionButton(
                    icon = Icons.Filled.HighQuality,
                    label = serverLabel,
                    onClick = { onOpenMenu(0) },
                )
            }
            PlayerActionButton(
                icon = Icons.Filled.AspectRatio,
                label = resizeLabel(resizeMode),
                onClick = onResizeToggle,
            )
            PlayerActionButton(
                icon = Icons.Filled.MoreVert,
                label = "More",
                // Opens the panel where it was last left rather than always
                // dumping the viewer on the server list.
                onClick = { onOpenMenu(-1) },
            )
            Spacer(Modifier.weight(1f))
            if (showNext && hasNext) {
                Button(
                    onClick = onNext,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Next episode")
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
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasDuration = durationMs > 0
    val playFraction = if (hasDuration) {
        (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f
    val bufferFraction = if (hasDuration) {
        (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    } else 0f

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(26.dp),
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
            skips.forEach { skip ->
                val f = if (hasDuration) {
                    (skip.fromMs.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f
                Box(
                    Modifier.fillMaxWidth(f.coerceAtLeast(MIN_TRACK)).fillMaxHeight()
                        .wrapContentWidth(Alignment.End)
                        .wrapContentHeight(Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier.width(3.dp).height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFFFC46B)),
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth(playFraction.coerceAtLeast(MIN_TRACK)).fillMaxHeight()
                    .wrapContentWidth(Alignment.End)
                    .wrapContentHeight(Alignment.CenterVertically),
            ) {
                // The thumb is the focus target; its click is play/pause, which
                // is what OK means everywhere else on this screen.
                Card(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(16.dp).onFocusEvent {
                        onScrubberFocus(it.isFocused)
                    },
                    shape = CardDefaults.shape(shape = CircleShape),
                    colors = CardDefaults.colors(
                        containerColor = VegaPrimary,
                        contentColor = Color.White,
                    ),
                    scale = CardDefaults.scale(focusedScale = 1.6f),
                ) {}
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

/**
 * Buffering indicator.
 *
 * tv-material ships no progress indicator, and a Compose `CircularProgress-
 * Indicator` is a Material-widget dependency this app does not take. One
 * rotating arc, drawn on a 48dp canvas, is the whole thing — one layer, and it
 * costs nothing on a weak GPU.
 */
@Composable
private fun BufferingSpinner(modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(16)
            angle = (angle + 6f) % 360f
        }
    }
    Canvas(modifier.size(48.dp)) {
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
            "Playback failed",
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
            Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Retry") }
            Button(onClick = onServer) { Text("Try another server") }
            Button(onClick = onClose) { Text("Back") }
        }
    }
}

/**
 * MENU panel: servers, audio, subtitles, speed, video track, online subtitle
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
    onSubAdded: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit,
    firstTabFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf("Server", "Audio", "Subs", "Speed", "Video", "Find")
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEachIndexed { i, t ->
                Button(
                    onClick = { onTab(i) },
                    modifier = if (i == 0) Modifier.focusRequester(firstTabFocus) else Modifier,
                ) {
                    Text((if (i == tab) "• " else "") + t)
                }
            }
        }
        when (tab) {
            0 -> {
                LazyColumn(Modifier.height(280.dp)) {
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
            1 -> AudioList(player, trackSelector)
            2 -> SubList(player, trackSelector, onSubAdded)
            3 -> SpeedAndFps(speed = speed, onSpeed = onSpeed)
            4 -> VideoList(player, trackSelector)
            5 -> SubFind(onSubAdded = onSubAdded)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onResize) { Text("Resize: ${resizeLabel(resizeMode)}") }
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

/** Alternate video tracks (renditions), when the source really has several. */
@Composable
private fun VideoList(player: ExoPlayer, trackSelector: DefaultTrackSelector) {
    val groups = remember(player.currentTracks) {
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    }
    if (groups.isEmpty()) {
        Text(
            "No alternate video tracks",
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
                append(
                    if (f.height > 0) "${f.height}p" else f.label ?: "Track ${i + 1}",
                )
                if (f.bitrate > 0) append(" · ${f.bitrate / 1000} kbps")
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
    fun shift(delta: Long) {
        val ok = applySubDelay(delta) { onSubAdded() }
        delayMs = PlaybackSession.subDelayMs
        delayNote = if (ok) null else "This subtitle format can't shift"
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

/** "Episode 4" / "Episode 4 · The Heist" — never a provider file name. */
private fun episodeSubtitle(ep: tv.vega.tv.data.model.EpisodeItem): String {
    val label = tv.vega.tv.ui.common.episodeLabel(ep)
    if (label.startsWith("Episode")) return label
    return if (ep.number > 0) "Episode ${ep.number} · $label" else label
}

private fun collectSkips(sel: VegaStream): List<Skip> {
    val out = mutableListOf<Skip>()
    fun add(list: List<tv.vega.tv.data.model.SkipInterval>) {
        list.forEach {
            out.add(Skip(it.title.orEmpty(), (it.from * 1000).toLong(), (it.to * 1000).toLong()))
        }
    }
    add(sel.skip)
    val ep = PlaybackSession.episodes.getOrNull(PlaybackSession.startIndex)
    ep?.skip?.let { add(it) }
    return out.distinctBy { it.fromMs to it.toMs }.sortedBy { it.fromMs }
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

private suspend fun saveProgress(pos: Long, dur: Long) {
    runCatching {
        val ep = PlaybackSession.episodes.getOrNull(PlaybackSession.startIndex)
        ServiceLocator.history.upsert(
            WatchEntry(
                mediaId = (ep?.link ?: PlaybackSession.pendingLink).ifBlank { return },
                title = PlaybackSession.title.ifBlank { PlaybackSession.pendingTitle },
                episodeTitle = ep?.title.orEmpty(),
                posterUrl = PlaybackSession.posterUrl.ifBlank { PlaybackSession.pendingPoster },
                positionMs = pos, durationMs = dur, updatedAt = System.currentTimeMillis(),
                link = PlaybackSession.pendingLink,
                provider = ServiceLocator.providers.active()?.ext?.value.orEmpty(),
                episodeIndex = PlaybackSession.startIndex,
            ),
        )
        ServiceLocator.syncSoon()
    }
}

private fun saveProgressSync(pos: Long, dur: Long) {
    // Fire-and-forget from onDispose (no scope): use a daemon thread.
    Thread {
        runCatching {
            kotlinx.coroutines.runBlocking { saveProgress(pos, dur) }
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
