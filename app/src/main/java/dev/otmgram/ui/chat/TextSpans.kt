package dev.otmgram.ui.chat

import org.drinkless.tdlib.TdApi

/** A formatted part of a message's text; offsets in UTF-16 units, as TDLib gives them. */
data class TextSpan(val start: Int, val end: Int, val kind: SpanKind)

sealed interface SpanKind {
    /** [hidden]: the text shown isn't the address, so opening it asks first. */
    data class Link(val url: String, val hidden: Boolean) : SpanKind

    data object Bold : SpanKind

    data object Italic : SpanKind

    data object Strikethrough : SpanKind

    data object Code : SpanKind

    /** Hidden until tapped. */
    data object Spoiler : SpanKind
}

/** The formatting the app shows; mentions, hashtags and the like stay plain text. */
internal fun TdApi.FormattedText.spans(): List<TextSpan> = entities.orEmpty().mapNotNull { entity ->
    val end = entity.offset + entity.length
    if (entity.offset < 0 || entity.length <= 0 || end > text.length) return@mapNotNull null
    kindOf(entity.type, text.substring(entity.offset, end))?.let { TextSpan(entity.offset, end, it) }
}

private fun kindOf(type: TdApi.TextEntityType, shown: String): SpanKind? = when (type) {
    is TdApi.TextEntityTypeUrl -> link(shown, hidden = false)
    is TdApi.TextEntityTypeTextUrl -> link(type.url, hidden = shown.trim() != type.url)
    is TdApi.TextEntityTypeEmailAddress -> link("mailto:$shown", hidden = false)
    is TdApi.TextEntityTypePhoneNumber -> link("tel:" + shown.filter { it.isDigit() || it == '+' }, hidden = false)
    is TdApi.TextEntityTypeBold -> SpanKind.Bold
    is TdApi.TextEntityTypeItalic -> SpanKind.Italic
    is TdApi.TextEntityTypeStrikethrough -> SpanKind.Strikethrough
    is TdApi.TextEntityTypeCode, is TdApi.TextEntityTypePre, is TdApi.TextEntityTypePreCode -> SpanKind.Code
    is TdApi.TextEntityTypeSpoiler -> SpanKind.Spoiler
    else -> null
}

private fun link(url: String, hidden: Boolean): SpanKind? = safeUrl(url)?.let { SpanKind.Link(it, hidden) }

/**
 * [url] if it's a web, mail or phone link (a bare "example.com" gets https); null for
 * anything else, e.g. a scheme that opens some other app.
 */
internal fun safeUrl(url: String): String? {
    val trimmed = url.trim()
    val scheme = trimmed.substringBefore(':', "").lowercase()
    return when {
        scheme in SAFE_SCHEMES -> trimmed
        // No scheme, like "example.com/path" or "example.com:8080".
        !trimmed.contains("://") && (scheme.isEmpty() || scheme.contains('.')) -> "https://$trimmed"
        else -> null
    }
}

private val SAFE_SCHEMES = setOf("http", "https", "mailto", "tel")
