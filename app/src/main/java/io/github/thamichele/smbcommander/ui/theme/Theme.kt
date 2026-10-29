package io.github.thamichele.smbcommander.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thamichele.smbcommander.R

/** "Arancio UI" tokens: warm dark ground, orange accent, IBM Plex Sans. */
@Immutable
data class ArancioColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val accentText: Color,
    val danger: Color,
    val success: Color,
    val folder: Color,
    val image: Color,
    val media: Color,
    val document: Color,
)

val DarkArancio = ArancioColors(
    background = Color(0xFF1C1B1A),
    surface = Color(0xFF252422),
    surfaceRaised = Color(0xFF2E2C2A),
    border = Color(0xFF3A3734),
    text = Color(0xFFEDEAE3),
    textSecondary = Color(0xFFA39E95),
    accent = Color(0xFFE3845A),
    onAccent = Color(0xFF1C1B1A),
    accentSoft = Color(0x33E3845A),
    accentText = Color(0xFFEC976F),
    danger = Color(0xFFF0948A),
    success = Color(0xFF8DBA7C),
    folder = Color(0xFFE3845A),
    image = Color(0xFF5DB3A6),
    media = Color(0xFFB8A3E0),
    document = Color(0xFF8FB3E3),
)

val LightArancio = ArancioColors(
    background = Color(0xFFF6F4EF),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEFECE6),
    border = Color(0xFFDDD8CF),
    text = Color(0xFF1C1B1A),
    textSecondary = Color(0xFF6B665E),
    accent = Color(0xFFB4572F),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFF3E2D9),
    accentText = Color(0xFFB4572F),
    danger = Color(0xFFB4382E),
    success = Color(0xFF4F7A40),
    folder = Color(0xFFC96F48),
    image = Color(0xFF2F7F73),
    media = Color(0xFF6F58A8),
    document = Color(0xFF3D6BA8),
)

val LocalArancio = staticCompositionLocalOf { DarkArancio }

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = PlexSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = (tracking * size).sp,
)

private val ArancioTypography = Typography(
    displaySmall = style(32, FontWeight.SemiBold, 40, -0.02),
    headlineMedium = style(26, FontWeight.SemiBold, 32, -0.02),
    headlineSmall = style(22, FontWeight.SemiBold, 28, -0.01),
    titleLarge = style(20, FontWeight.SemiBold, 26),
    titleMedium = style(17, FontWeight.SemiBold, 24),
    titleSmall = style(15, FontWeight.Medium, 20),
    bodyLarge = style(15, FontWeight.Normal, 22),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(13, FontWeight.Normal, 18),
    labelLarge = style(14, FontWeight.SemiBold, 20),
    labelMedium = style(13, FontWeight.Medium, 18),
    labelSmall = style(12, FontWeight.Medium, 16),
)

private val ArancioShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun ArancioTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkArancio else LightArancio
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.text,
            secondary = c.accentText, onSecondary = c.onAccent,
            background = c.background, onBackground = c.text,
            surface = c.background, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.background, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceRaised, surfaceContainerHighest = c.surfaceRaised,
            outline = c.border, outlineVariant = c.border, error = c.danger, onError = c.onAccent,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.text,
            secondary = c.accentText, onSecondary = c.onAccent,
            background = c.background, onBackground = c.text,
            surface = c.background, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceRaised, surfaceContainerHighest = c.surfaceRaised,
            outline = c.border, outlineVariant = c.border, error = c.danger, onError = c.onAccent,
        )
    }
    CompositionLocalProvider(LocalArancio provides c) {
        MaterialTheme(colorScheme = scheme, typography = ArancioTypography, shapes = ArancioShapes, content = content)
    }
}

object Arancio {
    val colors: ArancioColors
        @Composable get() = LocalArancio.current
}
