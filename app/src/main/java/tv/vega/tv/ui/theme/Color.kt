package tv.vega.tv.ui.theme

import androidx.compose.ui.graphics.Color

// Exact Vega brand parity (tailwind.config / seeds.ts):
// primary tomato, legacy tertiary #171717, onSurface #F5F0EF.
val VegaPrimary = Color(0xFFFF6347)
val VegaOnPrimary = Color(0xFF17100F)
// AMOLED black: OLED pixels are truly off at #000000, so the page disappears
// into the panel edge (and every dark scene does too). Elevated containers
// below are deliberately *not* black, or cards would vanish on a TV in a
// bright room.
val VegaBackground = Color(0xFF000000)
val VegaSurface = Color(0xFF000000)
val VegaSurfaceLow = Color(0xFF120B09)
val VegaSurfaceContainer = Color(0xFF1A100D)
val VegaSurfaceHigh = Color(0xFF211612)
val VegaSurfaceHighest = Color(0xFF2B1C17)
val VegaOnSurface = Color(0xFFF5F0EF)
val VegaOnVariant = Color(0xFFD4CBC9)
val VegaOutline = Color(0xFF9E9290)
val VegaOutlineVariant = Color(0xFF3A2D29)
val VegaTertiary = Color(0xFF171717) // legacy chip bg
val VegaError = Color(0xFFFFB4AB)

// TV-only: opaque scrims instead of BlurView (Mali/Adreno weak GPUs).
val ScrimHeavy = Color(0xCC000000)
val ScrimTag = Color(0xA6000000)
val ProgressTrack = Color(0xFF2B1C17)
