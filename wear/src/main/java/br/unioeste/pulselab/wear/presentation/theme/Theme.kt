package br.unioeste.pulselab.wear.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

private val PulseLabWearColorScheme = ColorScheme(
    primary = PulseLabColors.Green,
    primaryDim = PulseLabColors.GreenDark,
    onPrimary = PulseLabColors.Background,
    onPrimaryContainer = PulseLabColors.Background,
    surfaceContainerLow = PulseLabColors.Card,
    surfaceContainer = PulseLabColors.Card,
    surfaceContainerHigh = PulseLabColors.Card,
    onSurface = PulseLabColors.TextPrimary,
    onSurfaceVariant = PulseLabColors.TextSecondary,
    outline = PulseLabColors.Outline,
    outlineVariant = PulseLabColors.CardBorder,
    background = PulseLabColors.Background,
    onBackground = PulseLabColors.TextPrimary,
    error = PulseLabColors.Error,
    onError = PulseLabColors.Background,
)

@Composable
fun PulseLabWearTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PulseLabWearColorScheme,
        content = content
    )
}
