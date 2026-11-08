package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SonixColorScheme = darkColorScheme(
    primary = SonixFocusBlue,
    onPrimary = SonixBgScreen,
    secondary = SonixAccentGold,
    onSecondary = SonixBgScreen,
    tertiary = SonixTextSilver,
    background = SonixBgScreen,
    onBackground = SonixTextWhite,
    surface = SonixBgPanel,
    onSurface = SonixTextWhite,
    surfaceVariant = SonixSelectedItemBg,
    onSurfaceVariant = SonixTextSilver,
    outline = SonixBorderPanel,
    error = SonixDangerRed,
    onError = SonixTextWhite
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SonixColorScheme,
        typography = Typography,
        content = content
    )
}
