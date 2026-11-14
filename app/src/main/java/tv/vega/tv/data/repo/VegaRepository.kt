package tv.vega.tv.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import tv.vega.tv.data.model.HomeRow
import tv.vega.tv.data.model.VegaInfo
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.data.model.VegaStream
import tv.vega.tv.data.providers.ProviderManager
import tv.vega.tv.data.settings.AppSettings

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
 * A catalog page after the family filter. [rawCount] is the provider's count
 * before filtering: an all-filtered page is not the same as the provider's
 * "no more pages" empty page.
 */
data class PostPage(
    val posts: List<VegaPost>,
    val rawCount: Int,
)

/**
 * Repository facade with the same semantics as the web app:
 * catalog -> posts(page) -> meta -> episodes -> stream.
 * Delegates to ProviderManager (real provider JS); no stubs.
 */
class VegaRepository(
    private val appContext: Context,
    private val pm: ProviderManager,
    private val settings: AppSettings,
) {
    suspend fun active(): ProviderManager.Active? = pm.active()

    /**
     * Home rows. [filterRef] blank means the default view: the single active
     * provider, or — when more than one provider is installed — every
     * installed provider mixed together (the active one answers first). A
     * non-blank "author:value" restricts the rows to that provider.
     *
     * [onPartial] fires as rows arrive so one slow provider cannot hold the
     * whole home page hostage; the screen fills in progressively (the same
     * progressive publish the mobile app's getHomePageDataOptimized does).
     */
    suspend fun catalogRows(
        filterRef: String? = null,
        onPartial: ((CatalogResult) -> Unit)? = null,
    ): CatalogResult {
        pm.ensureSeeded()
        val wanted = filterRef?.takeIf { it.isNotBlank() }
        val sources = pm.sources().associateBy { it.author }
        val installed = pm.installed().mapNotNull { ext ->
            sources[ext.source.author]?.let { ProviderManager.Active(ext, it) }
        }
        val activeRef = pm.active()?.let { "${it.ext.source.author}:${it.ext.value}" }
        val targets = when {
            wanted != null -> installed.filter { "${it.ext.source.author}:${it.ext.value}" == wanted }
            else -> installed
        }
        if (targets.isEmpty()) {
            return CatalogResult(
                diagnostic = if (wanted != null) {
                    "Provider $wanted is not installed."
                } else {
                    "No provider is installed. Add one on the Providers screen " +
                        "(for example @Zenda-Cross), then install a provider from the list."
                },
            )
        }
        // Mixing: one provider's failure must not hide the rest, and the
        // viewer's current provider answers first.
        val ordered = if (wanted == null && targets.size > 1 && activeRef != null) {
            targets.sortedByDescending { "${it.ext.source.author}:${it.ext.value}" == activeRef }
        } else {
            targets
        }
        val mixing = ordered.size > 1
        val familyOn = runCatching { settings.familyFilter.first() }.getOrDefault(true)
        // Dedupe across the whole mixed feed, not per provider: two installed
        // providers routinely carry the same title, and the active provider is
        // first in `ordered`, so its copy is the one kept.
        val seenTitles = HashSet<String>()
        val rows = mutableListOf<HomeRow>()
        var firstFailure: String? = null
        var rawPosts = 0
        var familyRemoved = 0
        for (a in ordered) {
            val ref = "${a.ext.source.author}:${a.ext.value}"
            val name = a.ext.display_name.ifBlank { a.ext.value }
            val catalog = try {
                pm.getCatalog(a).first
            } catch (e: CancellationException) {
                // A refresh cancelled by the user leaving Home is not a
                // provider failure; swallowing it here painted every row with
                // "StandaloneCoroutine was cancelled".
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Catalog load failed for $ref: ${e.message}", e)
                if (firstFailure == null) firstFailure = describe(e)
                emptyList()
            }
            if (catalog.isEmpty()) continue
            val visibleCatalog = if (familyOn) {
                catalog.filter { FamilyFilter.isAllowedCatalog(it.title, it.filter) }
            } else {
                catalog
            }
            if (visibleCatalog.isEmpty()) continue
            // Providers often mirror adult titles into "Latest"/"Movies" with
            // no adult marker on the post itself. Load the provider's adult
            // sections once and quarantine their links/images from every
            // regular row, instead of trusting the card text alone.
            val adultLinks = HashSet<String>()
            val adultImages = HashSet<String>()
            if (familyOn && visibleCatalog.size != catalog.size) {
                for (adultEntry in catalog) {
                    if (FamilyFilter.isAllowedCatalog(adultEntry.title, adultEntry.filter)) continue
                    val adultPosts = try {
                        pm.getPosts(a, adultEntry.filter, 1)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        emptyList()
                    }
                    for (post in adultPosts) {
                        if (post.link.isNotBlank()) adultLinks += post.link
                        if (post.image.isNotBlank()) adultImages += post.image
                    }
                }
            }
            for (c in visibleCatalog) {
                // Same as getHomePageDataOptimized: page-1 per catalog, keep partial.
                val posts = try {
                    Result.success(pm.getPosts(a, c.filter, 1))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                val fetched = posts.getOrDefault(emptyList())
                rawPosts += fetched.size
                val unique = ArrayList<VegaPost>(fetched.size)
                for (post in fetched) {
                    if (familyOn && !FamilyFilter.isAllowed(post)) {
                        familyRemoved++
                        continue
                    }
                    if (familyOn && (
                            post.link in adultLinks ||
                                (post.image.isNotBlank() && post.image in adultImages)
                            )
                    ) {
                        familyRemoved++
                        continue
                    }
                    if (seenTitles.add(FamilyFilter.dedupeKey(post.title))) unique += post
                }
                val error = posts.exceptionOrNull()?.message?.take(160)
                if (unique.isEmpty() && error == null) continue
                rows += HomeRow(
                    title = if (mixing) "$name · ${c.title}" else c.title,
                    filter = c.filter,
                    posts = unique,
                    error = error,
                    providerRef = if (mixing) ref else "",
                )
                onPartial?.invoke(CatalogResult(rows = rows.toList()))
            }
        }
        if (rows.isEmpty()) {
            val diagnostic = when {
                firstFailure != null -> firstFailure
                familyOn && familyRemoved > 0 && familyRemoved >= rawPosts ->
                    "Family filter is hiding all results. Turn it off in Settings → Family filter."
                else -> "Provider returned an empty feed."
            }
            return CatalogResult(diagnostic = diagnostic)
        }
        // Every row failed: show the common cause, not a silent blank screen.
        val allFailed = rows.all { it.posts.isEmpty() && it.error != null }
        return CatalogResult(
            rows = rows,
            diagnostic = if (allFailed) "No posts could be loaded. ${rows.first().error}" else null,
        )
    }

    suspend fun posts(filter: String, page: Int): List<VegaPost> =
        postsPage(filter, page).posts

    suspend fun postsPage(filter: String, page: Int): PostPage {
        val a = active() ?: return PostPage(emptyList(), 0)
        val familyOn = runCatching { settings.familyFilter.first() }.getOrDefault(true)
        if (familyOn && !FamilyFilter.isAllowedCatalog("", filter)) {
            return PostPage(emptyList(), 0)
        }
        val raw = pm.getPosts(a, filter, page)
        return PostPage(unique(raw, familyOn), raw.size)
    }

    suspend fun search(query: String, page: Int): List<VegaPost> {
        val a = active() ?: return emptyList()
        // Fan-out across installed providers comes later; active first (web
        // SearchResults fans out, but single-provider keeps weak boxes fast).
        val familyOn = runCatching { settings.familyFilter.first() }.getOrDefault(true)
        return unique(pm.getSearchPosts(a, query, page), familyOn)
    }

    private fun unique(posts: List<VegaPost>, familyOn: Boolean): List<VegaPost> {
        val seen = HashSet<String>(posts.size)
        return posts.filter { post ->
            (!familyOn || FamilyFilter.isAllowed(post)) &&
                seen.add(FamilyFilter.dedupeKey(post.title))
        }
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
