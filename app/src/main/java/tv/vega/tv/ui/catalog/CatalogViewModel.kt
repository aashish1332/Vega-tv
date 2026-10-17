package tv.vega.tv.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator

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
        _posts.value = emptyList()
        _loading.value = true
        _loadingMore.value = false
        _error.value = null
        loadMore()
    }

    fun loadMore() {
        val f = filter ?: return
        if (exhausted || _loadingMore.value) return
        _loadingMore.value = true
        job?.cancel()
        job = viewModelScope.launch {
            runCatching { ServiceLocator.repository.posts(f, page + 1) }
                .onSuccess { newPage ->
                    if (newPage.isEmpty()) {
                        exhausted = true
                    } else {
                        page += 1
                        _posts.value = _posts.value + newPage
                    }
                    _error.value = null
                }
                .onFailure { e -> _error.value = e.message ?: "Failed to load" }
            _loading.value = false
            _loadingMore.value = false
        }
    }
}
