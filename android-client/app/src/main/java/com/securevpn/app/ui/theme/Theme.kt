package com.securevpn.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SecureColorScheme = darkColorScheme(
    primary = AccentBlue,
    secondary = AccentPurple,
    tertiary = AccentGreen,
    surface = CardSurface,
    background = BackgroundStart,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun SecureVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SecureColorScheme,
        typography = Typography,
        content = content
    )
}

