package dev.otmgram.ui.chat

import dev.otmgram.data.ChatHistory
import dev.otmgram.data.SenderPhoto
import dev.otmgram.testing.NOON_2026_10_23
import dev.otmgram.testing.TestStrings
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
        ).toItems(isGroup = false, TestStrings)
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
        ).toItems(isGroup = true, TestStrings)
        assertEquals(listOf(null, "Anna", "Lea", null, "Lea"), items.map { it.senderName })
    }

    @Test
    fun `in groups the sender's avatar shows by the newest of their messages in a row, each day`() {
        // Newest first: Anna, Anna, Lea, you, Lea, and Anna the day before.
        val items = history(
            message(6, from = 2),
            message(5, from = 2),
            message(4, from = 3),
            message(3, from = 1, outgoing = true),
            message(2, from = 3),
            message(1, from = 2, date = NOON_2026_10_23 - 24 * 3600),
        ).toItems(isGroup = true, TestStrings)
        assertEquals(listOf(true, false, true, false, true, true), items.map { it.showAvatar })
        assertEquals(listOf("Anna", "Anna", "Lea", null, "Lea", "Anna"), items.map { it.sender?.name })
    }

    @Test
    fun `group events say who did what to whom, once the names are known`() {
        fun event(id: Long, content: TdApi.MessageContent, from: Long = 2, outgoing: Boolean = false) = message(id, content, from = from, outgoing = outgoing)
        val items = history(
            event(6, TdApi.MessageChatAddMembers(longArrayOf(3, 4, 5))),
            event(5, TdApi.MessageChatAddMembers(longArrayOf(2))),
            event(4, TdApi.MessageChatDeleteMember(3)),
            event(3, TdApi.MessageChatDeleteMember(2)),
            event(2, TdApi.MessageChatJoinByLink(), from = 1, outgoing = true),
            event(1, TdApi.MessageChatAddMembers(longArrayOf(99))),
            names = mapOf(2L to "Anna", 3L to "Lea", 4L to "Tom", 5L to "Max"),
        ).toItems(isGroup = true, TestStrings)
        assertEquals(
            listOf(
                "Anna added Lea, Tom and Max",
                "Anna joined the group",
                "Anna removed Lea",
                "Anna left the group",
                "You joined the group via invite link",
                "Joined the group", // Whom isn't known.
            ),
            items.map { it.text },
        )
    }

    @Test
    fun `private chats show no sender names`() {
        val items = history(message(2, from = 2), message(1, from = 3)).toItems(isGroup = false, TestStrings)
        assertTrue(items.all { it.senderName == null && it.sender == null && !it.showAvatar })
    }

    @Test
    fun `replies quote the replied message to jump to, or say it's gone`() {
        val items = history(
            message(3, replyTo = 1),
            message(2, replyTo = 99),
            message(1, content = text("hello"), from = 3),
        ).toItems(isGroup = true, TestStrings)
        assertEquals(ReplyPreview("Lea", "hello", messageId = 1), items[0].replyTo)
        assertEquals(ReplyPreview(null, "Deleted message"), items[1].replyTo)
        assertNull(items[2].replyTo)
    }

    @Test
    fun `a reply to a photo shows a small size of it, to a round video its thumbnail, round`() {
        fun size(type: String, side: Int, fileId: Int) = TdApi.PhotoSize(type, TdApi.File().apply { id = fileId }, side, side, IntArray(0))
        val photo = TdApi.MessagePhoto().apply {
            photo = TdApi.Photo(false, null, arrayOf(size("s", 90, 11), size("m", 320, 12), size("x", 800, 13)))
            caption = TdApi.FormattedText("", emptyArray())
        }
        val round = TdApi.MessageVideoNote().apply {
            videoNote = TdApi.VideoNote().apply {
                video = TdApi.File().apply { id = 20 }
                thumbnail = TdApi.Thumbnail(TdApi.ThumbnailFormatJpeg(), 240, 240, TdApi.File().apply { id = 21 })
            }
        }
        val items = history(
            message(4, replyTo = 2),
            message(3, replyTo = 1),
            message(2, content = round, from = 3),
            message(1, content = photo, from = 3),
        ).toItems(isGroup = true, TestStrings)
        assertEquals(QuoteThumbnail(11, null), items[1].replyTo?.thumbnail)
        assertEquals(QuoteThumbnail(21, null, round = true), items[0].replyTo?.thumbnail)
    }

    @Test
    fun `reactions keep their counts and which ones are yours`() {
        val reactions = listOf(
            TdApi.MessageReaction(TdApi.ReactionTypeEmoji("🔥"), 3, true, null, emptyArray()),
            TdApi.MessageReaction(TdApi.ReactionTypePaid(), 1, false, null, emptyArray()),
        )
        val chips = history(message(1, reactions = reactions)).toItems(isGroup = false, TestStrings).single().reactions
        assertEquals(ReactionChip("🔥", "🔥", 3, true), chips[0])
        // Paid reactions show, but can't be added from here.
        assertEquals(ReactionChip("⭐", null, 1, false), chips[1])
    }

    @Test
    fun `few who reacted show by face, more as a count`() {
        fun users(vararg ids: Long) = ids.map { TdApi.MessageSenderUser(it) }.toTypedArray<TdApi.MessageSender>()
        val reactions = listOf(
            TdApi.MessageReaction(TdApi.ReactionTypeEmoji("👍"), 2, false, null, users(2, 3)),
            TdApi.MessageReaction(TdApi.ReactionTypeEmoji("🔥"), 5, false, null, users(2, 3, 4)),
        )
        val chips = history(message(1, reactions = reactions)).toItems(isGroup = true, TestStrings).single().reactions
        assertEquals(listOf("Anna", "Lea"), chips[0].reactors.map { it.name })
        assertTrue(chips[1].reactors.isEmpty())
    }

    @Test
    fun `a contact shows its name, phone and, on Telegram, face`() {
        val contact = TdApi.MessageContact(TdApi.Contact("491512345678", "Anna", "Smith", "", 2))
        val history = ChatHistory(
            messages = listOf(message(1, content = contact)),
            senderNames = mapOf(2L to "Anna Smith"),
            senderPhotos = mapOf(2L to SenderPhoto(7, null)),
        )
        assertEquals(MessageMedia.Contact(2, "Anna Smith", "+491512345678", photoFileId = 7), history.toItems(isGroup = false, TestStrings).single().media)
    }

    @Test
    fun `media without a view is a placeholder with its description`() {
        val poll = message(1, content = TdApi.MessagePoll())
        val item = history(poll).toItems(isGroup = false, TestStrings).single()
        assertTrue(item.isMediaPlaceholder)
        assertEquals("Message", item.text)
    }

    @Test
    fun `text messages can be edited, stickers can't`() {
        val items = history(
            message(2),
            message(
                1,
                content = TdApi.MessageSticker().apply {
                    sticker = TdApi.Sticker().apply {
                        sticker = dev.otmgram.testing.file(7)
                        format = TdApi.StickerFormatWebp()
                        emoji = "🙂"
                    }
                },
            ),
        ).toItems(isGroup = false, TestStrings)
        assertTrue(items[0].hasEditableText)
        assertFalse(items[1].hasEditableText)
    }
}
