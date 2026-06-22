package br.unioeste.pulselab.wear.presentation.theme

import androidx.compose.ui.graphics.Color

// Mirrors src/theme/colors.ts (COLORS) from the pulselab-companion RN app,
// so the watch face matches the phone app's black + neon-green identity.
object PulseLabColors {
    val Background = Color(0xFF000000)
    val Card = Color(0xFF111111)
    val CardBorder = Color(0x14FFFFFF) // rgba(255,255,255,0.08)
    val Outline = Color(0x26FFFFFF) // rgba(255,255,255,0.15)

    val Green = Color(0xFF39FF14)
    val GreenDark = Color(0xFF2ECC10)

    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF888888)

    val Error = Color(0xFFF87171)
    val Warning = Color(0xFFF59E0B)
}
