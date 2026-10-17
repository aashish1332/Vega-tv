package tv.vega.tv.data.repo

import android.content.Context
import android.util.Log
import tv.vega.tv.data.model.HomeRow
import tv.vega.tv.data.model.VegaInfo
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.data.model.VegaStream
import tv.vega.tv.data.providers.ProviderManager

private const val TAG = "VegaRepo"

/**
 * Home rows plus the reason there are none. Upstream has
 * providerErrorPropagation tests; the TV port must not collapse a runtime crash,
 * a Cloudflare block and a genuine "nothing installed" into one empty list.
 */
data class CatalogResult(
    val rows: List<HomeRow> = emptyList(),
    val diagnostic: String? = null,
)

/**
 * Repository facade with the same semantics as the web app:
 * catalog -> posts(page) -> meta -> episodes -> stream.
 * Delegates to ProviderManager (real provider JS); no stubs.
 */
class VegaRepository(
    private val appContext: Context,
    private val pm: ProviderManager,
) {
    suspend fun active(): ProviderManager.Active? = pm.active()

    suspend fun catalogRows(): CatalogResult {
        pm.ensureSeeded()
        val a = active()
            ?: return CatalogResult(
                diagnostic = "No provider is installed. Add one on the Providers screen " +
                    "(for example @Zenda-Cross), then install a provider from the list.",
            )
        val catalog = runCatching { pm.getCatalog(a) }.fold(
            onSuccess = { it.first },
            onFailure = { e ->
                Log.e(TAG, "Catalog load failed: ${e.message}", e)
                return CatalogResult(diagnostic = describe(e))
            },
        )
        if (catalog.isEmpty()) {
            return CatalogResult(diagnostic = "Provider ${a.ext.value} returned an empty catalog.")
        }
        // Same as getHomePageDataOptimized: page-1 per catalog, keep partial.
        val rows = catalog.map { c ->
            val posts = runCatching { pm.getPosts(a, c.filter, 1) }
            HomeRow(c.title, c.filter, posts.getOrDefault(emptyList()), posts.exceptionOrNull()?.message?.take(160))
        }
        // Every row failed: show the common cause, not a silent blank screen.
        val allFailed = rows.isNotEmpty() && rows.all { it.posts.isEmpty() && it.error != null }
        return CatalogResult(
            rows = rows,
            diagnostic = if (allFailed) "No posts could be loaded. ${rows.first().error}" else null,
        )
    }

    suspend fun posts(filter: String, page: Int): List<VegaPost> {
        val a = active() ?: return emptyList()
        return pm.getPosts(a, filter, page)
    }

    suspend fun search(query: String, page: Int): List<VegaPost> {
        val a = active() ?: return emptyList()
        // Fan-out across installed providers comes later; active first (web
        // SearchResults fans out, but single-provider keeps weak boxes fast).
        return pm.getSearchPosts(a, query, page)
    }

    suspend fun meta(link: String): VegaInfo {
        val a = active() ?: error("No provider")
        return pm.getMeta(a, link)
    }

    suspend fun streams(link: String, type: String, isDownload: Boolean = false): List<VegaStream> {
        val a = active() ?: return emptyList()
        return pm.getStream(a, link, type, isDownload)
    }

    /** Turn a provider-layer throw into one readable line for the TV screen. */
    private fun describe(e: Throwable): String {
        val msg = e.message?.take(200).orEmpty()
        return when {
            e is ProviderManager.WafRequired ->
                "Cloudflare is blocking ${e.host}. Solve the check, then retry."
            msg.contains("Module load failed") -> "Provider module failed to load. $msg"
            // "expected an array" is upstream's own wording; keep it intact
            // rather than restating it, since it names the exact failing call.
            msg.contains("expected an array") -> "Provider returned unexpected data. $msg"
            msg.isBlank() -> e.javaClass.simpleName
            else -> msg
        }
    }
}
