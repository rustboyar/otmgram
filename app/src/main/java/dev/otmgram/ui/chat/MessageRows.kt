package dev.otmgram.ui.chat

import dev.otmgram.ui.format.localDay

/** Rows of the message list, newest first: messages and the dividers above them. */
internal sealed interface ListRow {
    val key: Any

    /** Rows of one type reuse each other's layout as they scroll by. */
    val contentType: Any get() = this::class

    data class Message(val message: MessageItem) : ListRow {
        override val key get() = message.id
        override val contentType: Any get() = if (message.isService) "service" else message.media?.let { it::class } ?: "text"
    }

    /** Photos and videos sent together, oldest first, in one bubble. */
    data class Album(val messages: List<MessageItem>) : ListRow {
        // The newest: seeing it marks the others read too.
        override val key get() = messages.last().id
        override val contentType: Any get() = "album"

        /** The one with the caption, else the first: its menu, reactions and time are the album's. */
        val main: MessageItem = messages.firstOrNull { it.text.isNotEmpty() } ?: messages.first()

        /** [main] as the bubble shows it: the sender of the first, and still sending while any is. */
        val shown: MessageItem = main.copy(
            senderName = messages.first().senderName,
            // Its avatar goes with the newest, at the bottom of the bubble.
            showAvatar = messages.last().showAvatar,
            forwardedFrom = messages.first().forwardedFrom,
            replyTo = messages.firstNotNullOfOrNull { it.replyTo },
            isEdited = messages.any { it.isEdited },
            status = when {
                main.status == null -> null
                messages.any { it.status == MessageStatus.Sending } -> MessageStatus.Sending
                messages.all { it.status == MessageStatus.Read } -> MessageStatus.Read
                else -> MessageStatus.Sent
            },
        )
    }

    data class Day(val date: Int, override val key: String) : ListRow

    /** "4 new since you left", above the first message that was unread. */
    data class Unread(val count: Int) : ListRow {
        override val key get() = "unread"
    }
}

/** The day this row is on, as a Unix time; null for the unread line. */
internal val ListRow.date: Int?
    get() = when (this) {
        is ListRow.Message -> message.date
        is ListRow.Album -> messages.first().date
        is ListRow.Day -> date
        is ListRow.Unread -> null
    }

/** Whether this row shows the message [id]. */
internal fun ListRow.has(id: Long): Boolean = when (this) {
    is ListRow.Message -> message.id == id
    is ListRow.Album -> messages.any { it.id == id }
    else -> false
}

internal fun buildRows(messages: List<MessageItem>, unread: UnreadMark?): List<ListRow> = buildList {
    val groups = albums(messages)
    groups.forEachIndexed { index, group ->
        add(if (group.size == 1) ListRow.Message(group.single()) else ListRow.Album(group.reversed()))
        // reverseLayout: the row after a message is shown above it.
        val newest = group.first()
        val older = groups.getOrNull(index + 1)?.first()
        if (unread != null && older != null && newest.id > unread.afterMessageId && older.id <= unread.afterMessageId) {
            add(ListRow.Unread(unread.count))
        }
        if (older == null || localDay(older.date) != localDay(group.last().date)) add(ListRow.Day(group.last().date, "day-${group.last().id}"))
    }
}

/**
 * [messages] (newest first) in groups: an album's photos and videos together, everything else
 * alone. An album whose messages have more than one caption, or one that failed to send,
 * stays apart: each caption, and the retry, belongs to its own message.
 */
private fun albums(messages: List<MessageItem>): List<List<MessageItem>> {
    val groups = mutableListOf<MutableList<MessageItem>>()
    messages.forEach { message ->
        val last = groups.lastOrNull()
        if (last != null && message.inAlbum && last.first().inAlbum && message.albumId == last.first().albumId) {
            last += message
        } else {
            groups += mutableListOf(message)
        }
    }
    return groups.flatMap { group ->
        if (group.size > 1 && group.count { it.text.isNotEmpty() } <= 1 && group.none { it.status == MessageStatus.Failed }) {
            listOf(group)
        } else {
            group.map { listOf(it) }
        }
    }
}

private val MessageItem.inAlbum: Boolean
    get() = albumId != 0L && (media is MessageMedia.Photo || media is MessageMedia.Video)
