package dev.otmgram.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons of the design, drawn on a 24-unit grid from SVG path data. They are black;
 * Icon() tints them.
 */
object OtmIcons {
    val Back = stroke("Back", "M15 5l-7 7 7 7")
    val Close = stroke("Close", "M6 6l12 12M18 6L6 18")
    val Search = stroke("Search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5")
    val Attach = stroke("Attach", "M20 11.5l-8.2 8.2a5 5 0 0 1-7-7L13 4.5a3.4 3.4 0 0 1 4.8 4.8l-8.2 8.2a1.7 1.7 0 0 1-2.4-2.4l7.5-7.5")
    val Smile = stroke("Smile", circle(12f, 12f, 9f), "M8.5 14.5c1.8 2 5.2 2 7 0", "M9 9.5h.01M15 9.5h.01")
    val Keyboard = stroke("Keyboard", "M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M7 10h.01M11 10h.01M15 10h.01M7 14h10")
    val CircleCamera = stroke(
        "CircleCamera", circle(12f, 12f, 9.5f),
        "M8 9.3h4.6a1.2 1.2 0 0 1 1.2 1.2v3a1.2 1.2 0 0 1-1.2 1.2H8a1.2 1.2 0 0 1-1.2-1.2v-3A1.2 1.2 0 0 1 8 9.3z",
        "M13.8 11l3.2-1.6v5.2l-3.2-1.6z",
    )
    val Send = stroke("Send", "M12 19V5M6 11l6-6 6 6", width = 2.2f)
    val Check = stroke("Check", "M5 12l5 5 9-10", width = 2.4f)
    val Mic = stroke("Mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M5 11a7 7 0 0 0 14 0M12 18v3")
    val Pin = stroke("Pin", "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z", circle(12f, 9.5f, 2.5f))

    /** A pushpin, for pinned messages (not [Pin], a map pin). */
    val Pushpin = stroke("Pushpin", "M9 3h6M10 3v6l-3 4h10l-3-4V3", "M12 13v8")
    val Unpin = stroke("Unpin", "M9 3h6M10 3v6l-3 4h10l-3-4V3", "M12 13v8", "M4 4l16 16")
    val List = stroke("List", "M9 6h11M9 12h11M9 18h11", "M4.5 6h.01M4.5 12h.01M4.5 18h.01", width = 2f)
    val LiveLocation = stroke(
        "LiveLocation", circle(12f, 10f, 3f),
        "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z", "M3 4a12 12 0 0 1 4-2M21 4a12 12 0 0 0-4-2",
    )
    val Gallery = stroke(
        "Gallery", "M6 4h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z",
        circle(9f, 10f, 1.8f), "M4 18l5-5 4 4 3-3 4 4",
    )
    val File = stroke("File", "M6 3h8l4 4v14H6z", "M14 3v4h4")
    val Download = stroke("Download", "M12 5v12M6 11l6 6 6-6", width = 2f)
    val Trash = stroke("Trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13")
    val Reply = stroke("Reply", "M10 8L4 13l6 5", "M4 13h10a6 6 0 0 1 6 6")
    val Edit = stroke("Edit", "M4 20h4L19 9l-4-4L4 16z", "M13 7l4 4")
    val Copy = stroke(
        "Copy", "M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z",
        "M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3",
    )
    val Forward = stroke("Forward", "M14 8l6 5-6 5", "M20 13H10a6 6 0 0 0-6 6")
    val Save = stroke("Save", "M12 4v11M7 10l5 5 5-5", "M5 20h14")
    val Select = stroke("Select", circle(12f, 12f, 9f), "M8 12l3 3 5-6")
    val ChevronUp = stroke("ChevronUp", "M6 15l6-6 6 6")
    val ChevronDown = stroke("ChevronDown", "M6 9l6 6 6-6")
    val Muted = stroke("Muted", "M11 5L6 9H3v6h3l5 4z", "M22 9l-6 6M16 9l6 6", width = 2f)
    val Eye = stroke("Eye", "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", circle(12f, 12f, 3f))
    val EyeOff = stroke("EyeOff", "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", circle(12f, 12f, 3f), "M4 4l16 16")
    val Clock = stroke("Clock", circle(12f, 12f, 9f), "M12 7v5l3 2", width = 2f)
    val Alert = stroke("Alert", circle(12f, 12f, 9f), "M12 7v6M12 16.5v.5", width = 2f)
    val MyLocation = stroke("MyLocation", circle(12f, 12f, 4f), "M12 2v3M12 19v3M2 12h3M19 12h3")
    val SwitchCamera = stroke(
        "SwitchCamera", "M4 8h3l2-3h6l2 3h3v11H4z",
        "M9 13a3 3 0 0 1 5.5-1.6M15 13a3 3 0 0 1-5.5 1.6", "M14.5 9.5v2h-2M9.5 16.5v-2h2",
    )
    val Offline = stroke("Offline", "M12 3a9 9 0 1 0 9 9", width = 2.2f)
    val More = fill("More", circle(12f, 5f, 1.8f), circle(12f, 12f, 1.8f), circle(12f, 19f, 1.8f))
    val Play = fill("Play", "M7 4.5l13 7.5-13 7.5z")
    val Pause = fill("Pause", "M6 4h4v16H6zM14 4h4v16h-4z")
}

private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

private fun builder(name: String) =
    ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)

private fun stroke(name: String, vararg paths: String, width: Float = 1.8f): ImageVector = builder(name).apply {
    paths.forEach {
        addPath(
            pathData = addPathNodes(it),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()

private fun fill(name: String, vararg paths: String): ImageVector = builder(name).apply {
    paths.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
}.build()
