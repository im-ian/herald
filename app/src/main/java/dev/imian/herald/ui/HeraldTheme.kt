package dev.imian.herald.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val HeraldInk = Color(0xFF17211D)
val HeraldCream = Color(0xFFF7F4EC)
val HeraldPaper = Color(0xFFFFFCF4)
val HeraldGold = Color(0xFFF0BC52)
val HeraldGreen = Color(0xFF2F6B4F)
val HeraldMuted = Color(0xFF647069)
val HeraldLine = Color(0xFFD8D8CC)
val HeraldError = Color(0xFFBA3D3D)

private val LightColors = lightColorScheme(
    primary = HeraldGreen,
    onPrimary = Color.White,
    secondary = HeraldGold,
    onSecondary = HeraldInk,
    background = HeraldCream,
    onBackground = HeraldInk,
    surface = HeraldPaper,
    onSurface = HeraldInk,
    surfaceVariant = Color(0xFFECECE2),
    onSurfaceVariant = HeraldMuted,
    outline = HeraldLine,
    error = HeraldError,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83C9A4),
    secondary = HeraldGold,
    background = Color(0xFF101713),
    surface = Color(0xFF18211D),
    surfaceVariant = Color(0xFF26312B),
    outline = Color(0xFF4E5D55),
)

@Composable
fun HeraldTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
