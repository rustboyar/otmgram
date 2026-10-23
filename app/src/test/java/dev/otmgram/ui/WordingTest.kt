package dev.otmgram.ui

import androidx.compose.ui.graphics.Color
import dev.otmgram.data.SizeEstimate
import dev.otmgram.testing.NOON_2026_10_23
import dev.otmgram.testing.message
import dev.otmgram.testing.text
import dev.otmgram.ui.chat.formatDuration
import dev.otmgram.ui.chat.lastSeen
import dev.otmgram.ui.chat.sizeLine
import dev.otmgram.ui.map.distanceMeters
import dev.otmgram.ui.map.formatDistance
import dev.otmgram.ui.media.formatSize
import org.drinkless.tdlib.TdApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** What the app says: sizes, durations, distances, summaries, statuses. */
class WordingTest {

    private val locale = Locale.getDefault()

    @Before
    fun englishNumbers() = Locale.setDefault(Locale.US)

    @After
    fun restore() = Locale.setDefault(locale)

    @Test
    fun sizes() {
        assertEquals("120 B", formatSize(120))
        assertEquals("830 KB", formatSize(830_000))
        assertEquals("2.4 MB", formatSize(2_400_000))
    }

    @Test
    fun durations() {
        assertEquals("0:07", formatDuration(7))
        assertEquals("1:05", formatDuration(65))
        assertEquals("12:00", formatDuration(720))
    }

    @Test
    fun `compression estimate`() {
        assertEquals("About 3.4 MB instead of 15.7 MB", sizeLine(SizeEstimate(15_700_000, 3_400_000), compress = true))
        assertEquals("Already small: 1.2 MB", sizeLine(SizeEstimate(1_200_000, 1_150_000), compress = true))
        assertEquals("15.7 MB, sent at full quality", sizeLine(SizeEstimate(15_700_000, 3_400_000), compress = false))
        assertEquals("Smaller and faster to send", sizeLine(null, compress = true))
    }

    @Test
    fun distances() {
        // Berlin TV tower to the Brandenburg Gate: about 2.1 km.
        val meters = distanceMeters(52.5208, 13.4094, 52.5163, 13.3777)
        assertTrue(meters in 2_100.0..2_250.0)
        assertEquals("340 m away", formatDistance(337.0))
        assertEquals("2.1 km away", formatDistance(2_140.0))
        assertEquals("23 km away", formatDistance(23_400.0))
    }

    @Test
    fun `last seen`() {
        val today = LocalDate.of(2026, 10, 23)
        assertEquals("online", lastSeen(TdApi.UserStatusOnline(0), today, ZoneOffset.UTC))
        // Java puts a narrow no-break space before "PM"; the time itself is what matters.
        assertTrue(lastSeen(TdApi.UserStatusOffline(NOON_2026_10_23), today, ZoneOffset.UTC)!!.startsWith("last seen at 12:00"))
        assertEquals("last seen Oct 23, 2026", lastSeen(TdApi.UserStatusOffline(NOON_2026_10_23), today.plusDays(3), ZoneOffset.UTC))
        assertEquals("last seen recently", lastSeen(TdApi.UserStatusRecently(), today, ZoneOffset.UTC))
        assertEquals(null, lastSeen(TdApi.UserStatusEmpty(), today, ZoneOffset.UTC))
    }

    @Test
    fun `message summaries`() {
        assertEquals("hi", message(1, content = text("hi")).summary())
        val photo = TdApi.MessagePhoto().apply { caption = TdApi.FormattedText("the view", emptyArray()) }
        assertEquals("Photo, the view", photo.summary())
        assertEquals("Video message", TdApi.MessageVideoNote().summary())
        assertEquals("Group renamed to \"band\"", TdApi.MessageChatChangeTitle("band").summary())
    }

    @Test
    fun `search matches are marked wherever they are`() {
        val marked = highlightMatches("Saturday? saturday!", "SATURDAY", Color.Yellow)
        assertEquals(listOf(0 to 8, 10 to 18), marked.spanStyles.map { it.start to it.end })
        assertEquals(0, highlightMatches("anything", "  ", Color.Yellow).spanStyles.size)
    }
}
