package com.company.callservice.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = lightColorScheme(
    primary = Color(0xFF2257D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF001849),
    surface = Color(0xFFFCF8FF),
    surfaceVariant = Color(0xFFE2E2EC),
    background = Color(0xFFF9F9FF),
)

@Composable
fun CompanyCallerIdTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
