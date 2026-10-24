package dev.otmgram.ui.chat

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which parts of a message become links or formatting. */
class TextSpansTest {

    private fun text(text: String, vararg entities: Triple<Int, Int, TdApi.TextEntityType>) =
        TdApi.FormattedText(text, entities.map { (offset, length, type) -> TdApi.TextEntity(offset, length, type) }.toTypedArray())

    @Test
    fun `web links open, a bare address over https`() {
        val spans = text("see example.com/a", Triple(4, 13, TdApi.TextEntityTypeUrl())).spans()
        assertEquals(listOf(TextSpan(4, 17, SpanKind.Link("https://example.com/a", hidden = false))), spans)
    }

    @Test
    fun `a link behind other words asks first`() {
        val spans = text("my bank", Triple(0, 7, TdApi.TextEntityTypeTextUrl("https://bank.example.net"))).spans()
        assertEquals(SpanKind.Link("https://bank.example.net", hidden = true), spans.single().kind)
    }

    @Test
    fun `schemes that open other apps stay text`() {
        assertNull(safeUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(safeUrl("market://details?id=x"))
        assertNull(safeUrl("javascript:alert(1)"))
        assertEquals("https://example.com:8080", safeUrl("example.com:8080"))
        val spans = text("tap", Triple(0, 3, TdApi.TextEntityTypeTextUrl("tg://resolve?domain=x"))).spans()
        assertEquals(emptyList<TextSpan>(), spans)
    }

    @Test
    fun `mail, phone and formatting`() {
        val spans = text(
            "a@b.org +49 30 1234 bold secret",
            Triple(0, 7, TdApi.TextEntityTypeEmailAddress()),
            Triple(8, 11, TdApi.TextEntityTypePhoneNumber()),
            Triple(20, 4, TdApi.TextEntityTypeBold()),
            Triple(25, 6, TdApi.TextEntityTypeSpoiler()),
            Triple(0, 1, TdApi.TextEntityTypeMention()),
        ).spans()
        assertEquals(
            listOf(SpanKind.Link("mailto:a@b.org", false), SpanKind.Link("tel:+49301234", false), SpanKind.Bold, SpanKind.Spoiler),
            spans.map { it.kind },
        )
    }

    @Test
    fun `entities outside the text are ignored`() {
        assertEquals(emptyList<TextSpan>(), text("short", Triple(3, 10, TdApi.TextEntityTypeBold())).spans())
    }
}
