package com.winlator.cmod.shared.theme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.R

val WinNativeBackground = Color(0xFF0E141B)
val WinNativeSurface = Color(0xFF23262E)
val WinNativeSurfaceAlt = Color(0xFF353A44)
val WinNativePanel = Color(0xFF1B1F27)
val WinNativeOutline = Color(0xFF454B55)
val WinNativeAccent = Color(0xFF1A9FFF)
val WinNativeTextPrimary = Color(0xFFF4F4F4)
val WinNativeTextSecondary = Color(0xFFB8BCBF)
val WinNativeFocus = Color.White
/** Play action: a brighter, modern green. The gradient pair is used where the action is drawn as a hero button. */
val WinNativePlay = Color(0xFF3FA83A)
val WinNativePlayHighlight = Color(0xFF6CCB4A)
val WinNativePlayShade = Color(0xFF2E8A2C)
val WinNativeDanger = Color(0xFFFF7A88)
/** Soft accent used for focus halos and selected surfaces. */
val WinNativeAccentGlow = Color(0xFF58B4FF)

// Surface steps for settings, sheets and secondary screens. They replace the purple-tinted
// palette inherited from WinNative (#11111C / #1C1C2A / #2A2A3A ...) so every screen
// shares the shell's blue-slate hue.
/** Recessed panel (list wells, inset cards). */
val WinNativePanelDeep = Color(0xFF141A21)
/** Raised tile (icon boxes, chips on panels). */
val WinNativeSurfaceRaised = Color(0xFF232B35)
/** Hairline borders and dividers on dark panels. */
val WinNativeOutlineSoft = Color(0xFF2B323C)

private val WinNativeColorScheme =
    darkColorScheme(
        primary = WinNativeAccent,
        onPrimary = WinNativeBackground,
        primaryContainer = Color(0xFF1B3853),
        onPrimaryContainer = WinNativeTextPrimary,
        secondary = WinNativeTextSecondary,
        onSecondary = WinNativeBackground,
        secondaryContainer = WinNativeSurfaceAlt,
        onSecondaryContainer = WinNativeTextPrimary,
        tertiary = WinNativeAccent,
        onTertiary = WinNativeBackground,
        tertiaryContainer = WinNativeSurfaceAlt,
        onTertiaryContainer = WinNativeTextPrimary,
        surfaceVariant = WinNativeSurfaceAlt,
        onSurfaceVariant = WinNativeTextSecondary,
        surfaceTint = Color.Transparent,
        surfaceDim = WinNativeBackground,
        surfaceBright = WinNativeSurfaceAlt,
        surfaceContainerLowest = WinNativeBackground,
        surfaceContainerLow = WinNativePanel,
        surfaceContainer = WinNativeSurface,
        surfaceContainerHigh = WinNativeSurfaceAlt,
        surfaceContainerHighest = WinNativeSurfaceAlt,
        outline = WinNativeOutline,
        outlineVariant = WinNativeOutline,
        background = WinNativeBackground,
        surface = WinNativeSurface,
        onSurface = WinNativeTextPrimary,
        onBackground = WinNativeTextPrimary,
    )

// Material components (cards, dialogs, menus, sheets) take their corners from here.
// Softer, modern radii; explicit component shapes elsewhere are unaffected.
private val WinNativeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Body and label text. Only the Inter Medium file is bundled, so every weight maps to it. */
val WinNativeFontFamily =
    FontFamily(
        Font(R.font.inter_medium, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_medium, FontWeight.SemiBold),
        Font(R.font.inter_medium, FontWeight.Bold),
    )

/**
 * Display and title text. Bricolage Grotesque ships with real weights, so headings get an
 * actual bold instead of the single Inter Medium face.
 */
val VaporDisplayFontFamily =
    FontFamily(
        Font(R.font.bricolage_grotesque_light, FontWeight.Light),
        Font(R.font.bricolage_grotesque_regular, FontWeight.Normal),
        Font(R.font.bricolage_grotesque_medium, FontWeight.Medium),
        Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
        Font(R.font.bricolage_grotesque_bold, FontWeight.Bold),
        Font(R.font.bricolage_grotesque_extrabold, FontWeight.ExtraBold),
    )

private val BaseTypography = Typography()

private fun TextStyle.display(weight: FontWeight, tracking: Double = -0.01): TextStyle =
    copy(fontFamily = VaporDisplayFontFamily, fontWeight = weight, letterSpacing = (fontSize.value * tracking).sp)

val WinNativeTypography =
    Typography(
        displayLarge = BaseTypography.displayLarge.display(FontWeight.ExtraBold, -0.02),
        displayMedium = BaseTypography.displayMedium.display(FontWeight.ExtraBold, -0.02),
        displaySmall = BaseTypography.displaySmall.display(FontWeight.Bold, -0.015),
        headlineLarge = BaseTypography.headlineLarge.display(FontWeight.Bold, -0.015),
        headlineMedium = BaseTypography.headlineMedium.display(FontWeight.Bold),
        headlineSmall = BaseTypography.headlineSmall.display(FontWeight.Bold),
        titleLarge = BaseTypography.titleLarge.display(FontWeight.SemiBold),
        titleMedium = BaseTypography.titleMedium.display(FontWeight.SemiBold, 0.0),
        titleSmall = BaseTypography.titleSmall.display(FontWeight.SemiBold, 0.0),
        bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = WinNativeFontFamily),
        bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = WinNativeFontFamily),
        bodySmall = BaseTypography.bodySmall.copy(fontFamily = WinNativeFontFamily),
        labelLarge = BaseTypography.labelLarge.copy(fontFamily = WinNativeFontFamily),
        labelMedium = BaseTypography.labelMedium.copy(fontFamily = WinNativeFontFamily),
        labelSmall = BaseTypography.labelSmall.copy(fontFamily = WinNativeFontFamily),
    )

@Composable
fun WinNativeTheme(
    colorScheme: ColorScheme = WinNativeColorScheme,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = WinNativeTypography,
        shapes = WinNativeShapes,
        content = content,
    )
}
