package io.github.kdiep12.reddot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RedDotRed = Color(0xFFDB2121)          // looks.ts "idle"
val RedDotSpeech = Color(0xFFFF0505)       // looks.ts "speech"

/** Light by day (readable in the sun), dark when the phone is in dark mode. */
@Composable
fun RedDotTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFFF6B5E), onPrimary = Color.Black, background = Color(0xFF141416),
            surface = Color(0xFF141416))
    } else {
        lightColorScheme(primary = RedDotRed, onPrimary = Color.White)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
