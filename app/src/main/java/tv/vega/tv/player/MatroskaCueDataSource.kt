package tv.vega.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes Matroska files with a chained SeekHead seekable by Media3.
 *
 * Media3's MatroskaExtractor follows a Cues entry from the first SeekHead, but
 * it does not follow a SeekHead entry that points to a *second* SeekHead. Some
 * muxers write the Cues entry only in that second SeekHead, and the extractor
 * then declares the stream unseekable at the first cluster. ExoPlayer answers
 * a seek on an unseekable item by restarting at 0:00 — the exact symptom this
 * wrapper removes.
 *
 * A small bounded range read walks the SeekHead chain and finds the Cues
 * position. The bytes handed to Media3 are then rewritten so the first
 * chained entry reads as a direct Cues entry with that position. The source
 * file and the HTTP response are never modified; no-op for everything that is
 * not Matroska. Ported from the mobile app's `ChainedMatroskaDataSource`, which
 * ships the same fix on top of react-native-video.
 */
class MatroskaCueDataSource private constructor(
    private val upstreamFactory: DataSource.Factory,
    private val owner: Factory,
) : DataSource {

    private val upstream: DataSource = upstreamFactory.createDataSource()
    private var patch: Patch = Patch.NONE
    private var readPosition: Long = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        readPosition = dataSpec.position
        patch = if (owner.treatAsMatroska || isMatroska(dataSpec.uri)) {
            patchFor(dataSpec)
        } else {
            Patch.NONE
        }
        return upstream.open(dataSpec)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead == C.RESULT_END_OF_INPUT) return bytesRead
        applyPatch(buffer, offset, bytesRead, readPosition, patch.seekIdOffset, patch.seekIdBytes)
        applyPatch(
            buffer,
            offset,
            bytesRead,
            readPosition,
            patch.seekPositionOffset,
            patch.seekPositionBytes,
        )
        readPosition += bytesRead
        return bytesRead
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        try {
            upstream.close()
        } finally {
            patch = Patch.NONE
            readPosition = 0
        }
    }

    /**
     * Discovery costs one range request for the header and at most
     * [MAX_SEEK_HEAD_HOPS] small reads. It runs once per source URL; every
     * later `open()` reuses the cached answer. A failed probe must never fail
     * playback, so any exception simply means "leave the bytes alone".
     */
    private fun patchFor(sourceSpec: DataSpec): Patch {
        val cacheKey = sourceSpec.uri.toString()
        PATCH_CACHE[cacheKey]?.let { return it }
        val discovered = try {
            discoverPatch(sourceSpec)
        } catch (_: Exception) {
            Patch.NONE
        }
        if (PATCH_CACHE.size >= MAX_CACHE_ENTRIES) PATCH_CACHE.clear()
        PATCH_CACHE[cacheKey] = discovered
        return discovered
    }

    @Throws(IOException::class)
    private fun discoverPatch(sourceSpec: DataSpec): Patch {
        val header = readRange(sourceSpec, 0, PROBE_LENGTH)
        val segmentIdOffset = indexOf(header, SEGMENT_ID, 0, header.size)
        if (segmentIdOffset < 0) return Patch.NONE

        val segmentSizeOffset = segmentIdOffset + SEGMENT_ID.size
        val segmentSizeLength = vintLength(header, segmentSizeOffset)
        if (segmentSizeLength < 1) return Patch.NONE
        val segmentContentOffset = segmentSizeOffset.toLong() + segmentSizeLength

        // The first SeekEntry whose SeekID is another SeekHead. That entry is
        // what Media3 ignores, and the bytes that will be rewritten.
        val chainedSeekIdOffset = indexOf(header, SEEK_ID_FOR_SEEK_HEAD, 0, header.size)
        if (chainedSeekIdOffset < 0) return Patch.NONE

        val chainedPosition = findUnsignedElement(
            header,
            chainedSeekIdOffset + SEEK_ID_FOR_SEEK_HEAD.size,
            minOf(header.size, chainedSeekIdOffset + SEEK_ENTRY_SCAN_LENGTH),
            SEEK_POSITION_ID,
        ) ?: return Patch.NONE

        val cuesPosition = findChainedCues(sourceSpec, segmentContentOffset, chainedPosition.value)
            ?: return Patch.NONE
        if (!fitsUnsigned(cuesPosition.value, chainedPosition.payloadLength)) return Patch.NONE

        // SeekID is `0x53 0xAB 0x84` + a 4-byte element ID; SeekPosition keeps
        // its own width, so the surrounding EBML sizes stay untouched.
        return Patch(
            seekIdOffset = (chainedSeekIdOffset + SEEK_ID_HEADER_SIZE).toLong(),
            seekIdBytes = CUES_ID,
            seekPositionOffset = chainedPosition.payloadOffset,
            seekPositionBytes = encodeUnsigned(cuesPosition.value, chainedPosition.payloadLength),
        )
    }

    /**
     * Follows SeekHead -> SeekHead entries until one lists Cues, and returns
     * that entry's SeekPosition. Values are relative to the Segment body, which
     * is exactly what Media3 puts in `cuesContentPosition`.
     */
    @Throws(IOException::class)
    private fun findChainedCues(
        sourceSpec: DataSpec,
        segmentContentOffset: Long,
        startRelativePosition: Long,
    ): UnsignedElement? {
        // A seekable stream needs a server that honours Range: without it,
        // following a SeekHead stored near the end of the file would make the
        // data source read (and discard) the whole film. A one-byte request is
        // cheap proof either way.
        if (!supportsRanges(sourceSpec)) return null
        val visited = HashSet<Long>()
        var relativePosition = startRelativePosition
        repeat(MAX_SEEK_HEAD_HOPS) {
            if (relativePosition < 0 ||
                relativePosition > Long.MAX_VALUE - segmentContentOffset ||
                !visited.add(relativePosition)
            ) {
                return null
            }
            val seekHead = readRange(
                sourceSpec,
                segmentContentOffset + relativePosition,
                PROBE_LENGTH,
            )
            if (indexOf(seekHead, SEEK_HEAD_ID, 0, SEEK_HEAD_ID.size) != 0) return null

            val sizeOffset = SEEK_HEAD_ID.size
            val sizeLength = vintLength(seekHead, sizeOffset)
            if (sizeLength < 1) return null
            val contentOffset = sizeOffset + sizeLength
            val contentLength = decodeVint(seekHead, sizeOffset, sizeLength, removeLengthMask = true)
            if (contentLength < 0) return null
            val end = minOf(seekHead.size.toLong(), contentOffset + contentLength).toInt()

            val cuesId = indexOf(seekHead, SEEK_ID_FOR_CUES, contentOffset, end)
            if (cuesId >= 0) {
                return findUnsignedElement(
                    seekHead,
                    cuesId + SEEK_ID_FOR_CUES.size,
                    minOf(end, cuesId + SEEK_ENTRY_SCAN_LENGTH),
                    SEEK_POSITION_ID,
                )
            }

            val nextId = indexOf(seekHead, SEEK_ID_FOR_SEEK_HEAD, contentOffset, end)
            if (nextId < 0) return null
            val nextPosition = findUnsignedElement(
                seekHead,
                nextId + SEEK_ID_FOR_SEEK_HEAD.size,
                minOf(end, nextId + SEEK_ENTRY_SCAN_LENGTH),
                SEEK_POSITION_ID,
            ) ?: return null
            relativePosition = nextPosition.value
        }
        return null
    }

    /**
     * Whether the server answers a genuine byte range with a partial response.
     * A server that ignores Range returns 200 and OkHttpDataSource skips to the
     * position by reading and discarding bytes — harmless for a one-byte probe,
     * disastrous for a SeekHead at the far end of a film.
     */
    private fun supportsRanges(sourceSpec: DataSpec): Boolean {
        val probeSpec = sourceSpec.buildUpon()
            .setPosition(1)
            .setLength(1)
            .build()
        val probe = upstreamFactory.createDataSource()
        return try {
            probe.open(probeSpec)
            val headers = probe.responseHeaders
            val contentRange = headers.keys.any { it.equals("Content-Range", ignoreCase = true) }
            val acceptRanges = headers.entries
                .firstOrNull { it.key.equals("Accept-Ranges", ignoreCase = true) }
                ?.value
                ?.any { it.trim().equals("bytes", ignoreCase = true) } == true
            contentRange || acceptRanges
        } catch (_: Exception) {
            false
        } finally {
            runCatching { probe.close() }
        }
    }

    @Throws(IOException::class)
    private fun readRange(sourceSpec: DataSpec, position: Long, requestedLength: Int): ByteArray {
        val output = ByteArrayOutputStream(requestedLength)
        val probeSpec = sourceSpec.buildUpon()
            .setPosition(position)
            .setLength(requestedLength.toLong())
            .build()
        val probe = upstreamFactory.createDataSource()
        try {
            probe.open(probeSpec)
            val chunk = ByteArray(8 * 1024)
            var remaining = requestedLength
            while (remaining > 0) {
                val count = probe.read(chunk, 0, minOf(chunk.size, remaining))
                if (count <= 0) break
                output.write(chunk, 0, count)
                remaining -= count
            }
        } finally {
            // A probe is best-effort: a close failure must not turn a working
            // stream into a playback error.
            runCatching { probe.close() }
        }
        return output.toByteArray()
    }

    private class UnsignedElement(
        val payloadOffset: Long,
        val payloadLength: Int,
        val value: Long,
    )

    private class Patch(
        val seekIdOffset: Long,
        val seekIdBytes: ByteArray?,
        val seekPositionOffset: Long,
        val seekPositionBytes: ByteArray?,
    ) {
        companion object {
            val NONE = Patch(-1, null, -1, null)
        }
    }

    /**
     * Set [treatAsMatroska] from the provider's declared stream type before
     * the player prepares; the URI heuristic then only has to cover streams
     * whose type is missing but whose URL clearly names an .mkv file.
     */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        @Volatile
        var treatAsMatroska: Boolean = false

        override fun createDataSource(): DataSource =
            MatroskaCueDataSource(upstreamFactory, this)
    }

    private companion object {
        const val PROBE_LENGTH = 64 * 1024
        const val SEEK_ENTRY_SCAN_LENGTH = 96
        const val MAX_SEEK_HEAD_HOPS = 8
        const val MAX_CACHE_ENTRIES = 64
        const val SEEK_ID_HEADER_SIZE = 3

        val SEEK_HEAD_ID = bytes(0x11, 0x4D, 0x9B, 0x74)
        val SEGMENT_ID = bytes(0x18, 0x53, 0x80, 0x67)
        val SEEK_ID_FOR_SEEK_HEAD = bytes(0x53, 0xAB, 0x84, 0x11, 0x4D, 0x9B, 0x74)
        val SEEK_ID_FOR_CUES = bytes(0x53, 0xAB, 0x84, 0x1C, 0x53, 0xBB, 0x6B)
        val SEEK_POSITION_ID = bytes(0x53, 0xAC)
        val CUES_ID = bytes(0x1C, 0x53, 0xBB, 0x6B)

        val PATCH_CACHE = ConcurrentHashMap<String, Patch>()

        val MKV_IN_QUERY = Regex("(?i)\\.mkv(?:\$|[\"';&#\\s])")

        fun isMatroska(uri: Uri): Boolean {
            val path = uri.path
            if (path != null && path.lowercase(Locale.US).endsWith(".mkv")) return true
            // Signed object URLs often carry the filename only in a query
            // parameter or Content-Disposition; the query is all we can read.
            val query = uri.encodedQuery ?: return false
            return MKV_IN_QUERY.containsMatchIn(Uri.decode(query))
        }

        fun applyPatch(
            buffer: ByteArray,
            bufferOffset: Int,
            bytesRead: Int,
            absoluteReadOffset: Long,
            patchOffset: Long,
            replacement: ByteArray?,
        ) {
            if (patchOffset < 0 || replacement == null || replacement.isEmpty()) return
            val readEnd = absoluteReadOffset + bytesRead
            val patchEnd = patchOffset + replacement.size
            val overlapStart = maxOf(absoluteReadOffset, patchOffset)
            val overlapEnd = minOf(readEnd, patchEnd)
            if (overlapStart >= overlapEnd) return

            val sourceOffset = (overlapStart - patchOffset).toInt()
            val destinationOffset = bufferOffset + (overlapStart - absoluteReadOffset).toInt()
            val count = (overlapEnd - overlapStart).toInt()
            replacement.copyInto(
                destination = buffer,
                destinationOffset = destinationOffset,
                startIndex = sourceOffset,
                endIndex = sourceOffset + count,
            )
        }

        fun findUnsignedElement(
            data: ByteArray,
            start: Int,
            end: Int,
            elementId: ByteArray,
        ): UnsignedElement? {
            val idOffset = indexOf(data, elementId, start, end)
            if (idOffset < 0) return null

            val sizeOffset = idOffset + elementId.size
            val sizeLength = vintLength(data, sizeOffset)
            if (sizeLength < 1) return null
            val payloadLengthValue = decodeVint(data, sizeOffset, sizeLength, removeLengthMask = true)
            if (payloadLengthValue < 1 || payloadLengthValue > 8) return null

            val payloadLength = payloadLengthValue.toInt()
            val payloadOffset = sizeOffset + sizeLength
            if (payloadOffset + payloadLength > data.size) return null
            return UnsignedElement(
                payloadOffset.toLong(),
                payloadLength,
                decodeUnsigned(data, payloadOffset, payloadLength),
            )
        }

        fun vintLength(data: ByteArray, offset: Int): Int {
            if (offset < 0 || offset >= data.size || data[offset] == 0.toByte()) return -1
            var mask = 0x80
            for (length in 1..8) {
                if (data[offset].toInt() and mask != 0) {
                    return if (offset + length <= data.size) length else -1
                }
                mask = mask shr 1
            }
            return -1
        }

        fun decodeVint(data: ByteArray, offset: Int, length: Int, removeLengthMask: Boolean): Long {
            var value = data[offset].toLong() and 0xFFL
            if (removeLengthMask) value = value and (0x80L shr (length - 1)).inv()
            for (i in 1 until length) {
                value = (value shl 8) or (data[offset + i].toLong() and 0xFFL)
            }
            return value
        }

        fun decodeUnsigned(data: ByteArray, offset: Int, length: Int): Long {
            var value = 0L
            for (i in 0 until length) {
                value = (value shl 8) or (data[offset + i].toLong() and 0xFFL)
            }
            return value
        }

        fun fitsUnsigned(value: Long, byteCount: Int): Boolean {
            if (value < 0 || byteCount !in 1..8) return false
            return byteCount == 8 || value < (1L shl (byteCount * 8))
        }

        fun encodeUnsigned(value: Long, byteCount: Int): ByteArray {
            val result = ByteArray(byteCount)
            var remaining = value
            for (i in byteCount - 1 downTo 0) {
                result[i] = (remaining and 0xFF).toByte()
                remaining = remaining ushr 8
            }
            return result
        }

        fun indexOf(data: ByteArray, pattern: ByteArray, start: Int, end: Int): Int {
            val lastStart = minOf(end, data.size) - pattern.size
            var i = maxOf(0, start)
            while (i <= lastStart) {
                var j = 0
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
                i++
            }
            return -1
        }

        fun bytes(vararg values: Int): ByteArray =
            ByteArray(values.size) { values[it].toByte() }
    }
}
