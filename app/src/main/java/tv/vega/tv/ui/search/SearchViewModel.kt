package tv.vega.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.ui.common.UiState

class SearchViewModel : ViewModel() {
    private val _state =
        MutableStateFlow<UiState<List<VegaPost>>>(UiState.Success(emptyList()))
    val state: StateFlow<UiState<List<VegaPost>>> = _state
    private val _wafUrl = MutableStateFlow<String?>(null)
    val wafUrl: StateFlow<String?> = _wafUrl
    private var job: Job? = null

    fun search(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = UiState.Loading
            _wafUrl.value = null
            runCatching { ServiceLocator.repository.search(q, 1) }
                .onSuccess { _state.value = UiState.Success(it) }
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
                        _state.value = UiState.Error(e.message ?: "Search failed")
                    }
                }
        }
    }
}
