package io.github.thamichele.smbcommander.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thamichele.smbcommander.R

/**
 * "Arancio UI" tokens (Specifica Arancio UI of the design canvas): warm greys, a main colour chosen in the settings
 * (orange by default), status colours that never follow the theme, IBM Plex Sans for text and JetBrains Mono for
 * titles and data.
 */
@Immutable
data class ArancioColors(
    val background: Color,
    val surface: Color,
    /** "surface-2": menus, dialogs, floating cards; in the dark theme depth comes mostly from this lighter ground. */
    val surfaceRaised: Color,
    /** Ground of the bottom bar. */
    val nav: Color,
    val border: Color,
    /** Outline of raised things (menus, dialogs, secondary buttons). */
    val borderStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    /** The main colour: switches, progress, selection, focus. */
    val accent: Color,
    /** Fill of primary buttons and the floating button. */
    val accentButton: Color,
    /** Text and icons on [accent] and [accentButton]. */
    val onAccent: Color,
    val accentHover: Color,
    /** Accent at 20%: pill of the active section, selected rows. */
    val accentSoft: Color,
    /** Accent at 10%: active menu entry. */
    val accentTint: Color,
    /** Links and accent text on the ground. */
    val accentText: Color,
    /** Text of accent tags. */
    val accentLabel: Color,
    val accentBorder: Color,
    val accentDeep: Color,
    val danger: Color,
    val dangerSoft: Color,
    val dangerBorder: Color,
    val success: Color,
    val successSoft: Color,
    val successBorder: Color,
    val warning: Color,
    val folder: Color,
    val image: Color,
    val media: Color,
    val document: Color,
    /** Shadow of level 1 (menus, toasts) and level 2 (dialogs). */
    val shadow: Color,
)

/** The colours of one ground (dark or light) with the chosen main colour. Greys and status colours never change. */
fun arancioColors(dark: Boolean, accent: AccentTheme): ArancioColors = if (dark) {
    ArancioColors(
        background = Color(0xFF1C1B1A),
        surface = Color(0xFF252422),
        surfaceRaised = Color(0xFF2E2C2A),
        nav = Color(0xFF1F1E1D),
        border = Color(0xFF3A3734),
        borderStrong = Color(0xFF45413D),
        text = Color(0xFFEDEAE3),
        textSecondary = Color(0xFFA39E95),
        textDisabled = Color(0xFF77726A),
        accent = accent.main,
        accentButton = accent.button,
        onAccent = accent.onButton,
        accentHover = accent.hover,
        accentSoft = accent.main.copy(alpha = 0.20f),
        accentTint = accent.main.copy(alpha = 0.10f),
        accentText = accent.text,
        accentLabel = accent.soft,
        accentBorder = accent.border,
        accentDeep = accent.deep,
        danger = Color(0xFFF0948A),
        dangerSoft = Color(0xFF3A2523),
        dangerBorder = Color(0xFF6B3631),
        success = Color(0xFF6CC4B6),
        successSoft = Color(0xFF1F2A27),
        successBorder = Color(0xFF2F4A44),
        warning = Color(0xFFE8C35A),
        folder = accent.icon,
        image = Color(0xFF5DB3A6),
        media = Color(0xFFB8A3E0),
        document = Color(0xFF8FB3E3),
        shadow = Color.Black,
    )
} else {
    ArancioColors(
        background = Color(0xFFF6F4EF),
        surface = Color(0xFFFFFFFF),
        surfaceRaised = Color(0xFFEFECE6),
        nav = Color(0xFFFFFFFF),
        border = Color(0xFFDDD8CF),
        borderStrong = Color(0xFFCFC9BE),
        text = Color(0xFF22201D),
        textSecondary = Color(0xFF6B665E),
        textDisabled = Color(0xFF9A948A),
        accent = accent.light,
        accentButton = accent.light,
        onAccent = Color(0xFFFFFFFF),
        accentHover = accent.light,
        accentSoft = accent.light.copy(alpha = 0.12f),
        accentTint = accent.light.copy(alpha = 0.10f),
        accentText = accent.light,
        accentLabel = accent.light,
        accentBorder = accent.light.copy(alpha = 0.30f),
        accentDeep = accent.light.copy(alpha = 0.10f),
        danger = Color(0xFFA8362B),
        dangerSoft = Color(0xFFF8E1DE),
        dangerBorder = Color(0xFFE8B8B2),
        success = Color(0xFF1F6B60),
        successSoft = Color(0xFFDDF0EC),
        successBorder = Color(0xFFB5DDD5),
        warning = Color(0xFF8A6A00),
        folder = accent.light,
        image = Color(0xFF2F7F73),
        media = Color(0xFF6F58A8),
        document = Color(0xFF3D6BA8),
        shadow = Color(0x33000000),
    )
}

val DarkArancio = arancioColors(dark = true, accent = AccentTheme.Arancio)

val LocalArancio = staticCompositionLocalOf { DarkArancio }

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

/** JetBrains Mono (variable font): page and section titles, numbers, sizes, dates in lists, addresses, paths, hashes. */
@OptIn(ExperimentalTextApi::class)
val JetBrainsMono = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.jetbrains_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0, family: FontFamily = PlexSans) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = (tracking * size).sp,
)

private val ArancioTypography = Typography(
    // titles in JetBrains Mono, like h1/h2 of the kit
    displaySmall = style(32, FontWeight.Bold, 40, -0.02, JetBrainsMono),
    headlineMedium = style(26, FontWeight.SemiBold, 32, -0.02, JetBrainsMono),
    headlineSmall = style(22, FontWeight.SemiBold, 28, -0.01, JetBrainsMono),
    titleLarge = style(20, FontWeight.SemiBold, 26, family = JetBrainsMono),
    titleMedium = style(17, FontWeight.SemiBold, 24),
    titleSmall = style(15, FontWeight.Medium, 20),
    bodyLarge = style(15, FontWeight.Normal, 22),
    bodyMedium = style(14, FontWeight.Normal, 20),
    bodySmall = style(13, FontWeight.Normal, 18),
    labelLarge = style(14, FontWeight.SemiBold, 20),
    labelMedium = style(13, FontWeight.Medium, 18),
    labelSmall = style(12, FontWeight.Medium, 16),
)

/** Text styles of the kit that Material has no slot for. */
object ArancioType {
    /** Small uppercase heading over a group of settings or cards ("COLORI · TEMA SCURO"). */
    val section = style(12, FontWeight.SemiBold, 16, 0.08, JetBrainsMono)
    /** Data in a secondary line: sizes, dates, counts. */
    val data = style(12, FontWeight.Normal, 18, family = JetBrainsMono)
    /** Addresses, paths, hashes at reading size. */
    val code = style(13, FontWeight.Normal, 20, family = JetBrainsMono)
    /** A big number in a card. */
    val figure = style(22, FontWeight.Medium, 28, -0.01, JetBrainsMono)
}

/** Corners of the kit: buttons and fields 4, menus and toasts 8, cards, panels and dialogs 12, tags as pills. */
object ArancioShape {
    val control = RoundedCornerShape(4.dp)
    val menu = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(12.dp)
    val pill = RoundedCornerShape(999.dp)
    /** The floating button, a little rounder than the buttons. */
    val fab = RoundedCornerShape(6.dp)
}

private val ArancioShapes = Shapes(
    extraSmall = ArancioShape.control,
    small = ArancioShape.control,
    medium = ArancioShape.card,
    large = ArancioShape.card,
    extraLarge = ArancioShape.card,
)

@Composable
fun ArancioTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val accent by remember(context) { AccentStore.state(context) }.collectAsState()
    val c = remember(dark, accent) { arancioColors(dark, accent) }
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.text,
            secondary = c.accentText, onSecondary = c.onAccent,
            background = c.background, onBackground = c.text,
            surface = c.background, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.background, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceRaised, surfaceContainerHighest = c.surfaceRaised,
            outline = c.borderStrong, outlineVariant = c.border, error = c.danger, onError = c.background,
            inverseSurface = c.surfaceRaised, inverseOnSurface = c.text, inversePrimary = c.accentText,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.text,
            secondary = c.accentText, onSecondary = c.onAccent,
            background = c.background, onBackground = c.text,
            surface = c.background, onSurface = c.text, surfaceVariant = c.surface, onSurfaceVariant = c.textSecondary,
            surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceRaised, surfaceContainerHighest = c.surfaceRaised,
            outline = c.borderStrong, outlineVariant = c.border, error = c.danger, onError = c.background,
            inverseSurface = c.surfaceRaised, inverseOnSurface = c.text, inversePrimary = c.accentText,
        )
    }
    val selection = remember(c) { TextSelectionColors(handleColor = c.accent, backgroundColor = c.accent.copy(alpha = 0.30f)) }
    CompositionLocalProvider(LocalArancio provides c, LocalTextSelectionColors provides selection) {
        MaterialTheme(colorScheme = scheme, typography = ArancioTypography, shapes = ArancioShapes, content = content)
    }
}

object Arancio {
    val colors: ArancioColors
        @Composable get() = LocalArancio.current
}
