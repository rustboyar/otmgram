package dev.otmgram.ui.embed

import dev.otmgram.ui.embed.MediaLink.Site
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaLinkTest {

    private fun parse(url: String) = MediaLink.parse(url)

    @Test
    fun `youtube links in their forms`() {
        assertEquals("dQw4w9WgXcQ", parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=abc")?.id)
        assertEquals("dQw4w9WgXcQ", parse("https://youtu.be/dQw4w9WgXcQ?si=abc")?.id)
        assertEquals("dQw4w9WgXcQ", parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ")?.id)
        assertEquals("dQw4w9WgXcQ", parse("https://youtube.com/live/dQw4w9WgXcQ")?.id)
        val short = parse("https://youtube.com/shorts/aqz-KE-bpKQ?feature=share")
        assertEquals(Site.YouTube, short?.site)
        assertEquals(true, short?.upright)
        assertEquals(false, parse("https://youtu.be/dQw4w9WgXcQ")?.upright)
    }

    @Test
    fun `a youtube start time carries over`() {
        assertEquals(90, parse("https://youtu.be/dQw4w9WgXcQ?t=90")?.start)
        assertEquals(90, parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s")?.start)
        assertEquals(
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?autoplay=1&playsinline=1&start=90",
            parse("https://youtu.be/dQw4w9WgXcQ?t=90")?.embedUrl,
        )
    }

    @Test
    fun `instagram posts and reels, also after a user name`() {
        val reel = parse("https://www.instagram.com/reel/C9xYz12AbCd/?igsh=MWQ1")
        assertEquals("C9xYz12AbCd", reel?.id)
        assertEquals(true, reel?.upright)
        assertEquals("https://www.instagram.com/p/C9xYz12AbCd/embed/", reel?.embedUrl)
        assertEquals(false, parse("https://instagram.com/p/C9xYz12AbCd/")?.upright)
        assertEquals("C9xYz12AbCd", parse("https://www.instagram.com/someone/reel/C9xYz12AbCd/")?.id)
    }

    @Test
    fun `tiktok videos, and short links to follow first`() {
        val video = parse("https://www.tiktok.com/@someone/video/7301234567890123456?lang=en")
        assertEquals("7301234567890123456", video?.id)
        assertEquals("https://www.tiktok.com/player/v1/7301234567890123456?embed_from=oembed&music_info=1&description=1&autoplay=1", video?.embedUrl)
        val short = parse("https://vm.tiktok.com/ZMabc123/")
        assertEquals(Site.TikTok, short?.site)
        assertNull(short?.id)
        assertNull(short?.embedUrl)
        assertNull(parse("https://www.tiktok.com/t/ZTabc123/")?.id)
        assertEquals("7301234567890123456", parse("https://m.tiktok.com/v/7301234567890123456.html")?.id)
    }

    @Test
    fun `other links aren't media links`() {
        assertNull(parse("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(parse("https://www.youtube.com/@channel"))
        assertNull(parse("https://www.instagram.com/someone/"))
        assertNull(parse("https://www.tiktok.com/@someone"))
        assertNull(parse("javascript:alert(1)"))
        assertNull(parse("not a link"))
    }
}
