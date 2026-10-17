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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
val WinNativePlay = Color(0xFF5C7E10)
val WinNativeDanger = Color(0xFFFF7A88)

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

private val WinNativeShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(4.dp),
)

val WinNativeFontFamily =
    FontFamily(
        Font(R.font.inter_medium, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_medium, FontWeight.SemiBold),
        Font(R.font.inter_medium, FontWeight.Bold),
    )

private val BaseTypography = Typography()

val WinNativeTypography =
    Typography(
        displayLarge = BaseTypography.displayLarge.copy(fontFamily = WinNativeFontFamily),
        displayMedium = BaseTypography.displayMedium.copy(fontFamily = WinNativeFontFamily),
        displaySmall = BaseTypography.displaySmall.copy(fontFamily = WinNativeFontFamily),
        headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = WinNativeFontFamily),
        headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = WinNativeFontFamily),
        headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = WinNativeFontFamily),
        titleLarge = BaseTypography.titleLarge.copy(fontFamily = WinNativeFontFamily),
        titleMedium = BaseTypography.titleMedium.copy(fontFamily = WinNativeFontFamily),
        titleSmall = BaseTypography.titleSmall.copy(fontFamily = WinNativeFontFamily),
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
