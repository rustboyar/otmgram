package dev.otmgram.ui.profile

import dev.otmgram.testing.CHAT_ID
import dev.otmgram.testing.FakeTelegram
import dev.otmgram.testing.file
import dev.otmgram.testing.message
import dev.otmgram.testing.user
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMediaTest {

    private val telegram = FakeTelegram()

    private fun photo(vararg sides: Int) = TdApi.MessagePhoto().apply {
        photo = TdApi.Photo().apply {
            sizes = sides.mapIndexed { i, side -> TdApi.PhotoSize("s$i", file(side), side, side * 3 / 4, IntArray(0)) }.toTypedArray()
        }
        caption = TdApi.FormattedText("", emptyArray())
    }

    private fun voice(fileId: Int) = TdApi.MessageVoiceNote().apply {
        voiceNote = TdApi.VoiceNote(3, ByteArray(0), "audio/ogg", null, file(fileId))
        caption = TdApi.FormattedText("", emptyArray())
    }

    private fun withLink(text: String, url: String, preview: TdApi.LinkPreview? = null) = TdApi.MessageText().apply {
        val start = text.indexOf(url)
        this.text = TdApi.FormattedText(text, arrayOf(TdApi.TextEntity(start, url.length, TdApi.TextEntityTypeUrl())))
        linkPreview = preview
    }

    @Test
    fun `tabs show only the kinds the chat has`() = runTest {
        telegram.on<TdApi.GetChatMessageCount> {
            TdApi.Count(
                when (it.filter) {
                    is TdApi.SearchMessagesFilterPhotoAndVideo -> 12
                    is TdApi.SearchMessagesFilterUrl -> 3
                    else -> 0
                },
            )
        }
        val shared = SharedMedia(telegram, CHAT_ID)
        shared.loadCounts()
        assertEquals(mapOf(SharedKind.Media to 12, SharedKind.Links to 3), shared.counts)
    }

    @Test
    fun `pages follow on from the last, until there are no more, and another tab starts over`() = runTest {
        telegram.on<TdApi.SearchChatMessages> { request ->
            when (request.fromMessageId) {
                0L -> TdApi.FoundChatMessages(3, arrayOf(message(30, photo(320)), message(20, photo(320))), 20)
                20L -> TdApi.FoundChatMessages(3, arrayOf(message(10, photo(320))), 0)
                else -> error("unexpected page")
            }
        }
        val shared = SharedMedia(telegram, CHAT_ID)
        shared.select(SharedKind.Media)
        assertEquals(listOf(30L, 20L), shared.items.map { it.messageId })
        assertEquals(false, shared.complete)

        shared.loadMore()
        assertEquals(listOf(30L, 20L, 10L), shared.items.map { it.messageId })
        assertTrue(shared.complete)
        shared.loadMore() // Nothing more to ask for.
        assertEquals(2, telegram.sent<TdApi.SearchChatMessages>().size)

        telegram.on<TdApi.SearchChatMessages> { TdApi.FoundChatMessages(0, emptyArray(), 0) }
        shared.select(SharedKind.Files)
        assertEquals(emptyList<SharedItem>(), shared.items)
        assertTrue(telegram.sent<TdApi.SearchChatMessages>().last().filter is TdApi.SearchMessagesFilterDocument)
    }

    @Test
    fun `voice messages say who sent them, asked once per sender`() = runTest {
        telegram.on<TdApi.SearchChatMessages> {
            TdApi.FoundChatMessages(2, arrayOf(message(2, voice(7), from = 5), message(1, voice(8), from = 5)), 0)
        }
        telegram.on<TdApi.GetUser> { user(5, "Lea", "Berg") }
        val shared = SharedMedia(telegram, CHAT_ID)
        shared.select(SharedKind.Voice)
        assertEquals(listOf("Lea Berg", "Lea Berg"), shared.items.map { (it as SharedItem.Voice).sender })
        assertEquals(1, telegram.sent<TdApi.GetUser>().size)
    }

    @Test
    fun `the grid takes a photo's smallest size that still fills a square`() {
        val item = message(1, photo(90, 320, 800, 1280)).toShared(SharedKind.Media) as SharedItem.Visual
        // Sizes are w x w*3/4: 320 is 240 high, the first at least 240 on its short side.
        assertEquals(320, item.thumbnailFileId)
        assertNull(item.duration)
    }

    @Test
    fun `a link shows its preview's title and picture, or the site's address`() {
        val preview = TdApi.LinkPreview().apply {
            url = "https://example.com/post"
            title = "A post"
            siteName = "Example"
            type = TdApi.LinkPreviewTypeArticle().apply { photo = photo(90, 320).photo }
        }
        val described = message(1, withLink("look https://example.com/post", "https://example.com/post", preview)).toShared(SharedKind.Links) as SharedItem.Link
        assertEquals("A post", described.title)
        assertEquals("https://example.com/post", described.url)
        assertEquals(320, described.thumbnailFileId) // 90 is under 90 px high.

        val bare = message(2, withLink("www.example.org/x", "www.example.org/x")).toShared(SharedKind.Links) as SharedItem.Link
        assertEquals("example.org", bare.title)
        assertNull(bare.thumbnailFileId)
    }

    @Test
    fun `a link in a photo's caption is listed as a link`() {
        val captioned = photo(320).apply { caption = TdApi.FormattedText("see https://a.dev", arrayOf(TdApi.TextEntity(4, 13, TdApi.TextEntityTypeUrl()))) }
        val item = message(1, captioned).toShared(SharedKind.Links)
        assertEquals("https://a.dev", (item as SharedItem.Link).url)
    }
}
