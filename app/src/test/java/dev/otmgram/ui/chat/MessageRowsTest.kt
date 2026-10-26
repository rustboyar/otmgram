package dev.otmgram.ui.chat

import dev.otmgram.testing.DAY
import dev.otmgram.testing.NOON_2026_10_23
import dev.otmgram.ui.format.dayLabel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class MessageRowsTest {

    private fun item(id: Long, date: Int) = MessageItem(
        id = id, isOutgoing = false, isService = false, senderName = null, forwardedFrom = null, replyTo = null,
        text = "", media = null, isMediaPlaceholder = false, date = date, isEdited = false, status = null, reactions = emptyList(),
    )

    /** Rows as short labels, top of the screen last (the list is reversed). */
    private fun List<ListRow>.labels() = map {
        when (it) {
            is ListRow.Message -> "m${it.message.id}"
            is ListRow.Day -> "day"
            is ListRow.Unread -> "new ${it.count}"
        }
    }

    @Test
    fun `a day divider sits above each day's first message`() {
        val rows = buildRows(listOf(item(3, NOON_2026_10_23 + DAY), item(2, NOON_2026_10_23), item(1, NOON_2026_10_23)), unread = null)
        assertEquals(listOf("m3", "day", "m2", "m1", "day"), rows.labels())
    }

    @Test
    fun `the new messages line sits above the first unread one`() {
        val messages = listOf(item(4, NOON_2026_10_23), item(3, NOON_2026_10_23), item(2, NOON_2026_10_23), item(1, NOON_2026_10_23))
        val rows = buildRows(messages, UnreadMark(afterMessageId = 2, count = 2))
        assertEquals(listOf("m4", "m3", "new 2", "m2", "m1", "day"), rows.labels())
    }

    @Test
    fun `no new messages line when the read ones aren't loaded`() {
        // All loaded messages are unread: where they started isn't on screen yet.
        val rows = buildRows(listOf(item(4, NOON_2026_10_23), item(3, NOON_2026_10_23)), UnreadMark(afterMessageId = 2, count = 2))
        assertEquals(listOf("m4", "m3", "day"), rows.labels())
    }

    @Test
    fun `day labels name the weekday this year and the year otherwise`() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals("Friday, 23 October", dayLabel(NOON_2026_10_23, today, ZoneOffset.UTC, Locale.ENGLISH))
        assertEquals("23 October 2026", dayLabel(NOON_2026_10_23, LocalDate.of(2027, 1, 2), ZoneOffset.UTC, Locale.ENGLISH))
    }
}
