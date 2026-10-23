package dev.otmgram.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** [text] with every occurrence of [query] on a [background], ignoring case. */
fun highlightMatches(text: String, query: String, background: Color): AnnotatedString {
    val part = query.trim()
    if (part.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val at = text.indexOf(part, from, ignoreCase = true)
            if (at < 0) break
            append(text.substring(from, at))
            withStyle(SpanStyle(background = background)) { append(text.substring(at, at + part.length)) }
            from = at + part.length
        }
        append(text.substring(from))
    }
}
