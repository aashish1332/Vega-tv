package tv.vega.tv.player

import java.util.concurrent.atomic.AtomicLong

/**
 * State shared between the playback thread and the loading thread.
 *
 * The load control runs on ExoPlayer's playback thread; the parallel data
 * source runs on its loading thread. Neither may block the other, so the two
 * facts they need to share are plain atomics.
 */
class BufferSignals {
    /**
     * Sum of the selected video and audio bitrates in bits per second, or 0
     * when the container declares none. Written by [VegaLoadControl] when the
     * track selection is made and read by [ParallelRangeDataSource] when it
     * decides whether one connection can feed the film.
     */
    @JvmField
    val selectedBitrateBitsPerSecond = AtomicLong(0L)

    /**
     * Incremented once per transition into a real playback stall while less
     * than [STARVATION_BUFFER_US] is buffered. A stall with a healthy buffer is
     * a decoder problem, not a network one, and must not be blamed on the
     * connection.
     */
    @JvmField
    val starvationCount = AtomicLong(0L)

    companion object {
        /** A stall below this much buffered media means the network ran dry. */
        const val STARVATION_BUFFER_US = 2_000_000L
    }
}
