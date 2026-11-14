package tv.vega.tv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppVisibility {
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground

    var isForeground: Boolean
        get() = _foreground.value
        set(value) {
            _foreground.value = value
        }
}
