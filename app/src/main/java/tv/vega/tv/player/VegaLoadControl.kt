package tv.vega.tv.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Load control modelled on the official client's `RNVLoadControl`.
 *
 * Media3's stock [DefaultLoadControl] only knows time thresholds, so a
 * high-bitrate film can fill a device-sized byte budget long before its 8 s
 * minimum. The official client therefore adds two byte gates on top of the
 * stock time policy:
 *
 *  - stop once the allocator holds the device-sized budget and more than five
 *    seconds are buffered, so the loader never quits with almost nothing;
 *  - stop when the process is nearly out of heap and more than two seconds are
 *    buffered, so a low-RAM box keeps a short runway instead of dying.
 *
 * Both floors exist for the same reason: a byte cap that stops the loader with
 * an empty buffer turns a memory limit into a stall. Five seconds survives
 * normal network jitter, and the heap gate deliberately keeps less because a
 * process under memory pressure has a more urgent problem than jitter.
 *
 * `getTotalBytesAllocated()` counts the allocations the player currently holds.
 * `DefaultAllocator.release()` decrements that count as playback consumes, so
 * both gates re-open as the buffer drains. They are caps, not one-way switches.
 * The budget itself is the same heap/RAM-derived value the player used before,
 * so a low-RAM stick does not start allocating like a 4K box.
 *
 * The control also publishes the selected video+audio bitrate and every real
 * network stall through [BufferSignals]. The parallel data source uses the
 * bitrate to decide whether one connection can feed the film, and a stall as a
 * last-resort signal when the container does not declare a bitrate.
 */
class VegaLoadControl(
    private val allocator: DefaultAllocator,
    private val byteBudget: Long,
    private val signals: BufferSignals,
) : DefaultLoadControl(
    allocator,
    // Java parameter order, not builder order: min and max come first. Passing
    // the builder order (playback first) trips the constructor's
    // `minBufferMs >= bufferForPlaybackMs` assertion and kills the process the
    // moment the player is built.
    /* minBufferMs = */ 8_000,
    /* maxBufferMs = */ 600_000,
    /* bufferForPlaybackMs = */ 1_500,
    /* bufferForPlaybackAfterRebufferMs = */ 3_000,
    // The byte cap lives in the override below, exactly as in the official
    // control; the stock target stays out of the way so the two policies do
    // not disagree about where the buffer ends.
    /* targetBufferBytes = */ C.LENGTH_UNSET,
    /* prioritizeTimeOverSizeThresholds = */ true,
    /* backBufferDurationMs = */ 0,
    /* retainBackBufferFromKeyframe = */ false,
) {
    private val runtime = Runtime.getRuntime()
    private var lastRebuffering = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // A transition into buffering with almost nothing buffered means the
        // network could not keep up, not that the decoder is slow. Count it so
        // the data source can switch to parallel ranges even when the file
        // does not declare a bitrate. Repeated calls while the same stall is
        // active must not count again. Initial buffering does not set
        // `rebuffering`; only a READY -> BUFFERING transition while playing
        // does, so opening a film cannot be mistaken for a stall.
        if (parameters.rebuffering && !lastRebuffering &&
            parameters.bufferedDurationUs < BufferSignals.STARVATION_BUFFER_US
        ) {
            signals.starvationCount.incrementAndGet()
        }
        lastRebuffering = parameters.rebuffering

        val bufferedMs = parameters.bufferedDurationUs / 1_000
        val allocated = allocator.totalBytesAllocated.toLong()

        // The byte cap is a read-ahead cap, not a playback floor. At 90 Mbps a
        // 64 MB budget is only ~5.7 s, so stopping exactly at the cap would
        // leave the player below its 8 s minimum. Five seconds is enough to
        // survive normal network jitter; the free-heap check below still
        // protects the process.
        if (allocated >= byteBudget && bufferedMs > MIN_BUFFER_AT_BYTE_CAP_MS) {
            return false
        }

        // Same safety net as the official control: when the app is nearly out
        // of heap, stop growing the buffer. The 2 s gate is deliberate — a
        // process under memory pressure should still keep a short runway.
        val used = runtime.totalMemory() - runtime.freeMemory()
        val free = runtime.maxMemory() - used
        val reserve = (runtime.maxMemory() * MIN_HEAP_RESERVE_FRACTION).toLong()
        if (free < reserve && bufferedMs > MIN_BUFFER_UNDER_MEMORY_PRESSURE_MS) {
            return false
        }

        // Time-oriented minimum/maximum, no byte ceiling, no back buffer.
        return super.shouldContinueLoading(parameters)
    }

    override fun onTracksSelected(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        renderers: Array<Renderer>,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection>,
    ) {
        super.onTracksSelected(
            playerId,
            timeline,
            mediaPeriodId,
            renderers,
            trackGroups,
            trackSelections,
        )

        // The data source needs the rate the film itself consumes, not a guess
        // from the file size: a 90 Mbps remux and a 2 Mbps episode need very
        // different single-connection throughput. The selection carries the
        // provider's declared bitrate; when the container has none, leave it
        // at zero and the data source falls back to its absolute floor.
        var bitsPerSecond = 0L
        // Media3 declares the parameter as ExoTrackSelection[] and its own
        // DefaultLoadControl null-checks every element, so a null entry is
        // legal even though Kotlin types the override's elements as non-null.
        // Read through a nullable view instead of trusting the Kotlin type.
        @Suppress("UNCHECKED_CAST")
        val selections = trackSelections as Array<ExoTrackSelection?>
        for (selection in selections) {
            if (selection == null) continue
            val type = selection.type
            if (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO) continue
            val format = selection.selectedFormat ?: continue
            val rate = when {
                format.bitrate > 0 -> format.bitrate
                format.averageBitrate > 0 -> format.averageBitrate
                format.peakBitrate > 0 -> format.peakBitrate
                else -> 0
            }
            if (rate > 0) bitsPerSecond += rate.toLong()
        }
        signals.selectedBitrateBitsPerSecond.set(bitsPerSecond)
    }

    private companion object {
        const val MIN_BUFFER_AT_BYTE_CAP_MS = 5_000L
        const val MIN_BUFFER_UNDER_MEMORY_PRESSURE_MS = 2_000L
        const val MIN_HEAP_RESERVE_FRACTION = 0.05
    }
}
