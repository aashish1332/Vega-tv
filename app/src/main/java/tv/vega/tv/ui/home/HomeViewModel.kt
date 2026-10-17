package tv.vega.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.history.WatchEntry
import tv.vega.tv.data.repo.CatalogResult
import tv.vega.tv.ui.common.UiState

class HomeViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState<CatalogResult>>(UiState.Loading)
    val state: StateFlow<UiState<CatalogResult>> = _state

    private val _continue = MutableStateFlow<List<WatchEntry>>(emptyList())
    val continueWatching: StateFlow<List<WatchEntry>> = _continue

    private val _update = MutableStateFlow<String?>(null)
    val updateAvailable: StateFlow<String?> = _update

    init { load() }

    fun load() {
        viewModelScope.launch {
            // A refresh must not blank the screen: the skeleton is only for the
            // very first load, and rows swap in place after that.
            if (_state.value !is UiState.Success) _state.value = UiState.Loading
            // Same gate as web Tutorial: seed default source + install provider
            // on first run so the app works out of the box.
            runCatching { ServiceLocator.repository.catalogRows() }
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { _state.value = UiState.Error(it.message ?: "Failed to load") }
            // Shared-folder sync on Home focus (mirrors web focus sync).
            runCatching { ServiceLocator.sync.syncFromSharedFolder() }
            // Update check on start (mirrors web autoCheckUpdate).
            runCatching { checkUpdateOnce() }
        }
        viewModelScope.launch {
            ServiceLocator.history.items.collect { _continue.value = it }
        }
    }

    private suspend fun checkUpdateOnce() {
        if (checkedUpdate) return
        checkedUpdate = true
        if (!ServiceLocator.settings.autoCheckUpdate.first()) return
        val tag = runCatching { tv.vega.tv.data.update.UpdateApi.latest().tag }.getOrNull().orEmpty()
        if (tag.isNotBlank()) _update.value = tag
    }

    fun dismissUpdate() {
        _update.value = null
    }

    fun refresh() = load()

    companion object {
        @Volatile
        private var checkedUpdate = false
    }
}
