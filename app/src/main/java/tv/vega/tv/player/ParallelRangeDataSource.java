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
 * Plays large progressive HTTP files over a few parallel range requests.
 *
 * <p>An 80+ GB 4K remux averages about 90 Mbps and peaks well above it. A host
 * that shapes a single connection, or one that only gives a fast link over
 * several sockets, cannot keep the player's forward buffer full from one
 * request. This source therefore opens the file once, proves byte-range
 * support, and then continues over ordered chunks on up to four connections.
 *
 * <p>Engagement is deterministic for a file whose proven size is at least
 * {@link #MIN_FILE_BYTES}: there is no speed measurement and no "only when
 * slow" gate, because both can silently keep a film on one connection (a fast
 * first response, a missing {@code Accept-Ranges} header, or a chunked
 * response). Chunks start at 1 MB so playback starts quickly, then grow to
 * 4 MB. Only a small window ahead of the player is fetched, so nothing is
 * requested while the player's buffer is full.
 *
 * <p>The single request, the range probe and every fallback use the caller's
 * normal header-aware factory. Chunks use a second factory built on an
 * HTTP/1.1-only client: concurrent requests to the same host then occupy
 * separate sockets instead of being multiplexed onto one HTTP/2 connection,
 * which is the whole point of a parallel range download.
 *
 * <p>A 429 or 503 permanently lowers the connection count for that host; a
 * server that ignores a range is marked unsuitable and the open falls back to
 * one request. Query strings are never logged.
 */
public final class ParallelRangeDataSource implements DataSource {
    private static final String TAG = "VegaParallelStream";

    static final int MAX_CONNECTIONS = 4;
    /**
     * Only files this large are supported over parallel ranges. A 4 GiB file
     * is already a full-length film; smaller opens are segments, subtitles,
     * indexes or sidecars and must stay single-request.
     */
    private static final long MIN_FILE_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int FIRST_CHUNK_BYTES = 1024 * 1024;
    private static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;
    /** Not worth extra connections for the last part of a file or for small reads. */
    private static final long MIN_PARALLEL_BYTES = 16 * 1024 * 1024L;
    /**
     * Until the player has read this much from an open, only two chunks run.
     * This keeps an index/header open cheap even when it is unbounded.
     */
    private static final long CONFIRM_PLAYBACK_BYTES = 512 * 1024L;
    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_CHUNK_ATTEMPTS = 3;
    private static final int MAX_REMEMBERED_URLS = 32;
    /** 429s within this time of a cut belong to the same burst and do not cut again. */
    private static final long RATE_LIMIT_BURST_MS = 3000;
    private static final int MAX_REOPEN_ATTEMPTS = 4;

    /** Per URL: range support proven, or found unsuitable. */
    private static final Map<String, UrlState> URL_STATES = new ConcurrentHashMap<>();
    /** Per host: connections allowed; lowered by each 429 or 503. */
    private static final Map<String, AtomicInteger> HOST_LIMITS = new ConcurrentHashMap<>();

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "VegaParallelStream");
        thread.setDaemon(true);
        return thread;
    });

    private static final class UrlState {
        volatile boolean unsuitable;
        /** The server has answered a byte range with a 206 for this file. */
        volatile boolean rangeProven;
        volatile long totalLength = C.LENGTH_UNSET;
        /** Avoids repeating the same log line on every seek within one file. */
        volatile String lastDecision = "";
    }

    private final DataSource.Factory singleFactory;
    private final DataSource.Factory chunkFactory;
    private final DataSource single;
    private final List<TransferListener> transferListeners = new ArrayList<>();

    @Nullable private DataSpec dataSpec;
    @Nullable private Engine engine;
    private boolean singleOpen;
    private long position;
    /** Exclusive end of the open range, or LENGTH_UNSET. */
    private long endPosition;
    private long totalLength = C.LENGTH_UNSET;
    /** The first response's headers, kept after the single request is closed. */
    private Map<String, List<String>> responseHeaders = Collections.emptyMap();

    private ParallelRangeDataSource(
            DataSource.Factory singleFactory, DataSource.Factory chunkFactory) {
        this.singleFactory = singleFactory;
        this.chunkFactory = chunkFactory;
        this.single = singleFactory.createDataSource();
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        transferListeners.add(transferListener);
        single.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        // Media3 closes before reopening, but a failed open or a caller that
        // abandons a source must not leave a socket or a worker behind.
        if (engine != null || singleOpen) {
            try {
                close();
            } catch (IOException ignored) {
                // The new open replaces this state entirely.
            }
        }
        this.dataSpec = dataSpec;
        position = dataSpec.position;
        endPosition = C.LENGTH_UNSET;
        totalLength = C.LENGTH_UNSET;
        responseHeaders = Collections.emptyMap();

        boolean eligible = suitable(dataSpec);
        UrlState state = eligible ? urlState(dataSpec.uri) : null;

        // One normal request discovers the size and, at position 0, costs one
        // response. It is also the source that playback falls back to.
        singleOpen = true;
        long length;
        try {
            length = single.open(dataSpec);
        } catch (IOException error) {
            singleOpen = false;
            throw error;
        }
        responseHeaders = single.getResponseHeaders();

        if (state != null) {
            length = inspectOpen(state, length);
        }
        if (totalLength > 0) {
            endPosition = dataSpec.length == C.LENGTH_UNSET
                    ? totalLength
                    : Math.min(totalLength, position + dataSpec.length);
        } else if (length != C.LENGTH_UNSET) {
            endPosition = position + length;
        }

        if (state != null && !state.unsuitable && state.rangeProven
                && state.totalLength >= MIN_FILE_BYTES) {
            long total = state.totalLength;
            long end = dataSpec.length == C.LENGTH_UNSET
                    ? total
                    : Math.min(total, position + dataSpec.length);
            int limit = hostLimit(dataSpec.uri).get();
            if (limit > 1 && end - position >= MIN_PARALLEL_BYTES) {
                endPosition = end;
                closeSingleQuietly();
                engine = new Engine(dataSpec, position, end, state);
                logDecision(state, "parallel", String.format(Locale.US,
                        "Parallel %dx range: host=%s total=%.1f GB from=%.1f GB",
                        limit, host(dataSpec.uri), total / 1e9, position / 1e9));
                return end - position;
            }
            logDecision(state, limit <= 1 ? "single-host" : "single-tail",
                    String.format(Locale.US,
                            "Single: host=%s total=%.1f GB remaining=%.1f MB limit=%d "
                                    + "(too short to parallelise)",
                            host(dataSpec.uri), total / 1e9,
                            (end - position) / 1e6, limit));
        } else if (state != null) {
            String key;
            if (state.unsuitable) {
                key = "single-norange";
            } else if (state.totalLength > 0 && state.totalLength < MIN_FILE_BYTES) {
                // Every HLS segment and every small sidecar down here would
                // otherwise log one line per URL. There is no decision to
                // report for a file that can never reach the threshold.
                return length;
            } else {
                key = "single-unknown";
            }
            logDecision(state, key, String.format(Locale.US,
                    "Single: host=%s total=%s ranges=%s from=%.1f GB "
                            + "(parallel range requests not used)",
                    host(dataSpec.uri),
                    state.totalLength > 0
                            ? String.format(Locale.US, "%.1f GB", state.totalLength / 1e9)
                            : "unknown",
                    state.rangeProven ? "proven" : "not honoured",
                    position / 1e9));
        }
        return length;
    }

    /**
     * Turns the first response into a total size and a range decision.
     *
     * <p>A 206 proves ranges and carries the total. A 200 at position 0 is the
     * normal whole-file response; a byte-0 probe then proves ranges without
     * ever asking Media3 to skip a position inside {@code open()}. A 200 at a
     * non-zero position means the server ignored the range (and Media3 has
     * already skipped those bytes), so that URL must stay single.
     *
     * @return the length to report to the caller, corrected when a 206 omitted
     *     {@code Content-Length}
     */
    private long inspectOpen(UrlState state, long length) {
        if (state.unsuitable) {
            return length;
        }
        long[] range = parseContentRange(single.getResponseHeaders());
        if (range != null) {
            state.rangeProven = true;
            state.totalLength = range[2];
            totalLength = range[2];
            return length == C.LENGTH_UNSET ? range[2] - position : length;
        }
        if (length != C.LENGTH_UNSET) {
            totalLength = position + length;
            state.totalLength = totalLength;
        }
        if (position > 0) {
            state.unsuitable = true;
            return length;
        }
        if (state.rangeProven) {
            return length;
        }
        if (totalLength > 0 && totalLength < MIN_FILE_BYTES) {
            // The size is already known and too small to parallelise. HLS
            // segments land here on every open; probing them would double the
            // request count for a stream that can never switch.
            return length;
        }
        long probed = probeRangeTotal(state);
        if (probed > 0) {
            state.rangeProven = true;
            state.totalLength = probed;
            totalLength = probed;
        }
        return length;
    }

    /**
     * Proves range support with one byte at the start of the file.
     *
     * <p>Starting at byte 0 is deliberate: Media3's HTTP source answers a 200
     * to a non-zero range by reading and discarding every preceding byte inside
     * {@code open()}, so a probe at the current (possibly tens-of-GB) position
     * could download the whole film before returning. From byte 0 that skip is
     * zero, and a range-ignoring server costs one response.
     *
     * @return the file's total size, or {@link C#LENGTH_UNSET}
     */
    private long probeRangeTotal(UrlState state) {
        DataSpec spec = dataSpec;
        if (spec == null) {
            return C.LENGTH_UNSET;
        }
        DataSource probe = singleFactory.createDataSource();
        try {
            probe.open(spec.buildUpon().setPosition(0).setLength(1L).build());
            long[] range = parseContentRange(probe.getResponseHeaders());
            if (range != null && range[0] == 0 && range[2] > 0) {
                return range[2];
            }
            // A 200 to a one-byte range is definitive: this URL cannot continue
            // at an arbitrary byte, so it must never switch to chunks.
            state.unsuitable = true;
            return C.LENGTH_UNSET;
        } catch (HttpDataSource.InvalidResponseCodeException error) {
            if (error.responseCode == 416 || error.responseCode == 405) {
                state.unsuitable = true;
            }
            // Any other status (429, 503, a transient 5xx) must not disable
            // parallel playback for the whole session.
            return C.LENGTH_UNSET;
        } catch (IOException error) {
            return C.LENGTH_UNSET;
        } finally {
            try {
                probe.close();
            } catch (IOException ignored) {
                // The probe result is already decided.
            }
        }
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
        int read = single.read(buffer, offset, length);
        if (read > 0) {
            position += read;
        }
        return read;
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
        return responseHeaders;
    }

    @Override
    public void close() throws IOException {
        if (engine != null) {
            engine.close();
            engine = null;
        }
        dataSpec = null;
        responseHeaders = Collections.emptyMap();
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

    private static String host(Uri uri) {
        String host = uri.getHost();
        return host == null || host.isEmpty() ? "unknown" : host;
    }

    private static void logDecision(UrlState state, String key, String message) {
        if (key.equals(state.lastDecision)) {
            return;
        }
        state.lastDecision = key;
        Log.i(TAG, message);
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
            // Chunks use the HTTP/1.1 client: concurrent ranges must be separate
            // sockets. An HTTP/2 client would multiplex them onto one connection,
            // which defeats per-connection shaping on the server.
            DataSource source = chunkFactory.createDataSource();
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

    /** Wraps two factories; each source decides per open whether to use parallel requests. */
    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory singleFactory;
        private final DataSource.Factory chunkFactory;

        public Factory(DataSource.Factory singleFactory, DataSource.Factory chunkFactory) {
            this.singleFactory = singleFactory;
            this.chunkFactory = chunkFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new ParallelRangeDataSource(singleFactory, chunkFactory);
        }
    }
}
