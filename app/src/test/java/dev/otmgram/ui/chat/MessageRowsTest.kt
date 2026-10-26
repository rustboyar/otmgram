package dev.otmgram.ui.chat

import dev.otmgram.testing.DAY
import dev.otmgram.testing.NOON_2026_10_23
import dev.otmgram.ui.format.dayLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class MessageRowsTest {

    private fun item(id: Long, date: Int = NOON_2026_10_23) = MessageItem(
        id = id, isOutgoing = false, isService = false, senderName = null, forwardedFrom = null, replyTo = null,
        text = "", media = null, isMediaPlaceholder = false, date = date, isEdited = false, status = null, reactions = emptyList(),
    )

    private val photo = MessageMedia.Photo(fileId = 1, width = 1280, height = 960, minithumbnail = null)

    private fun inAlbum(id: Long, album: Long = 7, caption: String = "", status: MessageStatus? = null) =
        item(id).copy(albumId = album, media = photo, text = caption, status = status)

    /** Rows as short labels, top of the screen last (the list is reversed). */
    private fun List<ListRow>.labels() = map {
        when (it) {
            is ListRow.Message -> "m${it.message.id}"
            is ListRow.Album -> it.messages.joinToString("+", "a(", ")") { message -> "${message.id}" }
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
    fun `an album's photos share a bubble, oldest first`() {
        val rows = buildRows(listOf(item(5), inAlbum(4), inAlbum(3), inAlbum(2, album = 6), item(1)), unread = null)
        // Album 6 has one message here; the others aren't loaded.
        assertEquals(listOf("m5", "a(3+4)", "m2", "m1", "day"), rows.labels())
        assertTrue(rows[1].has(3))
    }

    @Test
    fun `an album's caption, sender and sending state are the bubble's`() {
        val album = buildRows(
            listOf(inAlbum(3, status = MessageStatus.Read), inAlbum(2, caption = "the view", status = MessageStatus.Sending), inAlbum(1, status = MessageStatus.Read).copy(senderName = "Anna")),
            unread = null,
        ).first() as ListRow.Album
        assertEquals(2L, album.main.id)
        assertEquals("the view", album.shown.text)
        assertEquals("Anna", album.shown.senderName)
        assertEquals(MessageStatus.Sending, album.shown.status)
        assertEquals(3L, album.key)
    }

    @Test
    fun `albums with several captions or a failed send stay apart`() {
        val captions = buildRows(listOf(inAlbum(2, caption = "one"), inAlbum(1, caption = "two")), unread = null)
        assertEquals(listOf("m2", "m1", "day"), captions.labels())
        val failed = buildRows(listOf(inAlbum(2, status = MessageStatus.Failed), inAlbum(1, status = MessageStatus.Sent)), unread = null)
        assertEquals(listOf("m2", "m1", "day"), failed.labels())
    }

    @Test
    fun `album rows go from two side by side to rows of three`() {
        fun rows(vararg aspects: Float) = albumRows(aspects.toList()).map { it.count() }
        assertEquals(listOf(2), rows(0.75f, 0.75f))
        assertEquals(listOf(1, 1), rows(1.5f, 1.8f))
        assertEquals(listOf(1, 2), rows(1f, 1f, 1f))
        assertEquals(listOf(1, 3), rows(1.5f, 1f, 1f, 1f))
        assertEquals(listOf(2, 2), rows(0.75f, 1f, 1f, 1f))
        assertEquals(listOf(2, 3), rows(*FloatArray(5) { 1f }))
        assertEquals(listOf(2, 2, 3), rows(*FloatArray(7) { 1f }))
        assertEquals(listOf(2, 2, 3, 3), rows(*FloatArray(10) { 1f }))
        assertEquals(listOf(0 until 2, 2 until 5), albumRows(List(5) { 1f }))
    }

    @Test
    fun `day labels name the weekday this year and the year otherwise`() {
        val today = LocalDate.of(2026, 10, 24)
        assertEquals("Friday, 23 October", dayLabel(NOON_2026_10_23, today, ZoneOffset.UTC, Locale.ENGLISH))
        assertEquals("23 October 2026", dayLabel(NOON_2026_10_23, LocalDate.of(2027, 1, 2), ZoneOffset.UTC, Locale.ENGLISH))
    }
}
