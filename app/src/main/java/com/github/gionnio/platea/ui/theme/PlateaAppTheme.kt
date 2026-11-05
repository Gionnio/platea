package com.github.gionnio.platea.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Material colour schemes in velvet and gold, for the few standard components Platea still draws through
 * Material (clock, screensaver, licence list): same ground, text and accent as the Platea pages. The cursor
 * stays white (onSurface / inverseSurface / border), gold marks selection.
 */
object PlateaThemeColors {
    private val goldContainer = Color(0xFF4A3520)
    private val onGoldContainer = Color(0xFFF3DDB8)
    private val error = Color(0xFFF87171)

    val darkScheme =
        darkColorScheme(
            primary = PlateaColors.Gold,
            onPrimary = PlateaColors.Ground,
            primaryContainer = goldContainer,
            onPrimaryContainer = onGoldContainer,
            secondary = PlateaColors.TextSecondary,
            onSecondary = PlateaColors.Ground,
            secondaryContainer = PlateaColors.Avatar,
            onSecondaryContainer = PlateaColors.Text,
            tertiary = PlateaColors.Gold,
            onTertiary = PlateaColors.Ground,
            tertiaryContainer = PlateaColors.Placeholder,
            onTertiaryContainer = PlateaColors.Text,
            error = error,
            onError = PlateaColors.Ground,
            errorContainer = Color(0xFF5C1F1F),
            onErrorContainer = Color(0xFFFFDAD6),
            background = PlateaColors.Ground,
            onBackground = PlateaColors.Text,
            surface = PlateaColors.Surface,
            onSurface = PlateaColors.Text,
            surfaceVariant = PlateaColors.Placeholder,
            onSurfaceVariant = PlateaColors.TextSecondary,
            scrim = Color.Black,
            inverseSurface = PlateaColors.Text,
            inverseOnSurface = PlateaColors.Ground,
            inversePrimary = Color(0xFF7A5A2A),
            border = PlateaColors.Text,
        )

    val darkSchemeMaterial =
        androidx.compose.material3.darkColorScheme(
            primary = PlateaColors.Gold,
            onPrimary = PlateaColors.Ground,
            primaryContainer = goldContainer,
            onPrimaryContainer = onGoldContainer,
            secondary = PlateaColors.TextSecondary,
            onSecondary = PlateaColors.Ground,
            secondaryContainer = PlateaColors.Avatar,
            onSecondaryContainer = PlateaColors.Text,
            tertiary = PlateaColors.Gold,
            onTertiary = PlateaColors.Ground,
            error = error,
            onError = PlateaColors.Ground,
            background = PlateaColors.Ground,
            onBackground = PlateaColors.Text,
            surface = PlateaColors.Surface,
            onSurface = PlateaColors.Text,
            surfaceVariant = PlateaColors.Placeholder,
            onSurfaceVariant = PlateaColors.TextSecondary,
            outline = PlateaColors.Border,
            inverseSurface = PlateaColors.Text,
            inverseOnSurface = PlateaColors.Ground,
        )
}

/** The Material type scale, set in Figtree. */
val PlateaTypography: Typography =
    Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontFamily = PlateaFont),
            displayMedium = displayMedium.copy(fontFamily = PlateaFont),
            displaySmall = displaySmall.copy(fontFamily = PlateaFont),
            headlineLarge = headlineLarge.copy(fontFamily = PlateaFont),
            headlineMedium = headlineMedium.copy(fontFamily = PlateaFont),
            headlineSmall = headlineSmall.copy(fontFamily = PlateaFont),
            titleLarge = titleLarge.copy(fontFamily = PlateaFont),
            titleMedium = titleMedium.copy(fontFamily = PlateaFont),
            titleSmall = titleSmall.copy(fontFamily = PlateaFont),
            bodyLarge = bodyLarge.copy(fontFamily = PlateaFont),
            bodyMedium = bodyMedium.copy(fontFamily = PlateaFont),
            bodySmall = bodySmall.copy(fontFamily = PlateaFont),
            labelLarge = labelLarge.copy(fontFamily = PlateaFont),
            labelMedium = labelMedium.copy(fontFamily = PlateaFont),
            labelSmall = labelSmall.copy(fontFamily = PlateaFont),
        )
    }

/** The theme of the whole app. Platea is dark only. */
@Composable
fun PlateaAppTheme(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(colorScheme = PlateaThemeColors.darkSchemeMaterial) {
        MaterialTheme(colorScheme = PlateaThemeColors.darkScheme, typography = PlateaTypography, content = content)
    }
}
