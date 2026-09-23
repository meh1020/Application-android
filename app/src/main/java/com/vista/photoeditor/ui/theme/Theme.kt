package com.vista.photoeditor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.vista.photoeditor.R

/** Jeu de couleurs de l'application, décliné en clair et en sombre. */
@Immutable
data class VistaColorScheme(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceSoft: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val primary: Color,
    val primarySoft: Color,
    val onPrimary: Color,
    val danger: Color,
    val gradientTop: Color,
    val gradientMiddle: Color,
    val gradientBottom: Color,
    val shadowAmbient: Color,
    val shadowSpot: Color,
    /** Voile posé hors du cadre de recadrage. */
    val cropVeil: Color,
    val cropGrid: Color,
)

private val LightColors = VistaColorScheme(
    isDark = false,
    background = Color(0xFFF2F4F7),
    surface = Color(0xFFFFFFFF),
    surfaceSoft = Color(0xFFEDEFF3),
    outline = Color(0xFFE2E4EA),
    text = Color(0xFF141319),
    muted = Color(0xFF8C8B96),
    primary = Color(0xFF4A1D6F),
    primarySoft = Color(0xFFEDE5F4),
    onPrimary = Color(0xFFFFFFFF),
    danger = Color(0xFFD64545),
    gradientTop = Color(0xFFF7F8FA),
    gradientMiddle = Color(0xFFF1F0F6),
    gradientBottom = Color(0xFFFBF5EE),
    shadowAmbient = Color(0x22301050),
    shadowSpot = Color(0x33301050),
    cropVeil = Color(0xB3FFFFFF),
    cropGrid = Color(0xD9FFFFFF),
)

private val DarkColors = VistaColorScheme(
    isDark = true,
    background = Color(0xFF0D0D11),
    surface = Color(0xFF1B1B22),
    surfaceSoft = Color(0xFF23232C),
    outline = Color(0xFF33333E),
    text = Color(0xFFF3F3F6),
    muted = Color(0xFF9B9BA7),
    // Le violet profond du design est illisible sur fond sombre : version éclaircie.
    primary = Color(0xFFB98BFF),
    primarySoft = Color(0xFF2A1D3D),
    onPrimary = Color(0xFF17082B),
    danger = Color(0xFFFF8A8A),
    gradientTop = Color(0xFF121218),
    gradientMiddle = Color(0xFF141419),
    gradientBottom = Color(0xFF1A1620),
    shadowAmbient = Color(0x66000000),
    shadowSpot = Color(0x99000000),
    cropVeil = Color(0x99000000),
    cropGrid = Color(0xD9FFFFFF),
)

val LocalVistaColors = staticCompositionLocalOf { LightColors }

/**
 * Couleurs du thème courant. Les accès se font dans un contexte @Composable ;
 * dans un bloc de dessin, copiez-les d'abord dans une variable locale.
 */
object VistaColors {
    val Background: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.background
    val Surface: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.surface
    val SurfaceSoft: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.surfaceSoft
    val Outline: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.outline
    val Text: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.text
    val Muted: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.muted
    val Primary: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.primary
    val PrimarySoft: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.primarySoft
    val OnPrimary: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.onPrimary
    val Danger: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.danger
    val CropVeil: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.cropVeil
    val CropGrid: Color
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.cropGrid

    /** Fond légèrement dégradé des écrans. */
    val ScreenGradient: Brush
        @Composable @ReadOnlyComposable get() = LocalVistaColors.current.let {
            Brush.verticalGradient(
                0f to it.gradientTop,
                0.55f to it.gradientMiddle,
                1f to it.gradientBottom,
            )
        }
}

val Kanit = FontFamily(
    Font(R.font.kanit_regular, FontWeight.Normal),
    Font(R.font.kanit_medium, FontWeight.Medium),
    Font(R.font.kanit_semibold, FontWeight.SemiBold),
    Font(R.font.kanit_bold, FontWeight.Bold),
    Font(R.font.kanit_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
)

private fun VistaColorScheme.toMaterial() = if (isDark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceSoft,
        onSurfaceVariant = muted,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceSoft,
        outline = outline,
        error = danger,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceSoft,
        onSurfaceVariant = muted,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        outline = outline,
        error = danger,
    )
}

private val typography = Typography().let { base ->
    fun TextStyle.kanit() = copy(fontFamily = Kanit)
    base.copy(
        displayLarge = base.displayLarge.kanit(),
        displayMedium = base.displayMedium.kanit(),
        displaySmall = base.displaySmall.kanit(),
        headlineLarge = base.headlineLarge.kanit(),
        headlineMedium = base.headlineMedium.kanit(),
        headlineSmall = base.headlineSmall.kanit(),
        titleLarge = base.titleLarge.kanit(),
        titleMedium = base.titleMedium.kanit(),
        titleSmall = base.titleSmall.kanit(),
        bodyLarge = base.bodyLarge.kanit(),
        bodyMedium = base.bodyMedium.kanit(),
        bodySmall = base.bodySmall.kanit(),
        labelLarge = base.labelLarge.kanit(),
        labelMedium = base.labelMedium.kanit(),
        labelSmall = base.labelSmall.kanit(),
    )
}

@Composable
fun VistaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalVistaColors provides colors) {
        MaterialTheme(colorScheme = colors.toMaterial(), typography = typography, content = content)
    }
}
