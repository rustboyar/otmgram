package dev.otmgram.ui.theme

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp

/* Backgrounds and full-screen surfaces: paper, the sunrise, system bars. */

/** Warm paper with a faint 3 dp grain: the background of every light screen. */
fun Modifier.paper(): Modifier = drawWithCache {
    val tile = 3.dp.toPx().toInt().coerceAtLeast(2)
    val grain = ImageBitmap(tile, tile).also {
        Canvas(it).drawCircle(Offset(tile / 2f, tile / 2f), 0.5.dp.toPx(), Paint().apply { color = Otm.Ink.copy(alpha = 0.035f) })
    }
    val brush = ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind {
        drawRect(Otm.Paper)
        drawRect(brush)
    }
}

// The sunrise: SVG paths of the design, in a 390-wide viewport.

private val SKY_COLORS = arrayOf(0f to Color(0xFFE6E9EA), 0.5f to Color(0xFFF3DDC9), 0.78f to Color(0xFFF2C6A9), 1f to Color(0xFFEDC3B7))

private val BAND_RIDGES = listOf(
    "M0 70L28 65L52 68L84 60L112 64L142 57L170 63L202 59L236 65L262 61L300 66L332 60L362 65L390 62V96H0Z" to Color(0xFFBCC8CF),
    "M0 79C40 71 72 75 104 70C134 65 166 76 204 73C244 70 274 78 312 72C342 68 366 74 390 71V96H0Z" to Color(0xFF91A8B6),
    "M0 88C50 84 92 90 142 85C192 80 232 90 282 87C322 85 362 81 390 85V96H0Z" to Color(0xFF62808F),
)

private val HERO_RIDGES = listOf(
    "M0 196L30 186L58 192L92 176L122 184L156 170L186 182L220 174L254 186L284 178L320 188L352 176L390 184V300H0Z" to Color(0xFFC3CED4),
    "M0 222C40 206 76 214 112 204C146 194 178 214 220 208C262 202 292 218 332 206C356 200 374 208 390 204V300H0Z" to Color(0xFF9BB0BD),
    "M0 252C46 240 90 252 140 240C190 228 236 250 286 244C330 238 364 230 390 238V300H0Z" to Color(0xFF7893A1),
)

private fun ridgePaths(ridges: List<Pair<String, Color>>) = ridges.map { (d, color) -> PathParser().parsePathString(d).toPath() to color }

private fun DrawScope.drawSunrise(paths: List<Pair<Path, Color>>, viewHeight: Float, sunX: Float, sunY: Float, sunR: Float, mist: Boolean) {
    drawRect(Brush.verticalGradient(*SKY_COLORS))
    val sx = size.width / 390f
    val sy = size.height / viewHeight
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFFFFF4E4), 0.5f to Color(0xCCFBDDBE), 1f to Color(0x00FBDDBE),
            center = Offset(sunX * sx, sunY * sy), radius = sunR * sx,
        ),
        radius = sunR * sx, center = Offset(sunX * sx, sunY * sy),
    )
    val scale = Matrix().apply { scale(sx, sy) }
    paths.forEach { (path, color) ->
        val scaled = Path().apply { addPath(path) }.apply { transform(scale) }
        drawPath(scaled, color)
    }
    if (mist) {
        val top = size.height * 0.75f
        drawRect(
            Brush.verticalGradient(0f to Otm.Paper.copy(alpha = 0f), 1f to Otm.Paper, startY = top, endY = size.height),
            topLeft = Offset(0f, top),
        )
    }
}

/**
 * The chat list header: a sunrise with misty ridges behind the status bar, the folder
 * name in Fraunces and [actions] on the right. [content] replaces the name, e.g. with a
 * search field.
 */
@Composable
fun SkyBand(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val paths = remember { ridgePaths(BAND_RIDGES) }
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) { drawSunrise(paths, 96f, 292f, 66f, 30f, mist = false) }
        Column {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                modifier = Modifier.fillMaxWidth().height(96.dp).padding(start = 20.dp, end = 8.dp, bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (content != null) {
                    content()
                } else {
                    // Names are short (see MAX_HEADER_NAME); longer ones shrink to fit on one line.
                    BasicText(
                        title,
                        style = OtmType.FolderName.copy(color = Otm.Ink),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = OtmType.FolderName.fontSize, stepSize = 0.5.sp),
                        modifier = Modifier.weight(1f).testTag("headerTitle"),
                    )
                }
                actions()
            }
        }
    }
}

/** A tall sunrise for sign-in screens; the last ridge fades into the paper like mist. */
@Composable
fun Hero(height: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val paths = remember { ridgePaths(HERO_RIDGES) }
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.matchParentSize()) { drawSunrise(paths, 300f, 262f, 196f, 72f, mist = true) }
        Box(Modifier.matchParentSize().statusBarsPadding(), content = content)
    }
}

/** The wordmark: "otmgram" over "One folder. Nothing else." */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("otmgram", style = OtmType.FolderName.copy(fontSize = 38.sp), color = Otm.Ink)
        Text("One folder. Nothing else.", style = OtmType.Body, color = Color(0xFF4A5560))
    }
}

/**
 * For full-screen dialogs that draw their own backdrop: no extra dimming from the window,
 * and dark status bar icons like the screens behind.
 */
@Composable
fun PlainDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        window?.setDimAmount(0f)
        window?.let { WindowCompat.getInsetsController(it, view) }?.apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        onDispose {}
    }
}

/**
 * Light status bar icons while a dark screen (photo viewer, circle camera) is shown; dark
 * ones again when it leaves.
 */
@Composable
fun DarkSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = true
            controller?.isAppearanceLightNavigationBars = true
        }
    }
}
