package dev.otmgram.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** [text] with every occurrence of [query] on a [background], ignoring case. */
fun highlightMatches(text: String, query: String, background: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        highlight(text, query, background)
    }

/** Puts every occurrence of [query] in [text] (already appended) on a [background]. */
fun AnnotatedString.Builder.highlight(text: String, query: String, background: Color) {
    val part = query.trim()
    if (part.isEmpty()) return
    var from = 0
    while (true) {
        val at = text.indexOf(part, from, ignoreCase = true)
        if (at < 0) break
        addStyle(SpanStyle(background = background), at, at + part.length)
        from = at + part.length
    }
}
