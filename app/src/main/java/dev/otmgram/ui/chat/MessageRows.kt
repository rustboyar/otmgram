package dev.otmgram.ui.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Rows of the message list, newest first: messages and the dividers above them. */
internal sealed interface ListRow {
    val key: Any

    /** Rows of one type reuse each other's layout as they scroll by. */
    val contentType: Any get() = this::class

    data class Message(val message: MessageItem) : ListRow {
        override val key get() = message.id
        override val contentType: Any get() = if (message.isService) "service" else message.media?.let { it::class } ?: "text"
    }

    data class Day(val date: Int, override val key: String) : ListRow

    /** "4 new since you left", above the first message that was unread. */
    data class Unread(val count: Int) : ListRow {
        override val key get() = "unread"
    }
}

internal fun buildRows(messages: List<MessageItem>, unread: UnreadMark?): List<ListRow> = buildList {
    messages.forEachIndexed { index, message ->
        add(ListRow.Message(message))
        // reverseLayout: the row after a message is shown above it.
        val older = messages.getOrNull(index + 1)
        if (unread != null && older != null && message.id > unread.afterMessageId && older.id <= unread.afterMessageId) {
            add(ListRow.Unread(unread.count))
        }
        if (older == null || day(older.date) != day(message.date)) add(ListRow.Day(message.date, "day-${message.id}"))
    }
}

internal fun day(unixTime: Int, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochSecond(unixTime.toLong()).atZone(zone).toLocalDate()

/** "Thursday, 23 October"; with the year when it isn't this one. */
internal fun dayLabel(unixTime: Int, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val date = day(unixTime, zone)
    val pattern = if (date.year == today.year) "EEEE, d MMMM" else "d MMMM yyyy"
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}
