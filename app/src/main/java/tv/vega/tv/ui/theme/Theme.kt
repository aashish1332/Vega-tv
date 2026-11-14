package tv.vega.tv.ui.theme

import androidx.compose.material3.MaterialTheme as M3MaterialTheme
import androidx.compose.material3.darkColorScheme as M3DarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.tv.material3.LocalContentColor
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

/**
 * Mirror of the palette for the few androidx.compose.material3 widgets the TV
 * kit does not ship (text fields, the root Surface). Without it those widgets
 * fall back to the phone kit's *light* scheme: near-black text on a transparent
 * container over a black page, i.e. invisible settings fields.
 */
private val VegaM3Scheme = M3DarkColorScheme(
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

@Suppress("DEPRECATION")
@Composable
fun VegaTVTheme(content: @Composable () -> Unit) {
    // Weak-TV decision: always dark, no wallpaper dynamic colors, no font
    // download (system sans). Dynamic accent is opt-in in Settings and only
    // applies to hero/detail backdrop, never per-card.
    M3MaterialTheme(colorScheme = VegaM3Scheme) {
        MaterialTheme(colorScheme = VegaScheme) {
            // tv-material's Text resolves its default color through its own
            // LocalContentColor, whose fallback is Color.Black — and its
            // MaterialTheme never provides one. Buttons and Cards set it
            // themselves, which is why their labels were readable while every
            // bare Text on Settings was black-on-black.
            CompositionLocalProvider(LocalContentColor provides VegaOnSurface) {
                content()
            }
        }
    }
}
