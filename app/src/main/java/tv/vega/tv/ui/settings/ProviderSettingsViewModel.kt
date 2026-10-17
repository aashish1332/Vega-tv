package tv.vega.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.providers.SettingsField
import tv.vega.tv.ui.common.UiState

/**
 * One editable control, holding the value the user has chosen.
 *
 * The value is kept in the same JSON shape the provider declares rather than in
 * a Kotlin type chosen by the field's `type`, so "what is stored" and "what is
 * shown" cannot drift apart as controls are added.
 */
data class SettingsControl(
    val field: SettingsField,
    val value: String,
    val selected: List<String>,
)

data class ProviderSettingsData(
    val provider: String,
    val controls: List<SettingsControl>,
    val modified: Set<String>,
)

/**
 * Loads a provider's own settings and persists edits.
 *
 * The contract this depends on was read out of the provider bundles rather than
 * assumed: a field's `key` *is* the kvStore key, and the provider reads it back
 * from its own modules. `providers/4khdhub/settings.ts` declares
 * `4khdhub_quickDownload`, and `providers/4khdhub/meta.ts` then calls
 * `providerContext.kvStore?.get<boolean>("4khdhub_quickDownload")`. Writing
 * these to a separate app settings file would therefore save values no provider
 * ever reads, and the setting would appear to do nothing.
 */
class ProviderSettingsViewModel(private val providerValue: String) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ProviderSettingsData>>(UiState.Loading)
    val state: StateFlow<UiState<ProviderSettingsData>> = _state

    private val _saved = MutableStateFlow<String?>(null)
    val saved: StateFlow<String?> = _saved

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private var loadJob: Job? = null

    /**
     * The provider whose schema was actually loaded.
     *
     * Writes go here rather than to the constructor argument: the schema and the
     * store must describe the same provider, and `load()` is what resolves
     * which one is active. Writing to a stale argument would save into a
     * provider that is no longer selected.
     */
    private var loadedProvider: String = providerValue

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = UiState.Loading
            _saved.value = null
            runCatching {
                val m = ServiceLocator.providers
                val active = m.active() ?: error("No active provider")
                loadedProvider = active.ext.value
                val schema = m.getSettingsSchema(active)
                val stored = m.kv.snapshotFor(loadedProvider)
                ProviderSettingsData(
                    provider = active.ext.display_name.ifBlank { loadedProvider },
                    controls = schema.map { it.toControl(stored[it.key]) },
                    // Only fields that carry a stored value count as modified;
                    // the rest are showing the provider's own default.
                    modified = schema.map { it.key }.filterTo(mutableSetOf()) { stored[it] != null },
                )
            }.onSuccess { _state.value = UiState.Success(it) }
                .onFailure { e ->
                    if (e is CancellationException) return@launch
                    _state.value = UiState.Error(e.message ?: "Could not read settings")
                }
        }
    }

    /** Persist one control, mirroring the encoding `kvStore.set` applies. */
    fun set(c: SettingsControl) {
        val m = ServiceLocator.providers
        viewModelScope.launch {
            val encoded = encode(c)
            runCatching {
                m.kv.set(loadedProvider, c.field.key, encoded)
                m.js.applyExternalKvWrite(loadedProvider, c.field.key, encoded)
            }.onSuccess { _saved.value = c.field.label }
                .onFailure { _status.value = "Could not save: ${it.message}" }
        }
    }

    fun reset(c: SettingsControl) {
        val m = ServiceLocator.providers
        viewModelScope.launch {
            runCatching {
                m.kv.delete(loadedProvider, c.field.key)
                m.js.applyExternalKvDelete(loadedProvider, c.field.key)
            }.onSuccess { _saved.value = c.field.label }
        }
    }

    fun clearAll() {
        val m = ServiceLocator.providers
        viewModelScope.launch {
            runCatching { m.kv.clear(loadedProvider) }
                .onSuccess { _status.value = "Reset to provider defaults"; load() }
        }
    }

    fun dismiss() {
        _saved.value = null
        _status.value = null
    }

    /**
     * What actually goes in the store.
     *
     * Strings are stored verbatim and everything else as JSON text, because
     * that is exactly what the runtime's `kvStore.set` does — anything else
     * would make a value this screen wrote unreadable to the provider that
     * asked for it.
     */
    private fun encode(c: SettingsControl): String = when (c.field.type) {
        SettingsField.TOGGLE -> c.value
        SettingsField.MULTISELECT -> JsonArray(c.selected.map { JsonPrimitive(it) }).toString()
        else -> c.value
    }
}

/** Build the control for [this] field, preferring a stored value over the default. */
private fun SettingsField.toControl(stored: String?): SettingsControl {
    fun storedOr(block: () -> String): String =
        stored ?: runCatching { block() }.getOrDefault("")

    return when (type) {
        SettingsField.TOGGLE -> SettingsControl(this, storedOr { defaultAsBool()?.toString() ?: "false" }, emptyList())
        SettingsField.MULTISELECT -> SettingsControl(
            this,
            "",
            stored?.let { s -> decodeList(s) } ?: defaultAsStringList(),
        )
        SettingsField.NUMBER -> SettingsControl(this, storedOr { defaultAsDouble()?.toString() ?: "" }, emptyList())
        else -> SettingsControl(this, storedOr { defaultAsString() ?: "" }, emptyList())
    }
}

/** Read a stored multiselect back into labels, falling back to raw values. */
private fun decodeList(stored: String): List<String> {
    val t = stored.trim()
    if (!t.startsWith("[")) return listOf(stored)
    val sb = StringBuilder()
    var inStr = false
    var esc = false
    for (ch in t) {
        when {
            esc -> { sb.append(ch); esc = false }
            ch == '\\' && inStr -> esc = true
            ch == '"' -> inStr = !inStr
            inStr -> sb.append(ch)
        }
    }
    if (sb.isEmpty()) return listOf(stored)
    return sb.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
