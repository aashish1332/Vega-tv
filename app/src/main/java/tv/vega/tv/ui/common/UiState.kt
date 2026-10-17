package tv.vega.tv.ui.common

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String) : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
}

/** Same aspect buckets as web Slider.tsx: >1.2 landscape, >0.85 square. */
fun cardWidthForRatio(ratio: Float): Int = when {
    ratio > 1.2f -> 220 // TV: keep 220, grids scale via columns not card growth
    ratio > 0.85f -> 150
    else -> 124
}

fun parseAspectRatio(raw: String?, fallback: Float = 2f / 3f): Float {
    if (raw.isNullOrBlank()) return fallback
    val t = raw.trim()
    if (":" in t) {
        val (w, h) = t.split(":").mapNotNull { it.toFloatOrNull() }
        if (w > 0 && h > 0) return w / h
    }
    if ("/" in t) {
        val (w, h) = t.split("/").mapNotNull { it.toFloatOrNull() }
        if (w > 0 && h > 0) return w / h
    }
    t.toFloatOrNull()?.takeIf { it > 0 }?.let { return it }
    return fallback
}
