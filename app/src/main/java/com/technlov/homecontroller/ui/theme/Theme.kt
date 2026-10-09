package com.technlov.homecontroller.ui.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.technlov.homecontroller.data.ThemeMode

// Brand fallback palette (used before Android 12, or when the user turns wallpaper colours off):
// deep teal-blue primary, cool slate secondary, warm amber-coral accent.
private val LightBrand = lightColorScheme(
    primary = Color(0xFF0B6780),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBCE9FF),
    onPrimaryContainer = Color(0xFF001F29),
    inversePrimary = Color(0xFF86D0EE),
    secondary = Color(0xFF4C626B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE6F1),
    onSecondaryContainer = Color(0xFF071E26),
    tertiary = Color(0xFF9A4521),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBCD),
    onTertiaryContainer = Color(0xFF360F00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FAFD),
    onBackground = Color(0xFF171C1F),
    surface = Color(0xFFF6FAFD),
    onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDBE4E9),
    onSurfaceVariant = Color(0xFF40484C),
    surfaceTint = Color(0xFF0B6780),
    inverseSurface = Color(0xFF2C3134),
    inverseOnSurface = Color(0xFFEDF1F4),
    outline = Color(0xFF70787D),
    outlineVariant = Color(0xFFBFC8CC),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FAFD),
    surfaceDim = Color(0xFFD6DBDE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4F7),
    surfaceContainer = Color(0xFFEAEEF1),
    surfaceContainerHigh = Color(0xFFE4E9EC),
    surfaceContainerHighest = Color(0xFFDFE3E6),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFF86D0EE),
    onPrimary = Color(0xFF003545),
    primaryContainer = Color(0xFF004D62),
    onPrimaryContainer = Color(0xFFBCE9FF),
    inversePrimary = Color(0xFF0B6780),
    secondary = Color(0xFFB3CAD5),
    onSecondary = Color(0xFF1E333C),
    secondaryContainer = Color(0xFF354A53),
    onSecondaryContainer = Color(0xFFCFE6F1),
    tertiary = Color(0xFFFFB596),
    onTertiary = Color(0xFF581E00),
    tertiaryContainer = Color(0xFF7B2F0A),
    onTertiaryContainer = Color(0xFFFFDBCD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1416),
    onBackground = Color(0xFFDEE3E6),
    surface = Color(0xFF0F1416),
    onSurface = Color(0xFFDEE3E6),
    surfaceVariant = Color(0xFF40484C),
    onSurfaceVariant = Color(0xFFBFC8CC),
    surfaceTint = Color(0xFF86D0EE),
    inverseSurface = Color(0xFFDEE3E6),
    inverseOnSurface = Color(0xFF2C3134),
    outline = Color(0xFF8A9296),
    outlineVariant = Color(0xFF40484C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353A3D),
    surfaceDim = Color(0xFF0F1416),
    surfaceContainerLowest = Color(0xFF0A0F11),
    surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023),
    surfaceContainerHigh = Color(0xFF252B2D),
    surfaceContainerHighest = Color(0xFF303538),
)

private val Base = Typography()

private val AppTypography = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1.5).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** The big temperature numbers on tiles and the dial. */
val NumberStyle = TextStyle(fontWeight = FontWeight.Light, letterSpacing = (-2).sp)

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun HomeTheme(dark: Boolean, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && supportsDynamicColor() -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkBrand
        else -> LightBrand
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

/** True when the current colour scheme is dark (follows the app's theme choice, not only the system's). */
@Composable
@ReadOnlyComposable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f
