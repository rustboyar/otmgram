package dev.otmgram.ui.chat

import dev.otmgram.ui.embed.MediaLink
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkCardTest {

    private fun textWithLink(text: String, url: String, preview: TdApi.LinkPreview? = null) = TdApi.MessageText().apply {
        val start = text.indexOf(url)
        this.text = TdApi.FormattedText(text, arrayOf(TdApi.TextEntity(start, url.length, TdApi.TextEntityTypeUrl())))
        linkPreview = preview
    }

    private fun preview(url: String, title: String, photoId: Int) = TdApi.LinkPreview().apply {
        this.url = url
        this.title = title
        type = TdApi.LinkPreviewTypeEmbeddedVideoPlayer().apply {
            thumbnail = TdApi.Photo(false, null, arrayOf(TdApi.PhotoSize("m", TdApi.File().apply { id = photoId }, 320, 180, IntArray(0))))
        }
    }

    @Test
    fun `a media link without a preview still gets a card`() {
        val card = textWithLink("look https://youtu.be/dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ").linkCard()
        assertEquals(MediaLink.Site.YouTube, card?.link?.site)
        assertNull(card?.title)
        assertNull(card?.thumbnailFileId)
    }

    @Test
    fun `the preview's title and picture go on the card`() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val card = textWithLink(url, url, preview(url, "A song", photoId = 7)).linkCard()
        assertEquals("A song", card?.title)
        assertEquals(7, card?.thumbnailFileId)
        assertEquals(320, card?.width)
    }

    @Test
    fun `a preview of another link doesn't describe the media link`() {
        val card = textWithLink(
            "https://youtu.be/dQw4w9WgXcQ and https://youtu.be/aqz-KE-bpKQ",
            "https://youtu.be/dQw4w9WgXcQ",
            preview("https://youtu.be/aqz-KE-bpKQ", "Other", photoId = 7),
        ).linkCard()
        // The preview's link comes first, and it is a media link too: that one gets the card.
        assertEquals("aqz-KE-bpKQ", card?.link?.id)
        assertEquals("Other", card?.title)
    }

    @Test
    fun `ordinary links get no card`() {
        assertNull(textWithLink("https://example.com", "https://example.com").linkCard())
    }
}
