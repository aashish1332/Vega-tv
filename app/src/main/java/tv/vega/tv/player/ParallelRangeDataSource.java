package tv.vega.tv.player;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays large progressive HTTP files over a few parallel range requests when one connection cannot
 * carry the bitrate, for servers that limit the speed of each connection.
 *
 * <p>The source starts as a normal single request. The first four seconds of reads measure how fast
 * that connection delivers. Only when the rate is below {@link #SLOW_BYTES_PER_SECOND}, the file is
 * at least {@link #MIN_FILE_BYTES} long and the server has proven byte-range support, does it
 * switch, at the current byte, to downloading the next chunks in order over up to
 * {@link #MAX_CONNECTIONS} connections. The player still receives the bytes strictly in order.
 *
 * <p>To stay light on the server: chunks start at 1 MB so playback starts quickly, then grow to
 * 4 MB; only a small window ahead of the player is fetched, so nothing is requested while the
 * player's buffer is full; a 429 or 503 permanently lowers the connection count for that host; and
 * a server that ignores ranges, or any repeated failure, returns this source to a single normal
 * request.
 *
 * <p>Ported from the official client's {@code ParallelDataSource}. The only behavioural changes
 * are the bitrate-scale switch, the 4 GiB file gate and the smaller 3 x 4 MB window, so total
 * memory stays well inside the media buffer budget computed in {@code PlayerScreen}.
 */
public final class ParallelRangeDataSource implements DataSource {
    private static final String TAG = "VegaParallelStream";

    static final int MAX_CONNECTIONS = 3;
    /**
     * Below this a single connection is not a safe carrier for a 4K remux. 16 MB/s is 128 Mbps;
     * an 80 GB two hour film averages ~89 Mbps and peaks higher, so a connection near this rate
     * cannot keep the forward buffer full through normal jitter.
     */
    static final long SLOW_BYTES_PER_SECOND = 16_000_000L;
    /** Time this connection must spend blocked in read() before its rate is trusted. */
    private static final long MEASURE_MIN_NANOS = 4_000_000_000L;
    private static final long MEASURE_MIN_BYTES = 512 * 1024L;
    /** Only files this large are worth extra connections; excludes segments and sidecars. */
    private static final long MIN_FILE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int FIRST_CHUNK_BYTES = 1024 * 1024;
    private static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;
    /** Not worth extra connections for the last part of a file or for small reads. */
    private static final long MIN_PARALLEL_BYTES = 16 * 1024 * 1024L;
    /**
     * Until the player has read this much from an open, only two chunks run. The player also opens
     * the file to read indexes near the end, then seeks away; this keeps those opens cheap.
     */
    private static final long CONFIRM_PLAYBACK_BYTES = 512 * 1024L;
    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_CHUNK_ATTEMPTS = 3;
    private static final int MAX_REMEMBERED_URLS = 32;
    /** 429s within this time of a cut belong to the same burst and do not cut again. */
    private static final long RATE_LIMIT_BURST_MS = 3000;
    private static final int MAX_REOPEN_ATTEMPTS = 4;

    /** Per URL: switched to parallel, or found unsuitable. */
    private static final Map<String, UrlState> URL_STATES = new ConcurrentHashMap<>();
    /** Per host: connections allowed; lowered by each 429 or 503. */
    private static final Map<String, AtomicInteger> HOST_LIMITS = new ConcurrentHashMap<>();

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "VegaParallelStream");
        thread.setDaemon(true);
        return thread;
    });

    private static final class UrlState {
        volatile boolean parallel;
        volatile boolean unsuitable;
        /** The server has answered a byte range with a 206 for this file. */
        volatile boolean rangeProven;
        volatile long totalLength = C.LENGTH_UNSET;
    }

    private final DataSource.Factory upstreamFactory;
    private final DataSource single;
    private final List<TransferListener> transferListeners = new ArrayList<>();

    @Nullable private DataSpec dataSpec;
    @Nullable private Engine engine;
    private boolean singleOpen;
    private long position;
    /** Exclusive end of the open range, or LENGTH_UNSET. */
    private long endPosition;

    private boolean measuring;
    private long measuredBytes;
    private long measuredNanos;

    private ParallelRangeDataSource(DataSource.Factory upstreamFactory) {
        this.upstreamFactory = upstreamFactory;
        this.single = upstreamFactory.createDataSource();
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        transferListeners.add(transferListener);
        single.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        position = dataSpec.position;
        measuring = false;
        measuredBytes = 0;
        measuredNanos = 0;

        UrlState state = suitable(dataSpec) ? urlState(dataSpec.uri) : null;
        if (state != null && state.parallel && state.totalLength > 0) {
            long end = dataSpec.length == C.LENGTH_UNSET
                    ? state.totalLength
                    : dataSpec.position + dataSpec.length;
            int limit = hostLimit(dataSpec.uri).get();
            if (limit > 1 && end - position >= MIN_PARALLEL_BYTES) {
                endPosition = end;
                engine = new Engine(dataSpec, position, end, state);
                return end - position;
            }
        }

        // A failed open still needs close() to release the upstream's open state.
        singleOpen = true;
        long length = single.open(dataSpec);
        endPosition = length == C.LENGTH_UNSET ? C.LENGTH_UNSET : position + length;
        // Measure only an open that reads to the end of a known, genuinely large file on a
        // server that supports byte ranges, so a switch can continue exactly where it left off.
        measuring = state != null
                && dataSpec.length == C.LENGTH_UNSET
                && length != C.LENGTH_UNSET
                && length >= MIN_FILE_BYTES
                && hostLimit(dataSpec.uri).get() > 1;
        if (measuring) {
            state.totalLength = endPosition;
            if (!state.rangeProven) {
                state.rangeProven =
                        supportsRanges(single.getResponseHeaders(), position, endPosition);
            }
        }
        return length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        if (engine != null) {
            try {
                int read = engine.read(buffer, offset, length);
                if (read > 0) {
                    position += read;
                }
                return read;
            } catch (FallbackException fallback) {
                Log.i(TAG, "Back to one connection: " + fallback.getMessage());
                engine.close();
                engine = null;
                reopenSingle();
            }
        }

        // Time only what the connection spends blocked in read(): that is network time. Wall-clock
        // time would include the player's long pauses while the forward buffer is full, which
        // would report a fast link as slow. In-read time is what the official source trusts; the
        // only bias is the small amount of data the socket buffers during a pause, which makes a
        // link look faster, never slower.
        long started = measuring ? System.nanoTime() : 0;
        int read = single.read(buffer, offset, length);
        if (read > 0) {
            position += read;
            if (measuring) {
                measuredBytes += read;
            }
        }
        if (measuring && read > 0) {
            measuredNanos += System.nanoTime() - started;
            if (measuredNanos >= MEASURE_MIN_NANOS && measuredBytes >= MEASURE_MIN_BYTES) {
                measuring = false;
                maybeSwitchToParallel();
            }
        }
        return read;
    }

    private void maybeSwitchToParallel() {
        long bytesPerSecond = measuredBytes * 1_000_000_000L / Math.max(1, measuredNanos);
        DataSpec spec = dataSpec;
        if (spec == null
                || bytesPerSecond >= SLOW_BYTES_PER_SECOND
                || endPosition - position < MIN_PARALLEL_BYTES) {
            return;
        }
        UrlState state = urlState(spec.uri);
        if (!state.rangeProven && !probeRanges(spec, state)) {
            return;
        }
        state.parallel = true;
        Log.i(TAG, String.format(Locale.US,
                "One connection gives %d KB/s over %d ms; using parallel range requests",
                bytesPerSecond / 1024, measuredNanos / 1_000_000L));
        closeSingleQuietly();
        engine = new Engine(spec, position, endPosition, state);
    }

    /**
     * Proves that the server honours a byte range when the first response did not advertise it.
     *
     * <p>The probe always starts at byte 0 on purpose: Media3's HTTP source answers a 200 to a
     * non-zero range by reading and discarding every byte before the requested position, so a
     * probe at the current (possibly tens-of-GB) position could download the whole film before it
     * returned. From byte 0 that skip is zero, and a range-ignoring server costs one response.
     */
    private boolean probeRanges(DataSpec spec, UrlState state) {
        DataSource probe = upstreamFactory.createDataSource();
        try {
            probe.open(spec.buildUpon().setPosition(0).setLength(1L).build());
            long[] range = parseContentRange(probe.getResponseHeaders());
            boolean proven = range != null
                    && range[0] == 0
                    && range[1] == 0
                    && range[2] == endPosition;
            if (!proven) {
                state.unsuitable = true;
                Log.i(TAG, "Server does not honour byte ranges; keeping one connection");
            }
            return proven;
        } catch (IOException error) {
            // A transient failure must not disable parallel playback for good.
            Log.i(TAG, "Range probe failed: " + error.getMessage());
            return false;
        } finally {
            try {
                probe.close();
            } catch (IOException ignored) {
                // The probe result is already decided.
            }
        }
    }

    /** Continues the open range from the current byte over one normal request. */
    private void reopenSingle() throws IOException {
        DataSpec spec = dataSpec;
        if (spec == null) {
            throw new IOException("Source is not open");
        }
        long length = endPosition == C.LENGTH_UNSET || spec.length == C.LENGTH_UNSET
                ? C.LENGTH_UNSET
                : endPosition - position;
        closeSingleQuietly();
        DataSpec rest = spec.buildUpon().setPosition(position).setLength(length).build();
        for (int attempt = 1; ; attempt++) {
            try {
                singleOpen = true;
                single.open(rest);
                return;
            } catch (HttpDataSource.InvalidResponseCodeException e) {
                // The server may still count the parallel requests for a moment.
                if ((e.responseCode != 429 && e.responseCode != 503)
                        || attempt >= MAX_REOPEN_ATTEMPTS) {
                    throw e;
                }
                closeSingleQuietly();
                try {
                    Thread.sleep(1000L << (attempt - 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException();
                }
            }
        }
    }

    @Override
    @Nullable
    public Uri getUri() {
        return dataSpec == null ? null : dataSpec.uri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return singleOpen ? single.getResponseHeaders() : Collections.emptyMap();
    }

    @Override
    public void close() throws IOException {
        if (engine != null) {
            engine.close();
            engine = null;
        }
        dataSpec = null;
        measuring = false;
        if (singleOpen) {
            singleOpen = false;
            single.close();
        }
    }

    private void closeSingleQuietly() {
        if (!singleOpen) {
            return;
        }
        singleOpen = false;
        try {
            single.close();
        } catch (IOException ignored) {
            // The replacement request reports its own errors.
        }
    }

    private static boolean suitable(DataSpec dataSpec) {
        if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET) {
            return false;
        }
        String scheme = dataSpec.uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        if (dataSpec.length != C.LENGTH_UNSET && dataSpec.length < MIN_PARALLEL_BYTES) {
            return false;
        }
        UrlState state = URL_STATES.get(dataSpec.uri.toString());
        return state == null || !state.unsuitable;
    }

    private static UrlState urlState(Uri uri) {
        if (URL_STATES.size() > MAX_REMEMBERED_URLS) {
            URL_STATES.clear();
        }
        String key = uri.toString();
        UrlState state = URL_STATES.get(key);
        if (state == null) {
            UrlState created = new UrlState();
            UrlState existing = URL_STATES.putIfAbsent(key, created);
            state = existing == null ? created : existing;
        }
        return state;
    }

    private static AtomicInteger hostLimit(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        AtomicInteger limit = HOST_LIMITS.get(host);
        if (limit == null) {
            AtomicInteger created = new AtomicInteger(MAX_CONNECTIONS);
            AtomicInteger existing = HOST_LIMITS.putIfAbsent(host, created);
            limit = existing == null ? created : existing;
        }
        return limit;
    }

    /**
     * Lowers a host's connection limit to the number of requests still working, never below
     * {@code floor}. Written as a CAS loop because {@code AtomicInteger.updateAndGet} only exists
     * from Android 8 (API 24) and this app supports API 21.
     */
    private static int lowerHostLimit(AtomicInteger limit, int floor, int working) {
        while (true) {
            int value = limit.get();
            int next = Math.max(Math.min(floor, value), Math.min(value - 1, working));
            if (value == next || limit.compareAndSet(value, next)) {
                return next;
            }
        }
    }

    /**
     * True when a response to an open that reads to the end shows the server supports ranges: a
     * 206 for exactly the requested bytes or, for a request from byte 0 that sends no Range
     * header, Accept-Ranges: bytes. Each chunk is checked again, so a wrong answer here only costs
     * one failed chunk before falling back.
     */
    private static boolean supportsRanges(
            Map<String, List<String>> headers, long start, long end) {
        long[] range = parseContentRange(headers);
        if (range != null) {
            return range[0] == start && range[1] == end - 1 && range[2] == end;
        }
        if (start != 0) {
            return false;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().equalsIgnoreCase("Accept-Ranges")
                    && !entry.getValue().isEmpty()
                    && entry.getValue().get(0).trim().equalsIgnoreCase("bytes")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for a 206 with exactly the chunk's bytes of a file of the expected size. OkHttp hides
     * the status code, but only a 206 carries Content-Range.
     */
    private static boolean isChunkRange(
            Map<String, List<String>> headers, long start, long end, long totalLength) {
        long[] range = parseContentRange(headers);
        return range != null
                && range[0] == start
                && range[1] == end - 1
                && (totalLength <= 0 || range[2] == totalLength);
    }

    /** Returns [first, last, total] from a Content-Range header, or null. */
    @Nullable
    private static long[] parseContentRange(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null
                    || !entry.getKey().equalsIgnoreCase("Content-Range")
                    || entry.getValue().isEmpty()) {
                continue;
            }
            String value = entry.getValue().get(0).trim().toLowerCase(Locale.US);
            if (!value.startsWith("bytes ")) {
                return null;
            }
            try {
                String[] rangeAndTotal = value.substring(6).split("/");
                String[] firstAndLast = rangeAndTotal[0].split("-");
                return new long[] {
                        Long.parseLong(firstAndLast[0].trim()),
                        Long.parseLong(firstAndLast[1].trim()),
                        Long.parseLong(rangeAndTotal[1].trim()),
                };
            } catch (RuntimeException malformed) {
                return null;
            }
        }
        return null;
    }

    /** Thrown by the engine when the read should continue over one normal request. */
    private static final class FallbackException extends IOException {
        FallbackException(String message) {
            super(message);
        }
    }

    private static final class Chunk {
        final long start;
        final byte[] data;
        /** Bytes received, from the chunk start. Guarded by the engine lock. */
        int filled;
        boolean running;
        int attempts;
        long notBefore;

        Chunk(long start, int length) {
            this.start = start;
            this.data = new byte[length];
        }

        long end() {
            return start + data.length;
        }
    }

    /** Downloads the chunks just ahead of the player and hands their bytes over in order. */
    private final class Engine {
        private final Object lock = new Object();
        private final DataSpec spec;
        private final long end;
        private final UrlState state;
        private final AtomicInteger hostLimit;
        private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();

        private long readPosition;
        private long nextChunkStart;
        private int nextChunkBytes = FIRST_CHUNK_BYTES;
        private long consumed;
        private int running;
        /** Chunks whose request succeeded and that are receiving data. */
        private int streaming;
        private long lastCutAt;
        private int cuts;
        private boolean closed;
        @Nullable private String fallbackReason;

        Engine(DataSpec spec, long start, long end, UrlState state) {
            this.spec = spec;
            this.end = end;
            this.state = state;
            this.hostLimit = hostLimit(spec.uri);
            this.readPosition = start;
            this.nextChunkStart = start;
            synchronized (lock) {
                schedule();
            }
        }

        int read(byte[] buffer, int offset, int length) throws IOException {
            synchronized (lock) {
                while (true) {
                    if (closed) {
                        throw new IOException("Source closed");
                    }
                    if (readPosition >= end) {
                        return C.RESULT_END_OF_INPUT;
                    }
                    if (fallbackReason != null) {
                        throw new FallbackException(fallbackReason);
                    }
                    Chunk head = chunks.peekFirst();
                    if (head != null) {
                        int offsetInChunk = (int) (readPosition - head.start);
                        int available = head.filled - offsetInChunk;
                        if (available > 0) {
                            int count = Math.min(length, available);
                            System.arraycopy(head.data, offsetInChunk, buffer, offset, count);
                            readPosition += count;
                            consumed += count;
                            if (readPosition >= head.end()) {
                                chunks.pollFirst();
                            }
                            schedule();
                            return count;
                        }
                    }
                    schedule();
                    try {
                        lock.wait(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException();
                    }
                }
            }
        }

        void close() {
            synchronized (lock) {
                closed = true;
                chunks.clear();
                lock.notifyAll();
            }
        }

        /** Adds chunks up to the window and starts their downloads. Holds the lock. */
        private void schedule() {
            if (closed || fallbackReason != null) {
                return;
            }
            int limit = Math.min(MAX_CONNECTIONS, hostLimit.get());
            int window = consumed >= CONFIRM_PLAYBACK_BYTES ? limit : Math.min(2, limit);
            while (chunks.size() < window && nextChunkStart < end) {
                int size = (int) Math.min(nextChunkBytes, end - nextChunkStart);
                chunks.addLast(new Chunk(nextChunkStart, size));
                nextChunkStart += size;
                nextChunkBytes = Math.min(MAX_CHUNK_BYTES, nextChunkBytes * 2);
            }
            long now = System.currentTimeMillis();
            for (Chunk chunk : chunks) {
                if (running >= window) {
                    break;
                }
                if (!chunk.running && chunk.filled < chunk.data.length && now >= chunk.notBefore) {
                    chunk.running = true;
                    running++;
                    WORKERS.execute(() -> download(chunk));
                }
            }
        }

        private void download(Chunk chunk) {
            DataSource source = upstreamFactory.createDataSource();
            for (TransferListener listener : transferListeners) {
                source.addTransferListener(listener);
            }
            IOException error = null;
            boolean counted = false;
            try {
                long from;
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                    from = chunk.start + chunk.filled;
                }
                source.open(spec.buildUpon()
                        .setPosition(from)
                        .setLength(chunk.end() - from)
                        .build());
                if (!isChunkRange(source.getResponseHeaders(), from, chunk.end(), state.totalLength)) {
                    // A server that ignores ranges would send the whole file for every chunk.
                    state.unsuitable = true;
                    state.parallel = false;
                    fail("server ignored the byte range");
                    return;
                }
                synchronized (lock) {
                    streaming++;
                    counted = true;
                }
                byte[] buffer = new byte[READ_BUFFER_BYTES];
                while (true) {
                    int want;
                    synchronized (lock) {
                        if (closed) {
                            return;
                        }
                        want = Math.min(buffer.length, chunk.data.length - chunk.filled);
                    }
                    if (want == 0) {
                        break;
                    }
                    int read = source.read(buffer, 0, want);
                    if (read == C.RESULT_END_OF_INPUT) {
                        throw new IOException("Range ended early");
                    }
                    synchronized (lock) {
                        if (closed) {
                            return;
                        }
                        System.arraycopy(buffer, 0, chunk.data, chunk.filled, read);
                        chunk.filled += read;
                        lock.notifyAll();
                    }
                }
            } catch (IOException e) {
                error = e;
            } finally {
                try {
                    source.close();
                } catch (IOException ignored) {
                    // The chunk result is already decided.
                }
                synchronized (lock) {
                    chunk.running = false;
                    running--;
                    if (counted) {
                        streaming--;
                    }
                    if (error != null) {
                        onChunkError(chunk, error);
                    }
                    schedule();
                    lock.notifyAll();
                }
            }
        }

        /** Holds the lock. */
        private void onChunkError(Chunk chunk, IOException error) {
            if (closed || fallbackReason != null) {
                return;
            }
            int status = error instanceof HttpDataSource.InvalidResponseCodeException
                    ? ((HttpDataSource.InvalidResponseCodeException) error).responseCode
                    : 0;
            if (status == 429 || status == 503) {
                // The server limits connections. Keep only as many as are working now, for this
                // host from now on. Several requests can be refused at once; that is one cut.
                long now = System.currentTimeMillis();
                if (now - lastCutAt >= RATE_LIMIT_BURST_MS) {
                    lastCutAt = now;
                    // Right after the switch the server may still count the single request
                    // that was just closed, so the first cut keeps two connections.
                    int floor = cuts++ == 0 ? 2 : 1;
                    int working = streaming;
                    int limit = lowerHostLimit(hostLimit, floor, working);
                    Log.i(TAG, "Server answered " + status + "; connection limit now " + limit);
                    if (limit <= 1) {
                        state.parallel = false;
                        fail("server limits connections");
                        return;
                    }
                }
                // A refused request is not a failure of the chunk; wait and try again.
                chunk.notBefore = now + 1000L * Math.min(8, 1 << Math.min(3, chunk.attempts));
                chunk.attempts = Math.min(chunk.attempts + 1, MAX_CHUNK_ATTEMPTS - 1);
                return;
            } else if (status >= 400 && status < 500) {
                fail("HTTP " + status);
                return;
            }
            chunk.attempts++;
            if (chunk.attempts >= MAX_CHUNK_ATTEMPTS) {
                fail(error.getMessage() == null ? "repeated errors" : error.getMessage());
                return;
            }
            chunk.notBefore = System.currentTimeMillis() + (1000L << chunk.attempts);
        }

        /** Holds the lock, or takes it. */
        private void fail(String reason) {
            synchronized (lock) {
                if (fallbackReason == null) {
                    fallbackReason = reason;
                }
                lock.notifyAll();
            }
        }
    }

    /** Wraps an upstream factory; each source decides per open whether to use parallel requests. */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;

        public Factory(DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new ParallelRangeDataSource(upstreamFactory);
        }
    }
}