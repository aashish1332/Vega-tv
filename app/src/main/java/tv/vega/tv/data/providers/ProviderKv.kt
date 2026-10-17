package tv.vega.tv.data.providers

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.providerKvStore by preferencesDataStore("provider_kv")

/**
 * Port of `providerKvStorage` (upstream `StorageService.ts`), reached from the
 * sandbox as `providerContext.kvStore`.
 *
 * Why this is the single most important file in the provider stack: the real
 * `vega-providers` bundles reference `providerContext.kvStore` **192 times**
 * across `dist/`. They use it to carry a Cloudflare `cf_clearance` cookie, a
 * scraped token, a memoised catalog listing, and per-episode resume positions
 * from one call to the next. The previous implementation kept those values in a
 * plain JS object on the runtime page, which meant every call to
 * `ProviderJs.invoke` started from nothing — and a Cloudflare clearance earned
 * on one call was gone by the next, so a challenge that had just been solved
 * reappeared immediately. Providers that gate the whole site behind a token
 * could therefore never work, no matter how many times the challenge was solved.
 *
 * Key scoping mirrors upstream `getScopedKvKey`: every key is stored as
 * `"<author>:<key>"`, so one provider's `cf_clearance` can never be handed to
 * another. Note that the stored JSON is the *whole* value — upstream stores
 * `JSON.stringify(value)` and parses on read, so an `undefined` round-trips as
 * absent rather than as a literal `"undefined"` string.
 *
 * Size limits are upstream's, enforced here rather than in JS because by the
 * time a value is this large the interesting failure is a full DataStore, and
 * a thrown Kotlin exception reaches the provider as a rejected promise, which
 * is what upstream's worker does too.
 */
class ProviderKv(private val context: Context) {

    companion object {
        /** Upstream `MAX_KV_KEY_LENGTH`. */
        const val MAX_KEY_LENGTH = 256

        /** Upstream `MAX_KV_VALUE_BYTES`. */
        const val MAX_VALUE_BYTES = 1_000_000
    }

    private fun scoped(providerValue: String, key: String) = "$providerValue:$key"

    /** Returns the stored value, or null when absent — which JS reads as `undefined`. */
    suspend fun get(providerValue: String, key: String): String? {
        if (key.length > MAX_KEY_LENGTH) throw IllegalArgumentException("Key too long")
        return context.providerKvStore.data.first()[stringPreferencesKey(scoped(providerValue, key))]
    }

    suspend fun set(providerValue: String, key: String, value: String) {
        if (key.length > MAX_KEY_LENGTH) throw IllegalArgumentException("Key too long")
        if (value.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
            throw IllegalArgumentException("Value too large")
        }
        val sk = scoped(providerValue, key)
        context.providerKvStore.edit { p ->
            // Storing the raw string is what makes get() a byte-for-byte
            // round-trip; JSON-encoding here would double-encode values that
            // are already JSON and string values that merely look like JSON.
            p[stringPreferencesKey(sk)] = value
        }
    }

    suspend fun delete(providerValue: String, key: String): Boolean {
        val sk = stringPreferencesKey(scoped(providerValue, key))
        var removed = false
        context.providerKvStore.edit { p ->
            removed = p.contains(sk)
            p.remove(sk)
        }
        return removed
    }

    /** Keys for this provider only, with the `"<author>:"` prefix stripped. */
    suspend fun keys(providerValue: String): List<String> {
        val prefix = "$providerValue:"
        return context.providerKvStore.data.first().asMap().keys
            .filterIsInstance<String>()
            .filter { it.startsWith(prefix) }
            .map { it.substring(prefix.length) }
            .sorted()
    }

    suspend fun clear(providerValue: String) {
        val prefix = "$providerValue:"
        context.providerKvStore.edit { p ->
            p.asMap().keys.filterIsInstance<String>()
                .filter { it.startsWith(prefix) }
                .forEach { p.remove(stringPreferencesKey(it)) }
        }
    }

    /** Upstream `clearProviderStorage`: drop state *and* every namespaced key. */
    suspend fun clearAll(providerValue: String) = clear(providerValue)

    /**
     * Every stored pair for one provider, unscoped. Used to prime the
     * synchronous RPC mirror at the start of an invoke, so a provider's first
     * `kvStore.get` is answered from disk-loaded state rather than from nothing.
     */
    suspend fun snapshotFor(providerValue: String): Map<String, String> {
        val prefix = "$providerValue:"
        val out = mutableMapOf<String, String>()
        context.providerKvStore.data.first().asMap().forEach { (k, v) ->
            if (k is String && k.startsWith(prefix) && v is String) {
                out[k.substring(prefix.length)] = v
            }
        }
        return out
    }
}
