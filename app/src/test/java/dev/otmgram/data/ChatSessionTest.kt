package dev.otmgram.data

import dev.otmgram.testing.CHAT_ID
import dev.otmgram.testing.FakeTelegram
import dev.otmgram.testing.chat
import dev.otmgram.testing.error
import dev.otmgram.testing.message
import dev.otmgram.testing.messages
import dev.otmgram.testing.user
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionTest {

    private val td = FakeTelegram()

    /** The chat's history, newest first, as TDLib would page it. */
    private var history = listOf(message(30), message(20, outgoing = true, from = 1), message(10))

    /** Messages scheduled in the chat, as TDLib lists them. */
    private var scheduled = emptyList<TdApi.Message>()

    private fun scheduledAt(id: Long, sendAt: Int) = message(id, outgoing = true, from = 1).apply {
        schedulingState = TdApi.MessageSchedulingStateSendAtDate(sendAt, 0)
    }

    @Before
    fun setUp() {
        td.on<TdApi.OpenChat> { TdApi.Ok() }
        td.on<TdApi.GetChat> { chat(CHAT_ID, "band", lastMessage = null).apply { lastReadOutboxMessageId = 20 } }
        td.on<TdApi.GetUser> { user(it.userId, "User${it.userId}") }
        td.on<TdApi.GetChatScheduledMessages> { messages(*scheduled.toTypedArray()) }
        // As TDLib: from a message on (inclusive), a negative offset reaching into newer ones.
        td.on<TdApi.GetChatHistory> { request ->
            val at = if (request.fromMessageId == 0L) 0 else history.indexOfFirst { it.id <= request.fromMessageId }.let { if (it < 0) history.size else it }
            val start = (at + request.offset).coerceAtLeast(0)
            messages(*history.drop(start).take(request.limit).toTypedArray())
        }
    }

    private fun TestScope.open(): ChatSession = ChatSession(td, CHAT_ID, backgroundScope).also { runCurrent() }

    private val ChatSession.ids get() = history.value.messages.map { it.id }

    @Test
    fun `opening loads the history and the read mark`() = runTest {
        val session = open()
        assertEquals(listOf(30L, 20L, 10L), session.ids)
        assertEquals(20L, session.history.value.lastReadOutboxMessageId)
        assertTrue(session.history.value.reachedStart)
        assertEquals("User2", session.history.value.senderNames[2L])
        assertEquals(1, td.sent<TdApi.OpenChat>().size)
    }

    @Test
    fun `scheduled messages are listed apart, the soonest first, never in the history`() = runTest {
        scheduled = listOf(scheduledAt(901, sendAt = 2000), scheduledAt(902, sendAt = 1000))
        val session = open()
        assertEquals(listOf(902L, 901L), session.scheduled.value.map { it.id })

        // One more scheduled from another device: not in the history, the list asked for again.
        scheduled = scheduled + scheduledAt(903, sendAt = 1500)
        td.emit(TdApi.UpdateNewMessage(scheduled.last()))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(30L, 20L, 10L), session.ids)
        assertEquals(listOf(902L, 903L, 901L), session.scheduled.value.map { it.id })
    }

    @Test
    fun `scheduling sends at the time, and a scheduled message can go now or later`() = runTest {
        td.on<TdApi.SendMessage> { message(1) }
        td.on<TdApi.EditMessageSchedulingState> { TdApi.Ok() }
        val session = open()
        session.sendText("later", sendAt = 1234)
        assertEquals(1234, (td.sent<TdApi.SendMessage>().single().options.schedulingState as TdApi.MessageSchedulingStateSendAtDate).sendDate)
        session.reschedule(901, sendAt = 2000)
        session.sendScheduledNow(901)
        val (later, now) = td.sent<TdApi.EditMessageSchedulingState>()
        assertEquals(2000, (later.schedulingState as TdApi.MessageSchedulingStateSendAtDate).sendDate)
        assertEquals(null, now.schedulingState)
    }

    @Test
    fun `pinned messages are searched for, and pinned quietly for everyone`() = runTest {
        td.on<TdApi.SearchChatMessages> { TdApi.FoundChatMessages(1, arrayOf(message(20)), 0) }
        td.on<TdApi.PinChatMessage> { TdApi.Ok() }
        td.on<TdApi.UnpinChatMessage> { TdApi.Ok() }
        val session = open()
        assertEquals(listOf(20L), session.pinned().map { it.id })
        assertTrue(td.sent<TdApi.SearchChatMessages>().single().filter is TdApi.SearchMessagesFilterPinned)
        session.setPinned(10, pinned = true)
        session.setPinned(20, pinned = false)
        val pin = td.sent<TdApi.PinChatMessage>().single()
        assertEquals(10L, pin.messageId)
        assertTrue(pin.disableNotification)
        assertFalse(pin.onlyForSelf)
        assertEquals(20L, td.sent<TdApi.UnpinChatMessage>().single().messageId)
    }

    /** A long chat: ids 10..2000, newest first. */
    private fun longHistory() {
        history = (2000L downTo 10L step 10).map { message(it) }
    }

    @Test
    fun `jumping far back loads the messages around, not everything in between`() = runTest {
        longHistory()
        val session = open()
        assertTrue(session.jumpTo(100))
        assertTrue(100L in session.ids)
        assertFalse(session.history.value.reachedEnd)
        assertTrue(session.ids.size <= 50)
        assertEquals(2, td.sent<TdApi.GetChatHistory>().size)
    }

    @Test
    fun `after a jump, newer messages load up to the newest, without gaps`() = runTest {
        longHistory()
        td.on<TdApi.GetChat> { chat(CHAT_ID, "band", lastMessage = message(2000)) }
        val session = open()
        session.jumpTo(100)
        repeat(50) { session.loadNewer() }
        assertTrue(session.history.value.reachedEnd)
        assertEquals((2000L downTo session.ids.last() step 10).toList(), session.ids)
    }

    @Test
    fun `away from the newest, new messages wait, and sending brings the newest back`() = runTest {
        longHistory()
        val session = open()
        session.jumpTo(100)
        td.emit(TdApi.UpdateNewMessage(message(2010)))
        runCurrent()
        assertFalse(2010L in session.ids)
        history = listOf(message(2020, outgoing = true, from = 1), message(2010)) + history
        td.emit(TdApi.UpdateNewMessage(message(2020, outgoing = true, from = 1)))
        runCurrent()
        assertTrue(session.history.value.reachedEnd)
        assertEquals(listOf(2020L, 2010L), session.ids.take(2))
    }

    @Test
    fun `a message already loaded is jumped to without loading`() = runTest {
        val session = open()
        val requests = td.sent<TdApi.GetChatHistory>().size
        assertTrue(session.jumpTo(20))
        assertEquals(requests, td.sent<TdApi.GetChatHistory>().size)
        assertTrue(session.history.value.reachedEnd)
    }

    @Test
    fun `a new message comes first`() = runTest {
        val session = open()
        td.emit(TdApi.UpdateNewMessage(message(40)))
        runCurrent()
        assertEquals(40L, session.ids.first())
    }

    @Test
    fun `messages of other chats are ignored`() = runTest {
        val session = open()
        td.emit(TdApi.UpdateNewMessage(message(40, chatId = 999)))
        runCurrent()
        assertEquals(listOf(30L, 20L, 10L), session.ids)
    }

    @Test
    fun `a sent message replaces its temporary id`() = runTest {
        val session = open()
        td.emit(TdApi.UpdateNewMessage(message(1_000_000, outgoing = true, from = 1, sending = TdApi.MessageSendingStatePending())))
        runCurrent()
        td.emit(TdApi.UpdateMessageSendSucceeded(message(41, outgoing = true, from = 1), 1_000_000))
        runCurrent()
        assertEquals(listOf(41L, 30L, 20L, 10L), session.ids)
    }

    @Test
    fun `a failed message stays, marked failed`() = runTest {
        val session = open()
        val failed = message(1_000_001, outgoing = true, from = 1, sending = TdApi.MessageSendingStateFailed().apply { error = error() })
        td.emit(TdApi.UpdateMessageSendFailed(failed, 1_000_001, error()))
        runCurrent()
        val first = session.history.value.messages.first()
        assertTrue(first.sendingState is TdApi.MessageSendingStateFailed)
    }

    @Test
    fun `only permanent deletions remove messages`() = runTest {
        val session = open()
        td.emit(TdApi.UpdateDeleteMessages(CHAT_ID, longArrayOf(10), false, true))
        runCurrent()
        assertEquals(listOf(30L, 20L, 10L), session.ids)
        td.emit(TdApi.UpdateDeleteMessages(CHAT_ID, longArrayOf(10), true, false))
        runCurrent()
        assertEquals(listOf(30L, 20L), session.ids)
    }

    @Test
    fun `the read mark follows the other side`() = runTest {
        val session = open()
        td.emit(TdApi.UpdateChatReadOutbox(CHAT_ID, 41))
        runCurrent()
        assertEquals(41L, session.history.value.lastReadOutboxMessageId)
    }

    @Test
    fun `every update makes a new history, even for changes in place`() = runTest {
        val session = open()
        val before = session.history.value
        td.emit(TdApi.UpdateMessageEdited(CHAT_ID, 30, 123, null))
        runCurrent()
        assertTrue(session.history.value.version > before.version)
        assertEquals(123, session.history.value.messages.first().editDate)
    }

    @Test
    fun `a sender whose name can't be loaded doesn't stop the chat`() = runTest {
        td.on<TdApi.GetUser> { error(400, "USER_NOT_FOUND") }
        val session = open()
        assertEquals(listOf(30L, 20L, 10L), session.ids)
        assertFalse(2L in session.history.value.senderNames)
    }

    @Test
    fun `a new sender whose name can't be loaded doesn't stop updates`() = runTest {
        val session = open()
        td.on<TdApi.GetUser> { error(400, "USER_NOT_FOUND") }
        td.emit(TdApi.UpdateNewMessage(message(40, from = 9)))
        runCurrent()
        td.emit(TdApi.UpdateNewMessage(message(50, from = 9)))
        runCurrent()
        assertEquals(listOf(50L, 40L), session.ids.take(2))
    }

    @Test
    fun `a replied message outside the loaded history is fetched`() = runTest {
        history = listOf(message(30, replyTo = 5))
        td.on<TdApi.GetRepliedMessage> { message(5) }
        val session = open()
        assertTrue(5L in session.history.value.repliedMessages)
    }

    @Test
    fun `a failed first load leaves an empty chat instead of crashing`() = runTest {
        td.on<TdApi.GetChat> { error(400, "CHAT_NOT_FOUND") }
        val session = open()
        assertTrue(session.ids.isEmpty())
    }

    @Test
    fun `forwarding keeps the chat order`() = runTest {
        td.on<TdApi.ForwardMessages> { messages() }
        val session = open()
        session.forward(listOf(30, 10, 20), toChatId = 7)
        val request = td.sent<TdApi.ForwardMessages>().single()
        assertEquals(listOf(10L, 20L, 30L), request.messageIds.toList())
        assertEquals(7L, request.chatId)
        assertEquals(CHAT_ID, request.fromChatId)
    }

    @Test
    fun `deleting for everyone asks TDLib to revoke`() = runTest {
        td.on<TdApi.DeleteMessages> { TdApi.Ok() }
        val session = open()
        session.delete(listOf(20), forEveryone = true)
        assertTrue(td.sent<TdApi.DeleteMessages>().single().revoke)
    }

    @Test
    fun `albums are sent ten at a time`() = runTest {
        td.on<TdApi.SendMessageAlbum> { messages() }
        td.on<TdApi.SendMessage> { message(1) }
        val session = open()
        session.sendMedia(List(11) { TdApi.InputMessageText() })
        assertEquals(1, td.sent<TdApi.SendMessageAlbum>().size)
        assertEquals(10, td.sent<TdApi.SendMessageAlbum>().single().inputMessageContents.size)
        assertEquals(1, td.sent<TdApi.SendMessage>().size)
    }
}
