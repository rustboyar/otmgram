package dev.otmgram.ui.chat

import dev.otmgram.data.ChatHistory
import dev.otmgram.testing.message
import dev.otmgram.testing.text
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageItemsTest {

    private fun history(vararg messages: TdApi.Message, lastReadOutbox: Long = 0, names: Map<Long, String> = mapOf(2L to "Anna", 3L to "Lea")) =
        ChatHistory(messages = messages.toList(), senderNames = names, lastReadOutboxMessageId = lastReadOutbox)

    @Test
    fun `delivery of your messages`() {
        val items = history(
            message(5, outgoing = true, from = 1, sending = TdApi.MessageSendingStatePending()),
            message(4, outgoing = true, from = 1, sending = TdApi.MessageSendingStateFailed()),
            message(3, outgoing = true, from = 1),
            message(2, outgoing = true, from = 1),
            message(1),
            lastReadOutbox = 2,
        ).toItems(isGroup = false)
        assertEquals(
            listOf(MessageStatus.Sending, MessageStatus.Failed, MessageStatus.Sent, MessageStatus.Read, null),
            items.map { it.status },
        )
    }

    @Test
    fun `in groups the sender shows above the first of their consecutive messages`() {
        // Newest first: Anna, Anna, Lea, you, Lea.
        val items = history(
            message(5, from = 2),
            message(4, from = 2),
            message(3, from = 3),
            message(2, from = 1, outgoing = true),
            message(1, from = 3),
        ).toItems(isGroup = true)
        assertEquals(listOf(null, "Anna", "Lea", null, "Lea"), items.map { it.senderName })
    }

    @Test
    fun `private chats show no sender names`() {
        val items = history(message(2, from = 2), message(1, from = 3)).toItems(isGroup = false)
        assertTrue(items.all { it.senderName == null })
    }

    @Test
    fun `replies quote the replied message, or say it's gone`() {
        val items = history(
            message(3, replyTo = 1),
            message(2, replyTo = 99),
            message(1, content = text("hello"), from = 3),
        ).toItems(isGroup = true)
        assertEquals(ReplyPreview("Lea", "hello"), items[0].replyTo)
        assertEquals(ReplyPreview(null, "Deleted message"), items[1].replyTo)
        assertNull(items[2].replyTo)
    }

    @Test
    fun `reactions keep their counts and which ones are yours`() {
        val reactions = listOf(
            TdApi.MessageReaction(TdApi.ReactionTypeEmoji("🔥"), 3, true, null, emptyArray()),
            TdApi.MessageReaction(TdApi.ReactionTypePaid(), 1, false, null, emptyArray()),
        )
        val chips = history(message(1, reactions = reactions)).toItems(isGroup = false).single().reactions
        assertEquals(ReactionChip("🔥", "🔥", 3, true), chips[0])
        // Paid reactions show, but can't be added from here.
        assertEquals(ReactionChip("⭐", null, 1, false), chips[1])
    }

    @Test
    fun `media without a view is a placeholder with its description`() {
        val poll = message(1, content = TdApi.MessagePoll())
        val item = history(poll).toItems(isGroup = false).single()
        assertTrue(item.isMediaPlaceholder)
        assertEquals("Message", item.text)
    }

    @Test
    fun `text messages can be edited, stickers can't`() {
        val items = history(message(2), message(1, content = TdApi.MessageSticker().apply {
            sticker = TdApi.Sticker().apply {
                sticker = dev.otmgram.testing.file(7)
                format = TdApi.StickerFormatWebp()
                emoji = "🙂"
            }
        })).toItems(isGroup = false)
        assertTrue(items[0].hasEditableText)
        assertFalse(items[1].hasEditableText)
    }
}
