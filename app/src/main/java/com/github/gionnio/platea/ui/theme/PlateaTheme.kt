package com.github.gionnio.platea.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import com.github.gionnio.platea.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Platea design system, palette "Velluto e oro".
 *
 * The concept is drawn at 1920x1080 px; TV screens run at 960x540 dp (xhdpi), so every size here is the
 * concept value divided by two.
 */
object PlateaColors {
    val Ground = Color(0xFF100B0C)
    val Surface = Color(0xFF1E1517)
    val Placeholder = Color(0xFF2C1C1F)
    val Avatar = Color(0xFF3A2629)
    val Border = Color(0xFF5A4446)
    val Separator = Color(0xFF614E50)
    val Text = Color(0xFFF5EDE8)
    val TextSecondary = Color(0xFFCDBDB8)
    val TextCard = Color(0xFFE3D6D2)
    val TextMeta = Color(0xFFB8A6A2)
    val TextTertiary = Color(0xFF94827F)
    val Gold = Color(0xFFD9A55B)
    val OkGreen = Color(0xFF34D399)
}

object PlateaDimens {
    /** TV safe area: 96 x 54 px in the concept. */
    val SafeHorizontal = 48.dp
    val SafeTop = 27.dp

    val CardLandscapeWidth = 192.dp
    val CardLandscapeHeight = 108.dp
    val PosterWidth = 110.dp
    val PosterHeight = 165.dp
    val CardGap = 16.dp
    val PosterGap = 12.dp
    val CardRadius = 7.dp
    val PosterRadius = 6.dp
    val FocusBorder = 2.dp
    const val FOCUS_SCALE = 1.04f

    val ButtonHeight = 32.dp
    val ButtonRadius = 6.dp
    val HeroLogoHeight = 55.dp
    val HeroLogoMaxWidth = 300.dp
}

/** Figtree (SIL Open Font License), variable font with one instance per weight used. */
@OptIn(ExperimentalTextApi::class)
val PlateaFont: FontFamily =
    FontFamily(
        listOf(400, 500, 600, 700, 800).map { w ->
            Font(R.font.figtree, weight = FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )

object PlateaType {
    val Kicker = TextStyle(fontFamily = PlateaFont, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.12.em)
    val HeroTitle = TextStyle(fontFamily = PlateaFont, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em)
    val Meta = TextStyle(fontFamily = PlateaFont, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(fontFamily = PlateaFont, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, lineHeight = 16.5.sp)
    val Badge = TextStyle(fontFamily = PlateaFont, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em)
    val Button = TextStyle(fontFamily = PlateaFont, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    val Nav = TextStyle(fontFamily = PlateaFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val Brand = TextStyle(fontFamily = PlateaFont, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    val RowTitle = TextStyle(fontFamily = PlateaFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val CardTitle = TextStyle(fontFamily = PlateaFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val CardSub = TextStyle(fontFamily = PlateaFont, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
    val Chip = TextStyle(fontFamily = PlateaFont, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
}
