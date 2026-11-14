package tv.vega.tv.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.repo.FamilyFilter

/**
 * One catalog row, paginated.
 *
 * The home screen shows page 1 of each row and a "See all" button that led
 * nowhere — the data layer has always had `posts(filter, page)`, there was just
 * nothing to navigate to. This view model is what that button needs: it keeps
 * appending pages until a provider returns an empty page, which is how a
 * provider says "that was all of them".
 */
class CatalogViewModel : ViewModel() {
    private val _posts = MutableStateFlow<List<tv.vega.tv.data.model.VegaPost>>(emptyList())
    val posts: StateFlow<List<tv.vega.tv.data.model.VegaPost>> = _posts

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    private var filter: String? = null
    private var job: Job? = null
    private var page = 0
    private var exhausted = false
    private val seenTitles = HashSet<String>()

    fun start(filter: String) {
        // A screen can re-enter (rotation, back-and-forth) with the same filter;
        // re-fetching would restart the scroll for nothing.
        if (this.filter == filter) return
        this.filter = filter
        // A page for the old filter may still be in flight; cancel it and clear
        // the guard flags, or loadMore() below would refuse to start and the
        // new grid would stay empty forever.
        job?.cancel()
        page = 0
        exhausted = false
        seenTitles.clear()
        _posts.value = emptyList()
        _loading.value = true
        _loadingMore.value = false
        _error.value = null
        loadMore()
    }

    /**
     * Stop a page fetch when the viewer opens a title. The grid stays in the
     * back stack while a film plays, and a catalog request must not share the
     * network with the video.
     */
    fun cancelPending() {
        job?.cancel()
        job = null
        _loading.value = false
        _loadingMore.value = false
    }

    fun loadMore() {
        val f = filter ?: return
        if (exhausted || _loadingMore.value) return
        _loadingMore.value = true
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val newPage = ServiceLocator.repository.postsPage(f, page + 1)
                if (newPage.rawCount == 0) {
                    exhausted = true
                } else {
                    page += 1
                    // Paginated provider feeds repeat a title on more than one
                    // page. Repository filtering is per page, so remember what
                    // this grid has already shown. A page emptied by the family
                    // filter is not the end of the provider's feed.
                    val fresh = newPage.posts.filter { seenTitles.add(FamilyFilter.dedupeKey(it.title)) }
                    if (fresh.isNotEmpty()) _posts.value = _posts.value + fresh
                }
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _error.value = e.message ?: "Failed to load"
            } finally {
                _loading.value = false
                _loadingMore.value = false
            }
        }
    }
}
