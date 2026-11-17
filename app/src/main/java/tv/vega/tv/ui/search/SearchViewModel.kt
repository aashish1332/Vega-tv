package tv.vega.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.providers.readableProviderError
import tv.vega.tv.data.repo.SearchOutcome
import tv.vega.tv.ui.common.UiState

class SearchViewModel : ViewModel() {
    private val _state =
        MutableStateFlow<UiState<SearchOutcome>>(UiState.Success(SearchOutcome()))
    val state: StateFlow<UiState<SearchOutcome>> = _state
    private val _wafUrl = MutableStateFlow<String?>(null)
    val wafUrl: StateFlow<String?> = _wafUrl

    /** The query the current [state] belongs to (not what is in the field now). */
    private val _searchedQuery = MutableStateFlow("")
    val searchedQuery: StateFlow<String> = _searchedQuery

    private var job: Job? = null

    fun search(query: String) {
        val q = query.trim()
        // Keep the last results visible for a too-short query instead of
        // claiming "no results for" it.
        if (q.length < 2) return
        _searchedQuery.value = q
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = UiState.Loading
            _wafUrl.value = null
            runCatching {
                ServiceLocator.repository.search(q, 1) { partial ->
                    // Progressive fill: only while this is still the search the
                    // viewer is waiting for. A superseded search has its scope
                    // cancelled, so its late provider result is dropped here.
                    if (isActive) {
                        _wafUrl.value = partial.wafUrl
                        _state.value = UiState.Success(partial)
                    }
                }
            }
                .onSuccess { outcome ->
                    // A challenge on one provider is actionable, but it must not
                    // hide the rows other providers returned. Keep the URL so the
                    // results screen can offer "Solve challenge".
                    _wafUrl.value = outcome.wafUrl
                    _state.value = UiState.Success(outcome)
                }
                .onFailure { e ->
                    // Cancellation is not a search failure. Reporting it as one
                    // means a superseded search replaces the results the user is
                    // actually looking at with an error, and `job?.cancel()` at
                    // the top of the next call fires exactly that path.
                    if (e is CancellationException) return@launch
                    if (e is tv.vega.tv.data.providers.ProviderManager.WafRequired) {
                        _wafUrl.value = e.url.ifBlank { null }
                        _state.value = UiState.Error("Challenge required at ${e.host.ifBlank { "provider" }}")
                    } else {
                        _state.value = UiState.Error(
                            readableProviderError(e, fallback = "Search failed. Try again."),
                        )
                    }
                }
        }
    }
}
