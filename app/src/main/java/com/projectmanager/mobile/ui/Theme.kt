package com.projectmanager.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0A84FF).copy(alpha = 0.20f),
    onPrimaryContainer = Color(0xFFB8D9FF),
    secondary = Color(0xFF30D158),
    onSecondary = Color(0xFF08210F),
    secondaryContainer = Color(0xFF30D158).copy(alpha = 0.20f),
    onSecondaryContainer = Color(0xFFA8EDB4),
    tertiary = Color(0xFFFF9F0A),
    background = Color(0xFF1C1C1E),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF242426),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFC7C7CC),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF545458),
    outlineVariant = Color(0xFF3A3A3C),
    error = Color(0xFFFF453A),
    onError = Color.White,
    scrim = Color.Black
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = Color(0xFF003A66),
    secondary = Color(0xFF34C759),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7F5DC),
    onSecondaryContainer = Color(0xFF0B3D1A),
    tertiary = Color(0xFFFF9500),
    background = Color(0xFFFDFDFE),
    onBackground = Color(0xFF1D1D1F),
    surface = Color.White,
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF5F5F64),
    surfaceContainerHigh = Color(0xFFF7F7FA),
    surfaceContainerHighest = Color(0xFFEDEDF2),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFFF3B30),
    onError = Color.White,
    scrim = Color.Black
)

@Composable
fun PmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

@Composable
fun successColor(): Color = if (isSystemInDarkTheme()) Color(0xFF30D158) else Color(0xFF248A3D)

@Composable
fun warningColor(): Color = if (isSystemInDarkTheme()) Color(0xFFFF9F0A) else Color(0xFFC93400)

@Composable
fun successTint(): Color = successColor().copy(alpha = if (isSystemInDarkTheme()) 0.20f else 0.14f)

@Composable
fun warningTint(): Color = warningColor().copy(alpha = if (isSystemInDarkTheme()) 0.20f else 0.14f)
