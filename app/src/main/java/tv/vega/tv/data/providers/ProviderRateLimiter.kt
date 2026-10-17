package tv.vega.tv.data.providers

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of upstream `src/lib/sandbox/rateLimiter.ts`.
 *
 * Why the app needs this at all: provider bundles are third-party code that fans
 * out — a catalog page of 40 posts commonly becomes 40 meta fetches, and several
 * bundles fetch their own home page *and* the requested page. Without a limit
 * the app opens dozens of simultaneous connections to one site, which is what
 * gets a TV's weak Wi-Fi to collapse mid-browse, and is also what makes Cloudflare
 * challenge the client more aggressively the more it is hammered.
 *
 * Upstream's exact numbers, kept because they were tuned against real sites:
 *   - 20 requests/second sustained per host bucket
 *   - burst of 30
 *   - 12 concurrent per host, 48 concurrent overall
 *   - at most 64 queued for one host, 20s maximum wait
 *
 * Related subdomains share a bucket (so `img.site.com` and `api.site.com` count
 * against one limit), except for IP literals and single-label hosts, which get a
 * bucket of their own.
 */
class ProviderRateLimiter(
    private val requestsPerSecond: Double = 20.0,
    private val burst: Int = 30,
    private val perHostConcurrency: Int = 12,
    private val globalConcurrency: Int = 48,
    private val maxQueuePerHost: Int = 64,
    private val maxWaitMs: Long = 20_000L,
) {
    private val mutex = Mutex()
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val globalInFlight = AtomicInteger(0)

    private class Bucket {
        var tokens: Double = 0.0
        var lastRefillMs: Long = 0L
        val inFlight = AtomicInteger(0)
        val queued = AtomicInteger(0)
    }

    private fun bucketFor(host: String): Bucket = buckets.computeIfAbsent(host) {
        Bucket().also { b ->
            b.tokens = burst.toDouble()
            b.lastRefillMs = System.currentTimeMillis()
        }
    }

    /**
     * Wait until this host may issue one more request, then return a function the
     * caller **must** invoke when the request settles — skipping it leaks both
     * the concurrency slot and a token.
     */
    suspend fun acquire(host: String): () -> Unit {
        val key = host.lowercase()
        val bucket = bucketFor(key)
        // Upstream refuses rather than queues past the cap: an unbounded queue on
        // a TV is just a memory leak that presents to the user as a freeze.
        if (bucket.queued.incrementAndGet() > maxQueuePerHost) {
            bucket.queued.decrementAndGet()
            error("Too many pending requests for $key")
        }
        val startedAt = System.currentTimeMillis()
        try {
            while (true) {
                if (System.currentTimeMillis() - startedAt > maxWaitMs) {
                    error("Rate limit wait exceeded for $key")
                }
                if (tryReserve(bucket)) return { release(bucket) }
                // 20ms is upstream's effective granularity and is well under the
                // budget of any provider fetch on this hardware.
                delay(20)
            }
        } finally {
            bucket.queued.decrementAndGet()
        }
    }

    /** One token + one host slot + one global slot, or nothing. */
    private suspend fun tryReserve(bucket: Bucket): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        val elapsed = (now - bucket.lastRefillMs).coerceAtLeast(0L)
        bucket.lastRefillMs = now
        bucket.tokens = (bucket.tokens + elapsed / 1000.0 * requestsPerSecond).coerceAtMost(burst.toDouble())
        if (bucket.tokens < 1.0) return@withLock false
        if (bucket.inFlight.get() >= perHostConcurrency) return@withLock false
        if (globalInFlight.get() >= globalConcurrency) return@withLock false
        bucket.tokens -= 1.0
        bucket.inFlight.incrementAndGet()
        globalInFlight.incrementAndGet()
        true
    }

    private fun release(bucket: Bucket) {
        bucket.inFlight.decrementAndGet()
        globalInFlight.decrementAndGet()
    }

    /** Diagnostic view, mirroring upstream's `snapshot(host)`. */
    fun snapshot(host: String): String {
        val key = host.lowercase()
        val b = buckets[key] ?: return "$key: idle"
        return "$key: tokens=%.1f inFlight=%d queued=%d".format(
            b.tokens, b.inFlight.get(), b.queued.get(),
        )
    }

    fun reset() {
        buckets.clear()
        globalInFlight.set(0)
    }

    companion object {
        val default = ProviderRateLimiter()
    }
}
