package dev.otmgram.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.otmgram.R

/** The palette of the "misty sunrise" design: warm paper, apricot sky, slate ridges. */
object Otm {
    val Paper = Color(0xFFF6F3EE)
    val Card = Color(0xFFFFFFFF)
    val Sheet = Color(0xFFFBF9F5)

    /** Your messages and highlights. */
    val Apricot = Color(0xFFF7E2CF)
    val ApricotDeep = Color(0xFFD9B79B)

    /** Within your messages: a quoted reply, a voice message's button, and its icon and waveform. */
    val ApricotQuote = Color(0xFFF3E4D6)
    val ApricotLight = Color(0xFFFBF1E7)
    val Umber = Color(0xFF8A5A3C)

    /** Unread pills, soft buttons. */
    val Haze = Color(0xFFE2E9EE)
    val Ridge = Color(0xFF91A8B6)
    val RidgeLight = Color(0xFFB7C6D0)
    val Slate = Color(0xFF4A6B80)
    val SlateDeep = Color(0xFF3E5E72)

    val Ink = Color(0xFF1E2A33)
    val InkBody = Color(0xFF2E3A43)
    val InkSoft = Color(0xFF5E6A73)
    val InkFaint = Color(0xFF8A9199)

    /** Times, small labels and quiet icons (attach, copy, menu actions). */
    val Meta = Color(0xFF56626B)
    val Line = Color(0x1F1E2A33)
    val Outline = Color(0xFFD6D9DC)

    /** Recording dot. */
    val Rose = Color(0xFFD27B6C)
    val Error = Color(0xFFA3473A)
    val ErrorSoft = Color(0xFFF8E6E2)

    /** A failed message's border. */
    val ErrorLine = Color(0xFFE5B9AF)

    /** Search matches; the current one is stronger. */
    val Mark = Color(0xFFD6E2EA)
    val MarkNow = Color(0xFF9DB6C6)

    /** The one dark surface: photo viewer and circle camera. */
    val Night = Color(0xFF1B242B)
    val NightText = Color(0xFFF1EEE9)
    val NightSoft = Color(0xFFAAB3BA)
    val NightFaint = Color(0xFF7D8A94)

    /** The sky near the horizon: the circle camera's progress ring. */
    val Peach = Color(0xFFF2C6A9)

    /** Banner under the sky, e.g. offline or live location. */
    val Banner = Color(0xFFECE6DD)

    /** Where a map shows once loaded. */
    val Placeholder = Color(0xFFEFEDE6)

    /** Behind a round icon, e.g. in the attach sheet. */
    val Tile = Color(0xFFDCE4E9)

    /** A text field without an outline, e.g. finding a chat to forward to. */
    val Field = Color(0xFFEFEBE4)
}

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Only for the folder name and the wordmark. */
val Fraunces = FontFamily(Font(R.font.fraunces_semibold, FontWeight.SemiBold))

object OtmType {
    val FolderName = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, letterSpacing = (-0.02).em, lineHeight = 32.sp)
    val Headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.01).em, lineHeight = 30.sp)
    val BarTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val Name = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 21.sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val Preview = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 19.sp)
    val Secondary = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val Time = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val Label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.06.em)
    val Button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
}

private val typography = Typography().let { base ->
    fun TextStyle.inter() = copy(fontFamily = Inter)
    Typography(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = OtmType.Headline,
        titleLarge = OtmType.BarTitle.copy(fontSize = 20.sp),
        titleMedium = OtmType.Name,
        titleSmall = base.titleSmall.inter(),
        bodyLarge = OtmType.Body,
        bodyMedium = OtmType.Preview,
        bodySmall = OtmType.Secondary,
        labelLarge = base.labelLarge.inter().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter(),
    )
}

/** Material components (text fields, snackbars, switches) mapped onto the palette. */
private val colors = lightColorScheme(
    primary = Otm.Slate,
    onPrimary = Color.White,
    primaryContainer = Otm.Haze,
    onPrimaryContainer = Otm.SlateDeep,
    secondary = Otm.SlateDeep,
    onSecondary = Color.White,
    secondaryContainer = Otm.Haze,
    onSecondaryContainer = Otm.SlateDeep,
    tertiary = Otm.ApricotDeep,
    background = Otm.Paper,
    onBackground = Otm.Ink,
    surface = Otm.Paper,
    onSurface = Otm.Ink,
    surfaceVariant = Otm.Card,
    onSurfaceVariant = Otm.InkSoft,
    surfaceContainerLowest = Otm.Card,
    surfaceContainerLow = Otm.Sheet,
    surfaceContainer = Otm.Sheet,
    surfaceContainerHigh = Otm.Sheet,
    surfaceContainerHighest = Otm.Haze,
    inverseSurface = Otm.Ink,
    inverseOnSurface = Otm.Paper,
    inversePrimary = Otm.RidgeLight,
    outline = Otm.Outline,
    outlineVariant = Otm.Line,
    error = Otm.Error,
    onError = Color.White,
    errorContainer = Otm.ErrorSoft,
    onErrorContainer = Otm.Error,
    scrim = Otm.Ink,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Always light: the design has no dark theme. */
@Composable
fun OtmgramTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
