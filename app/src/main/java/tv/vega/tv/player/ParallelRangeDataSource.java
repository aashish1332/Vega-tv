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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plays a large progressive HTTP file over parallel range requests, but only
 * after one connection has been measured to be too slow.
 *
 * <p>An 80+ GB 4K remux averages about 90 Mbps and peaks well above it. A host
 * that shapes each connection can leave one request below that rate even when
 * the link itself has headroom, and the player then drains its forward buffer
 * and stalls. This source therefore starts every open as one normal request,
 * measures how fast that request actually delivers bytes, and switches to
 * ordered chunks on up to four sockets only when the measured rate is below
 * what the file needs.
 *
 * <p>The decision is deliberately conservative. A fast start is not treated as
 * proof: the measurement runs in three-second / 512-KB windows for as long as
 * the open stays on one connection, so a connection that starts fast and is
 * then shaped still triggers a switch. A switch always happens at the exact
 * byte the player has reached, so nothing is downloaded twice and no data is
 * lost.
 *
 * <p>Once chunks are running, the source measures their aggregate delivery
 * rate. If parallel requests do not beat the single request by a real margin,
 * the URL is remembered as single for the rest of the session instead of
 * wasting sockets on a host that caps the whole client. A URL that did benefit
 * is remembered as parallel, so a later seek engages at once rather than
 * paying the measurement again.
 *
 * <p>The single request, the range probe and every fallback use the caller's
 * normal header-aware factory. Chunks use a second factory built on an
 * HTTP/1.1-only client: concurrent requests to the same host then occupy
 * separate sockets instead of being multiplexed onto one HTTP/2 connection,
 * which is the whole point of parallel range downloading.
 *
 * <p>Any open that still has at least {@link #MIN_PARALLEL_BYTES} left to
 * stream is measured and may switch, not just multi-gigabyte files: a 300 MB
 * episode on a throttled host stalls the same way an 80 GB remux does. The
 * measured rate is compared with what the file needs, and a connection that
 * is fast enough stays single. Small HLS segments, subtitles, playlists and
 * the bounded 64 KB Matroska probes stay single-request. A URL that already
 * benefited engages at once on later seeks. A 429 or 503 permanently lowers
 * the connection count for that host. A server that ignores a range is marked
 * unsuitable and the open falls back to one request. Query strings are never
 * logged.
 */
public final class ParallelRangeDataSource implements DataSource {
    private static final String TAG = "VegaParallelStream";

    static final int MAX_CONNECTIONS = 4;

    /** A switch needs this much blocked time and this many delivered bytes. */
    private static final long MEASURE_MIN_NANOS = 3_000_000_000L;
    private static final long MEASURE_MIN_BYTES = 512L * 1024L;
    /**
     * Never switch when one connection already delivers this much: 16 MB/s is
     * 128 Mbps, comfortably above a 4K remux's ~90 Mbps average. A connection
     * below this cannot keep a 4K film buffered through normal jitter.
     */
    private static final long MIN_SWITCH_BYTES_PER_SECOND = 16L * 1000L * 1000L;
    /** Feature-length bound used to estimate a file's bitrate from its size. */
    private static final long ASSUMED_FILM_SECONDS = 90L * 60L;

    private static final int FIRST_CHUNK_BYTES = 1024 * 1024;
    private static final int MAX_CHUNK_BYTES = 8 * 1024 * 1024;
    /**
     * The smallest amount of a file left to stream that is worth parallelising.
     * Any video above it is measured -- a 300 MB episode and an 80 GB remux get
     * the same treatment -- while small HLS segments, sidecars, playlists and
     * the bounded cue probes stay single-request.
     */
    private static final long MIN_PARALLEL_BYTES = 16L * 1024L * 1024L;
    /** Until the player has read this much from an open, only two chunks run. */
    private static final long CONFIRM_PLAYBACK_BYTES = 512L * 1024L;
    private static final int READ_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_CHUNK_ATTEMPTS = 3;
    private static final int MAX_REMEMBERED_URLS = 32;
    /** 429s within this time of a cut belong to the same burst and do not cut again. */
    private static final long RATE_LIMIT_BURST_MS = 3000L;
    private static final int MAX_REOPEN_ATTEMPTS = 4;

    /** Parallel chunks must beat the single request by at least 15%. */
    private static final long VERIFY_MIN_NANOS = 2_000_000_000L;
    private static final long VERIFY_MIN_BYTES = 16L * 1024L * 1024L;
    private static final long VERIFY_MIN_GAIN_PERCENT = 115L;

    /**
     * Chunk buffers sit on top of the player's own forward buffer, so the
     * window is sized from the heap rather than fixed. Four megabytes per
     * socket keeps all four busy on a low-memory box; eight on a larger one.
     */
    private static final long WINDOW_BYTES = Math.max(
            16L * 1024L * 1024L,
            Math.min(48L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 8L));
    private static final int CHUNK_CAP_BYTES = (int) Math.max(
            2L * 1024L * 1024L,
            Math.min((long) MAX_CHUNK_BYTES, WINDOW_BYTES / MAX_CONNECTIONS));

    /** Per URL: range support proven, and how parallel requests behaved. */
    private static final Map<String, UrlState> URL_STATES = new ConcurrentHashMap<>();
    /** Per host: connections allowed; lowered by each 429 or 503. */
    private static final Map<String, AtomicInteger> HOST_LIMITS = new ConcurrentHashMap<>();

    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "VegaParallelStream");
        thread.setDaemon(true);
        return thread;
    });

    /** The decision the last playback made, for the Stats overlay. */
    private static volatile String diagnostics = "one connection";

    /**
     * The mode, measured rate and reason from the most recent playback, e.g.
     * {@code "parallel 4x - 21.4 MB/s (single 6.2)"} or
     * {@code "single - server ignores ranges"}. Best-effort diagnostics only.
     */
    public static String diagnostics() {
        return diagnostics;
    }

    private static void setDiagnostics(String value) {
        diagnostics = value;
    }

    private static final class UrlState {
        volatile boolean unsuitable;
        /** The server has answered a byte range with a 206 for this file. */
        volatile boolean rangeProven;
        volatile long totalLength = C.LENGTH_UNSET;
        /** Parallel requests were measured to deliver more for this URL. */
        volatile boolean parallelVerified;
        /** Parallel requests were measured and did not help; stay single. */
        volatile boolean parallelUnhelpful;
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
    private long endPosition = C.LENGTH_UNSET;
    private long totalLength = C.LENGTH_UNSET;
    /** The first response's headers, kept after the single request is closed. */
    private Map<String, List<String>> responseHeaders = Collections.emptyMap();

    // Measuring the single connection. Time is wall time spent inside read(),
    // so a connection that is fast only while its socket buffer drains is not
    // mistaken for a fast connection.
    private boolean measuring;
    private long measuredNanos;
    private long measuredBytes;
    private long lastSingleRate;

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
        measuring = false;
        measuredNanos = 0;
        measuredBytes = 0;
        lastSingleRate = 0;

        boolean eligible = suitable(dataSpec);
        UrlState state = eligible ? urlState(dataSpec.uri) : null;
        int limit = hostLimit(dataSpec.uri).get();

        // A URL that already proved parallel requests help engages at once. A
        // seek would otherwise pay the three-second measurement -- and the
        // stall it exists to prevent -- on every jump.
        if (state != null && state.parallelVerified && !state.parallelUnhelpful
                && state.rangeProven
                && limit > 1) {
            long end = dataSpec.length == C.LENGTH_UNSET
                    ? state.totalLength
                    : Math.min(state.totalLength, position + dataSpec.length);
            if (end - position >= MIN_PARALLEL_BYTES) {
                endPosition = end;
                totalLength = state.totalLength;
                engine = new Engine(dataSpec, position, end, state, 0);
                int used = Math.min(MAX_CONNECTIONS, limit);
                logDecision(state, "parallel-remembered", String.format(Locale.US,
                        "Parallel %dx range: host=%s total=%.1f GB from=%.1f GB "
                                + "(remembered)",
                        used, host(dataSpec.uri), totalLength / 1e9, position / 1e9));
                setDiagnostics(String.format(Locale.US,
                        "parallel %dx - remembered", used));
                return end - position;
            }
        }

        // One normal request discovers the size and range support, and is the
        // stream playback falls back to.
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
        if (length == C.LENGTH_UNSET && totalLength > 0) {
            length = totalLength - position;
        }

        if (state != null) {
            long remaining =
                    endPosition == C.LENGTH_UNSET ? C.LENGTH_UNSET : endPosition - position;
            // A bounded open counts too: a large HLS byterange segment is the
            // video's own data, and the 206 that exposed it has already proved
            // that ranges work. Small bounded opens never get here.
            boolean canMeasure = state.rangeProven
                    && !state.unsuitable
                    && remaining >= MIN_PARALLEL_BYTES
                    && limit > 1;
            if (canMeasure && !state.parallelUnhelpful) {
                measuring = true;
                logDecision(state, "measure", String.format(Locale.US,
                        "Single: host=%s total=%.1f GB from=%.1f GB (measuring throughput)",
                        host(dataSpec.uri), totalLength / 1e9, position / 1e9));
                setDiagnostics("single - measuring");
            } else if (state.unsuitable) {
                logDecision(state, "single-norange", String.format(Locale.US,
                        "Single: host=%s total=%s (server ignores byte ranges)",
                        host(dataSpec.uri),
                        totalLength > 0
                                ? String.format(Locale.US, "%.1f GB", totalLength / 1e9)
                                : "unknown"));
                setDiagnostics("single - server ignores ranges");
            } else if (state.parallelUnhelpful) {
                logDecision(state, "single-unhelpful", String.format(Locale.US,
                        "Single: host=%s total=%.1f GB (parallel gave no gain)",
                        host(dataSpec.uri), totalLength / 1e9));
                setDiagnostics("single - parallel did not help");
            } else if (limit <= 1) {
                logDecision(state, "single-host", String.format(Locale.US,
                        "Single: host=%s (server limit is one connection)",
                        host(dataSpec.uri)));
                setDiagnostics("single - host limit");
            } else {
                logDecision(state, "single-small", String.format(Locale.US,
                        "Single: host=%s total=%s remaining=%.1f MB (too small or "
                                + "unseekable to parallelise)",
                        host(dataSpec.uri),
                        totalLength > 0
                                ? String.format(Locale.US, "%.1f GB", totalLength / 1e9)
                                : "unknown",
                        remaining > 0 ? remaining / 1e6 : 0));
                setDiagnostics("single - small or unseekable");
            }
        }
        return length;
    }

    /**
     * Turns the first response into a total size and a range decision.
     *
     * <p>A 206 proves ranges and carries the total. A 200 at position 0 is the
     * normal whole-file response; {@code Accept-Ranges: bytes} is trusted, as
     * the official engine trusts it, and the first chunk checks a real 206
     * again. A one-byte probe at byte 0 settles a server that sends neither
     * header. A 200 at a non-zero position means the server ignored the range
     * (and Media3 has already skipped those bytes), so that URL must stay
     * single.
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
            if (range[0] != position || range[1] < range[0]) {
                // Media3's HTTP source does not skip on a 206, so a range that
                // starts anywhere else would desynchronise the stream.
                state.unsuitable = true;
                return length;
            }
            state.rangeProven = true;
            if (range[2] > 0) {
                state.totalLength = range[2];
                totalLength = range[2];
            }
            if (length == C.LENGTH_UNSET && range[2] > 0) {
                length = range[2] - position;
            }
            return length;
        }
        // No Content-Range: a 200 carrying the whole body from `position`.
        // Only an unbounded open runs to the end of the file, so only then is
        // position + length the file's total. A bounded open (a clip, an HLS
        // byterange segment) reports just its own length and must not overwrite
        // the remembered total with it.
        boolean wholeFile = dataSpec == null || dataSpec.length == C.LENGTH_UNSET;
        if (wholeFile && length != C.LENGTH_UNSET) {
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
        if (totalLength >= MIN_PARALLEL_BYTES) {
            if (hasAcceptRanges(single.getResponseHeaders())) {
                state.rangeProven = true;
                return length;
            }
            probeRanges(state);
            return length;
        }
        if (totalLength > 0) {
            // Too small to bother: HLS segments, sidecars and playlists land
            // here on every open. Probing them would double their requests.
            return length;
        }
        if (!wholeFile) {
            // A bounded open of unknown size (a clip whose server sent no
            // length) cannot be bounded for the chunk engine.
            return length;
        }
        // A whole-file open with no Content-Length (chunked transfer). A
        // byte-0 range probe discovers both the total and range support, which
        // is the only way a chunked 80 GB film can be measured at all.
        probeRanges(state);
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
     */
    private void probeRanges(UrlState state) {
        DataSpec spec = dataSpec;
        if (spec == null) {
            return;
        }
        DataSource probe = singleFactory.createDataSource();
        try {
            probe.open(spec.buildUpon().setPosition(0).setLength(1L).build());
            Map<String, List<String>> headers = probe.getResponseHeaders();
            long[] range = parseContentRange(headers);
            if (range != null) {
                if (range[0] != 0 || range[1] < 0) {
                    state.unsuitable = true;
                    return;
                }
                if (range[2] > 0) {
                    state.totalLength = range[2];
                    totalLength = range[2];
                }
                state.rangeProven = true;
                return;
            }
            if (hasAcceptRanges(headers)) {
                // Trusted like the official engine; the first chunk validates.
                state.rangeProven = true;
                return;
            }
            // A 200 with no Accept-Ranges: a one-byte range gets the whole
            // file, so chunks would too.
            state.unsuitable = true;
        } catch (HttpDataSource.InvalidResponseCodeException error) {
            if (error.responseCode == 416 || error.responseCode == 405) {
                state.unsuitable = true;
            }
            // Any other status (429, 503, a transient 5xx) must not disable
            // parallel playback for the whole session.
        } catch (IOException error) {
            // A transient probe failure proves nothing either way.
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
                UrlState state = dataSpec == null ? null : urlState(dataSpec.uri);
                if (state != null) {
                    // Measure again on the next open instead of re-engaging
                    // blind; a host that just refused four sockets may allow
                    // two, and the gate will find that out.
                    state.parallelVerified = false;
                }
                reopenSingle();
                setDiagnostics("single - fell back from parallel");
            }
        }

        long started = measuring ? System.nanoTime() : 0L;
        int read = single.read(buffer, offset, length);
        if (read > 0) {
            position += read;
        }
        if (measuring && read > 0) {
            measuredNanos += System.nanoTime() - started;
            measuredBytes += read;
            if (measuredNanos >= MEASURE_MIN_NANOS && measuredBytes >= MEASURE_MIN_BYTES) {
                long rate = measuredBytes * 1_000_000_000L / Math.max(1L, measuredNanos);
                lastSingleRate = rate;
                measuredNanos = 0;
                measuredBytes = 0;
                long remaining = endPosition == C.LENGTH_UNSET
                        ? C.LENGTH_UNSET
                        : endPosition - position;
                if (rate < switchThresholdBytesPerSecond()
                        && remaining >= MIN_PARALLEL_BYTES) {
                    measuring = false;
                    switchToParallel(rate);
                } else {
                    // The connection is fast enough now. Keep measuring: a
                    // burst at the start of a stream is often followed by the
                    // real, shaped rate.
                    setDiagnostics(String.format(Locale.US,
                            "single - %.1f MB/s", rate / 1e6));
                }
            }
        }
        return read;
    }

    /**
     * The rate below which one connection cannot keep the film fed. A 90-minute
     * bound converts the file size into a rough bitrate; the floor then covers
     * files whose real duration is longer than that bound.
     */
    private long switchThresholdBytesPerSecond() {
        long bySize = totalLength > 0 ? totalLength / ASSUMED_FILM_SECONDS : 0L;
        return Math.max(MIN_SWITCH_BYTES_PER_SECOND, bySize);
    }

    /** Leaves the single request at the current byte and starts ordered chunks. */
    private void switchToParallel(long singleRate) {
        DataSpec spec = dataSpec;
        if (spec == null || endPosition == C.LENGTH_UNSET) {
            return;
        }
        UrlState state = urlState(spec.uri);
        if (state.unsuitable) {
            return;
        }
        // Remembered before verification so a seek while the first chunks are
        // still proving themselves engages at once; a poor gain clears it.
        state.parallelVerified = true;
        closeSingleQuietly();
        engine = new Engine(spec, position, endPosition, state, singleRate);
        int used = Math.min(MAX_CONNECTIONS, hostLimit(spec.uri).get());
        logDecision(state, "parallel", String.format(Locale.US,
                "Parallel %dx range: host=%s total=%.1f GB from=%.1f GB single=%.1f MB/s",
                used, host(spec.uri), totalLength / 1e9, position / 1e9,
                singleRate / 1e6));
        setDiagnostics(String.format(Locale.US,
                "parallel %dx - single %.1f MB/s", used, singleRate / 1e6));
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
        measuring = false;
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
                long first = Long.parseLong(firstAndLast[0].trim());
                long last = Long.parseLong(firstAndLast[1].trim());
                // Some servers answer a one-byte probe with "bytes 0-0/*".
                boolean knownTotal = rangeAndTotal.length > 1
                        && !rangeAndTotal[1].trim().equals("*");
                long total = knownTotal
                        ? Long.parseLong(rangeAndTotal[1].trim())
                        : C.LENGTH_UNSET;
                if (first < 0 || last < first) {
                    return null;
                }
                return new long[] {first, last, total};
            } catch (RuntimeException malformed) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasAcceptRanges(Map<String, List<String>> headers) {
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
        private final long singleRate;
        private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
        private final Set<DataSource> activeSources =
                Collections.newSetFromMap(new ConcurrentHashMap<DataSource, Boolean>());

        private long readPosition;
        private long nextChunkStart;
        private int nextChunkBytes = Math.min(FIRST_CHUNK_BYTES, CHUNK_CAP_BYTES);
        private long consumed;
        private long windowBytes;
        private int running;
        private long lastCutAt;
        private int cuts;
        private boolean closed;
        @Nullable private String fallbackReason;

        // Aggregate network bytes and the wall time during which at least one
        // chunk worker was actually reading. Summing per-read times would count
        // concurrent workers several times over and make four sockets look as
        // slow as one.
        private long netBytes;
        private long activeNanos;
        private int activeReaders;
        private long activeSinceNanos;
        private boolean verified;

        Engine(DataSpec spec, long start, long end, UrlState state, long singleRate) {
            this.spec = spec;
            this.end = end;
            this.state = state;
            this.hostLimit = hostLimit(spec.uri);
            this.singleRate = singleRate;
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
                                windowBytes -= head.data.length;
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
            ArrayList<DataSource> toClose;
            synchronized (lock) {
                closed = true;
                chunks.clear();
                windowBytes = 0;
                toClose = new ArrayList<>(activeSources);
                activeSources.clear();
                lock.notifyAll();
            }
            for (DataSource source : toClose) {
                try {
                    source.close();
                } catch (IOException ignored) {
                    // Every worker reports its own result.
                }
            }
        }

        /** Adds chunks up to the window and starts their downloads. Holds the lock. */
        private void schedule() {
            if (closed || fallbackReason != null) {
                return;
            }
            int connections = connections();
            int window = window(connections);
            while (chunks.size() < window && nextChunkStart < end) {
                int size = (int) Math.min((long) nextChunkBytes, end - nextChunkStart);
                if (windowBytes + size > WINDOW_BYTES && !chunks.isEmpty()) {
                    break;
                }
                chunks.addLast(new Chunk(nextChunkStart, size));
                windowBytes += size;
                nextChunkStart += size;
                if (nextChunkBytes < CHUNK_CAP_BYTES) {
                    nextChunkBytes = (int) Math.min(
                            (long) CHUNK_CAP_BYTES, (long) nextChunkBytes * 2L);
                }
            }
            long now = System.currentTimeMillis();
            for (Chunk chunk : chunks) {
                if (running >= connections) {
                    break;
                }
                if (!chunk.running && chunk.filled < chunk.data.length && now >= chunk.notBefore) {
                    chunk.running = true;
                    running++;
                    WORKERS.execute(() -> download(chunk));
                }
            }
        }

        private int connections() {
            int limit = Math.min(MAX_CONNECTIONS, hostLimit.get());
            int byWindow = (int) Math.max(1L, WINDOW_BYTES / CHUNK_CAP_BYTES);
            return Math.max(1, Math.min(limit, byWindow));
        }

        private int window(int connections) {
            int base = consumed >= CONFIRM_PLAYBACK_BYTES
                    ? connections * 2
                    : Math.min(2, connections);
            return Math.max(1, base);
        }

        /**
         * Fetches one chunk. A server may answer with a shorter range than
         * asked for (some cap range responses), so one chunk can take several
         * requests; each continues exactly where the previous one stopped.
         */
        private void download(Chunk chunk) {
            IOException error = null;
            byte[] buffer = new byte[READ_BUFFER_BYTES];
            try {
                while (true) {
                    long from;
                    synchronized (lock) {
                        if (closed) {
                            return;
                        }
                        from = chunk.start + chunk.filled;
                    }
                    if (from >= chunk.end()) {
                        return;
                    }
                    DataSource source = chunkFactory.createDataSource();
                    for (TransferListener listener : transferListeners) {
                        source.addTransferListener(listener);
                    }
                    activeSources.add(source);
                    try {
                        source.open(spec.buildUpon()
                                .setPosition(from)
                                .setLength(chunk.end() - from)
                                .build());
                        long[] range = parseContentRange(source.getResponseHeaders());
                        if (range == null) {
                            // A 200 here is definitive: the server sent the
                            // whole file for a byte range. Media3 may already
                            // have skipped `from` bytes, so never chunk again.
                            state.unsuitable = true;
                            fail("server ignored the byte range");
                            return;
                        }
                        if (range[0] != from || range[1] < from) {
                            fail("server returned an unexpected range");
                            return;
                        }
                        if (range[2] > 0 && state.totalLength > 0
                                && range[2] != state.totalLength) {
                            fail("file size changed");
                            return;
                        }
                        long toRead = Math.min(chunk.end(), range[1] + 1L) - from;
                        readBody(source, chunk, buffer, toRead);
                        synchronized (lock) {
                            if (closed) {
                                return;
                            }
                            if (chunk.filled >= chunk.data.length) {
                                return;
                            }
                        }
                        // The server capped the range. Request the rest from
                        // the next byte; this is a normal short 206, not an
                        // error, so no attempt is counted.
                    } finally {
                        activeSources.remove(source);
                        try {
                            source.close();
                        } catch (IOException ignored) {
                            // The chunk result is already decided.
                        }
                    }
                }
            } catch (IOException e) {
                error = e;
            } finally {
                synchronized (lock) {
                    chunk.running = false;
                    running--;
                    if (error != null) {
                        onChunkError(chunk, error);
                    }
                    schedule();
                    lock.notifyAll();
                }
            }
        }

        /** Reads exactly {@code toRead} bytes of this response into the chunk. */
        private void readBody(DataSource source, Chunk chunk, byte[] buffer, long toRead)
                throws IOException {
            long readTotal = 0;
            while (readTotal < toRead) {
                int want = (int) Math.min((long) buffer.length, toRead - readTotal);
                beginNetworkRead();
                try {
                    int read = source.read(buffer, 0, want);
                    if (read == C.RESULT_END_OF_INPUT) {
                        break;
                    }
                    if (read <= 0) {
                        throw new IOException("No data from the range");
                    }
                    synchronized (lock) {
                        if (closed) {
                            return;
                        }
                        System.arraycopy(buffer, 0, chunk.data, chunk.filled, read);
                        chunk.filled += read;
                        netBytes += read;
                        lock.notifyAll();
                    }
                    readTotal += read;
                    // Still inside the active interval, so the gain check sees
                    // this worker's latest wall-clock contribution.
                    maybeVerifyGain();
                } finally {
                    endNetworkRead();
                }
            }
            if (readTotal < toRead) {
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                }
                throw new IOException("Range ended early");
            }
        }

        /**
         * Opens a "network active" interval. Concurrent workers share one
         * interval: the clock runs once while any socket is delivering bytes,
         * which is the comparable unit to the single connection's read time.
         */
        private void beginNetworkRead() {
            synchronized (lock) {
                if (activeReaders++ == 0) {
                    activeSinceNanos = System.nanoTime();
                }
            }
        }

        private void endNetworkRead() {
            synchronized (lock) {
                if (activeReaders <= 0) {
                    return;
                }
                if (--activeReaders == 0 && activeSinceNanos != 0) {
                    activeNanos += System.nanoTime() - activeSinceNanos;
                    activeSinceNanos = 0;
                }
            }
        }

        /**
         * Compares the aggregate chunk rate with the rate the single request
         * measured at the switch. A host that caps the whole client shows no
         * gain and is remembered as single; the current chunks keep running to
         * avoid a second stall mid-film. Holds the lock.
         */
        private void maybeVerifyGain() {
            synchronized (lock) {
                long active = activeNanos + (activeSinceNanos == 0
                        ? 0L
                        : System.nanoTime() - activeSinceNanos);
                if (verified || singleRate <= 0
                        || active < VERIFY_MIN_NANOS || netBytes < VERIFY_MIN_BYTES) {
                    return;
                }
                verified = true;
                long parallelRate = netBytes * 1_000_000_000L / Math.max(1L, active);
                if (parallelRate * 100L < singleRate * VERIFY_MIN_GAIN_PERCENT) {
                    state.parallelVerified = false;
                    state.parallelUnhelpful = true;
                    Log.i(TAG, String.format(Locale.US,
                            "Parallel gives %.1f MB/s vs %.1f MB/s on one connection; "
                                    + "staying single for this URL",
                            parallelRate / 1e6, singleRate / 1e6));
                    setDiagnostics(String.format(Locale.US,
                            "parallel %dx - %.1f MB/s (single %.1f) - no gain",
                            connections(), parallelRate / 1e6, singleRate / 1e6));
                } else {
                    Log.i(TAG, String.format(Locale.US,
                            "Parallel gives %.1f MB/s vs %.1f MB/s on one connection",
                            parallelRate / 1e6, singleRate / 1e6));
                    setDiagnostics(String.format(Locale.US,
                            "parallel %dx - %.1f MB/s (single %.1f)",
                            connections(), parallelRate / 1e6, singleRate / 1e6));
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
            long now = System.currentTimeMillis();
            if (status == 429 || status == 503) {
                // The server limits connections. Keep only as many as are
                // working now, for this host from now on. Several requests can
                // be refused at once; that is one cut.
                if (now - lastCutAt >= RATE_LIMIT_BURST_MS) {
                    lastCutAt = now;
                    // Right after the switch the server may still count the
                    // single request that was just closed, so the first cut
                    // keeps two connections.
                    int floor = cuts++ == 0 ? 2 : 1;
                    int working = Math.max(1, running);
                    int limit = lowerHostLimit(hostLimit, floor, working);
                    Log.i(TAG, "Server answered " + status
                            + "; connection limit now " + limit);
                    if (limit <= 1) {
                        fail("server limits connections");
                        return;
                    }
                }
                // A refused request is not a failure of the chunk; wait and retry.
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
            chunk.notBefore = now + (1000L << Math.min(3, chunk.attempts));
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
