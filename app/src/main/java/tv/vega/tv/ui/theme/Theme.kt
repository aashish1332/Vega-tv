package tv.vega.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val VegaScheme = darkColorScheme(
    primary = VegaPrimary,
    onPrimary = VegaOnPrimary,
    background = VegaBackground,
    onBackground = VegaOnSurface,
    surface = VegaSurface,
    onSurface = VegaOnSurface,
    surfaceVariant = VegaSurfaceHigh,
    onSurfaceVariant = VegaOnVariant,
    error = VegaError,
)

@Composable
fun VegaTVTheme(content: @Composable () -> Unit) {
    // Weak-TV decision: always dark, no wallpaper dynamic colors, no font
    // download (system sans). Dynamic accent is opt-in in Settings and only
    // applies to hero/detail backdrop, never per-card.
    MaterialTheme(colorScheme = VegaScheme, content = content)
}
