package com.ration.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Green = Color(0xFF2E7D32)
private val LightColors = lightColorScheme(primary = Green, secondary = Color(0xFF556B2F), tertiary = Color(0xFF00696E))
private val DarkColors = darkColorScheme(primary = Color(0xFF81C784), secondary = Color(0xFFB5CC8E), tertiary = Color(0xFF4FD8DE))

object LevelColors {
    val green = Color(0xFF2E7D32)
    val yellow = Color(0xFFF9A825)
    val red = Color(0xFFC62828)
    val grey = Color(0xFF9E9E9E)
}

@Composable
fun RationTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
