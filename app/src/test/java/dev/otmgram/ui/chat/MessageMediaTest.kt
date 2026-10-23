package dev.otmgram.ui.chat

import dev.otmgram.testing.NOON_2026_10_23
import dev.otmgram.testing.TestStrings
import dev.otmgram.testing.file
import dev.otmgram.testing.message
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageMediaTest {

    private fun size(type: String, id: Int, width: Int, height: Int) = TdApi.PhotoSize(type, file(id), width, height, intArrayOf())

    private fun photo(vararg sizes: TdApi.PhotoSize) = TdApi.MessagePhoto().apply {
        photo = TdApi.Photo().apply { this.sizes = arrayOf(*sizes) }
        caption = TdApi.FormattedText("", emptyArray())
    }

    @Test
    fun `bubbles use the largest photo size up to 1280 px`() {
        val media = photo(size("s", 1, 90, 60), size("m", 2, 320, 213), size("x", 3, 1280, 853), size("y", 4, 2560, 1706)).toMedia()
        assertEquals(3, (media as MessageMedia.Photo).fileId)
    }

    @Test
    fun `only huge sizes fall back to the smallest`() {
        val media = photo(size("y", 4, 2560, 1706), size("w", 5, 5000, 3000)).toMedia()
        assertEquals(4, (media as MessageMedia.Photo).fileId)
    }

    private fun live(period: Int, expiresIn: Int, edited: Int = 0) = message(
        1,
        content = TdApi.MessageLiveLocation(TdApi.LiveLocation(TdApi.Location(52.5, 13.4, 5.0), period, 0, 0), expiresIn),
    ).apply { editDate = edited }.toMedia() as MessageMedia.Location

    @Test
    fun `live locations end after their period`() {
        val info = live(period = 900, expiresIn = 600)!!.live!!
        assertEquals(NOON_2026_10_23 + 900, info.expiresAt)
        assertEquals(NOON_2026_10_23, info.updatedAt)
    }

    @Test
    fun `a stopped live location has ended`() {
        assertEquals(0, live(period = 900, expiresIn = 0).live!!.expiresAt)
    }

    @Test
    fun `a live location until stopped never expires, and moves with edits`() {
        val info = live(period = LIVE_FOREVER, expiresIn = 100, edited = NOON_2026_10_23 + 60).live!!
        assertEquals(Int.MAX_VALUE, info.expiresAt)
        assertEquals(NOON_2026_10_23 + 60, info.updatedAt)
    }

    @Test
    fun `live status says how fresh the position is`() {
        val info = MessageMedia.LiveInfo(updatedAt = 1_000, expiresAt = 5_000)
        assertEquals("Live · updated just now", liveStatus(info, now = 1_030, TestStrings))
        assertEquals("Live · updated 2 min ago", liveStatus(info, now = 1_150, TestStrings))
        assertEquals("Live location ended", liveStatus(info, now = 5_000, TestStrings))
    }

    @Test
    fun `a plain location isn't live`() {
        assertNull((TdApi.MessageLocation().apply { location = TdApi.Location(1.0, 2.0, 0.0) }.toMedia() as MessageMedia.Location).live)
    }

    @Test
    fun `audio files are shown as files with a readable name`() {
        val audio = TdApi.MessageAudio().apply {
            this.audio = TdApi.Audio().apply {
                this.audio = file(9, size = 4_000_000)
                fileName = ""
                performer = "Kino"
                title = "Gruppa krovi"
                mimeType = "audio/mpeg"
            }
            caption = TdApi.FormattedText("", emptyArray())
        }
        assertEquals("Kino – Gruppa krovi", (audio.toMedia() as MessageMedia.Document).name)
    }
}
