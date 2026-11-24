package tv.vega.tv.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Load control modelled on the official client's `RNVLoadControl`.
 *
 * The stock [DefaultLoadControl] treats the byte budget as a hard ceiling: with
 * `prioritizeTimeOverSizeThresholds = false`, once the allocator reaches the
 * budget it stops feeding the player even when the buffered duration is below
 * the minimum. At 90 Mbps a 64 MB budget is only ~5.7 s, so a 4K remux hit the
 * ceiling below the 8 s minimum, the loader stopped, and playback drained the
 * buffer and stalled over and over.
 *
 * This control instead:
 *
 *  - gives time priority (`targetBufferBytes = -1`, `prioritize = true`) so
 *    Media3's own minimum is never the thing that starves playback;
 *  - stops at the byte budget only when more than [MIN_BUFFER_AT_BYTE_CAP_MS]
 *    is already buffered, so the loader never quits with almost nothing
 *    buffered;
 *  - keeps a free-heap reserve and stops early when the process is running out
 *    of memory, but again only when a few seconds are already buffered;
 *  - keeps no back buffer: it shares the allocator with the forward buffer and
 *    its cost is pure bitrate.
 *
 * The budget is the same heap/RAM-derived value the player used before, so a
 * low-RAM stick does not start allocating like a 4K box.
 */
class VegaLoadControl(
    private val allocator: DefaultAllocator,
    private val byteBudget: Long,
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
    /* targetBufferBytes = */ C.LENGTH_UNSET,
    /* prioritizeTimeOverSizeThresholds = */ true,
    /* backBufferDurationMs = */ 0,
    /* retainBackBufferFromKeyframe = */ false,
) {
    private val runtime = Runtime.getRuntime()

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
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

    private companion object {
        const val MIN_BUFFER_AT_BYTE_CAP_MS = 5_000L
        const val MIN_BUFFER_UNDER_MEMORY_PRESSURE_MS = 2_000L
        const val MIN_HEAP_RESERVE_FRACTION = 0.05
    }
}
