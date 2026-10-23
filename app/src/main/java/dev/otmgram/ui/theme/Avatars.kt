package dev.otmgram.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.sp
import dev.otmgram.ui.media.TdImage

/* Chat pictures: photos, or generated patterns for chats without one. */

/**
 * A chat's picture: its photo, or for chats without one, a pattern derived from the id
 * (stripes, dots, arcs, zigzag) with the initial in the middle. Saved Messages gets a
 * bookmark.
 */
@Composable
fun ChatAvatar(
    chatId: Long,
    title: String,
    photoFileId: Int?,
    minithumbnail: ByteArray?,
    size: Dp = 52.dp,
    savedMessages: Boolean = false,
) {
    when {
        savedMessages -> Box(Modifier.size(size).clip(CircleShape).background(Color(0xFFDCE4E9)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 0.58f).clip(CircleShape).background(Otm.Sheet), contentAlignment = Alignment.Center) {
                Icon(BOOKMARK, null, tint = Otm.Slate, modifier = Modifier.size(size * 0.28f))
            }
        }
        photoFileId != null -> TdImage(photoFileId, minithumbnail, Modifier.size(size).clip(CircleShape))
        else -> PatternAvatar(chatId, title, size)
    }
}

@Composable
private fun PatternAvatar(chatId: Long, title: String, size: Dp) {
    val pattern = Math.floorMod(chatId, PATTERNS)
    val (light, dark) = AVATAR_TONES[Math.floorMod(chatId / PATTERNS, AVATAR_TONES.size.toLong()).toInt()]
    Box(Modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(light)
            val unit = this.size.width / 52f
            when (pattern) {
                0L -> { // Diagonal stripes.
                    val step = 10 * unit
                    var x = -this.size.height
                    while (x < this.size.width) {
                        drawLine(dark, Offset(x, this.size.height), Offset(x + this.size.height, 0f), strokeWidth = 5 * unit)
                        x += step
                    }
                }
                1L -> { // Dots.
                    val step = 9 * unit
                    var y = step / 2
                    while (y < this.size.height) {
                        var x = step / 2
                        while (x < this.size.width) {
                            drawCircle(dark, 2.4f * unit, Offset(x, y))
                            x += step
                        }
                        y += step
                    }
                }
                2L -> { // Arcs from a corner.
                    var r = 70 * unit
                    var i = 0
                    while (r > 0) {
                        drawCircle(if (i % 2 == 0) dark else light, r, Offset(0f, this.size.height))
                        r -= 5 * unit
                        i++
                    }
                }
                3L -> { // Zigzag.
                    val step = 12 * unit
                    var y = 0f
                    while (y < this.size.height + step) {
                        val path = Path()
                        var x = 0f
                        path.moveTo(0f, y)
                        while (x < this.size.width) {
                            path.lineTo(x + step / 2, y - step / 2)
                            path.lineTo(x + step, y)
                            x += step
                        }
                        drawPath(path, dark, style = Stroke(width = 3 * unit))
                        y += step / 1.5f
                    }
                }
                else -> { // Horizontal stripes.
                    var y = 0f
                    while (y < this.size.height) {
                        drawRect(dark, Offset(0f, y + 4 * unit), Size(this.size.width, 4 * unit))
                        y += 8 * unit
                    }
                }
            }
        }
        Box(Modifier.size(size * 0.58f).clip(CircleShape).background(Otm.Sheet), contentAlignment = Alignment.Center) {
            Text(
                title.trim().firstOrNull()?.uppercase() ?: "?",
                style = OtmType.Name.copy(fontSize = (size.value * 0.29f).sp),
                color = Otm.Ink,
            )
        }
    }
}

private const val PATTERNS = 5L

/** Light and dark tone pairs, from the sky and the ridges. */
private val AVATAR_TONES = listOf(
    Color(0xFFDFE5E9) to Color(0xFFA9BAC6),
    Color(0xFFF1E1D2) to Color(0xFFDDBFA6),
    Color(0xFFE7DCEB) to Color(0xFFB9A3C4),
    Color(0xFFDDE6D6) to Color(0xFFA9BC9E),
    Color(0xFFF3DDC9) to Color(0xFFE4B79A),
)

private val BOOKMARK = ImageVector.Builder("Bookmark", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        addPathNodes("M6 3h12v18l-6-4-6 4z"),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2.4f,
        strokeLineJoin = StrokeJoin.Round,
    )
}.build()
