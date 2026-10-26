package dev.otmgram.data

import dev.otmgram.testing.FakeTelegram
import dev.otmgram.testing.chat
import dev.otmgram.testing.user
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleTest {

    private val td = FakeTelegram().apply {
        on<TdApi.GetUser> { if (it.userId == 1L) user(1, "Anna", "Smith") else user(it.userId, "Tom") }
        on<TdApi.GetChat> { chat(it.chatId, "band") }
    }

    @Test
    fun `users by full or first name, chats by title`() = runTest {
        assertEquals("Anna Smith", td.senderName(TdApi.MessageSenderUser(1)))
        assertEquals("Anna", td.senderName(TdApi.MessageSenderUser(1), firstOnly = true))
        assertEquals("Tom", td.senderName(TdApi.MessageSenderUser(2)))
        assertEquals("band", td.senderName(TdApi.MessageSenderChat(-5), firstOnly = true))
    }

    @Test
    fun `a name is asked each time, so a new one shows`() = runTest {
        assertEquals("Tom", td.senderName(TdApi.MessageSenderUser(2)))
        td.on<TdApi.GetUser> { user(it.userId, "Thomas") }
        assertEquals("Thomas", td.senderName(TdApi.MessageSenderUser(2)))
    }

    @Test
    fun `an empty first name leaves no space`() {
        assertEquals("Smith", user(1, "", "Smith").fullName())
    }
}
