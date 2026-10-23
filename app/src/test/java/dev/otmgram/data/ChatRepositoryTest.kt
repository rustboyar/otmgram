package dev.otmgram.data

import dev.otmgram.testing.FakeTelegram
import dev.otmgram.testing.ME
import dev.otmgram.testing.chat
import dev.otmgram.testing.error
import dev.otmgram.testing.message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ChatRepositoryTest {

    private val td = FakeTelegram()
    private val group = TdApi.ChatTypeBasicGroup(5)
    private val chats = mapOf(
        10L to chat(10, "Anna"),
        ME to chat(ME, "Me"),
        20L to chat(20, "band", type = group, lastMessage = message(7, from = 3, chatId = 20)),
        30L to chat(30, "crew", type = group, muteFor = 3600),
    )

    @Before
    fun setUp() {
        td.on<TdApi.GetMe> { dev.otmgram.testing.user(ME, "Me") }
        td.on<TdApi.GetScopeNotificationSettings> { request ->
            // Groups are muted by default, private chats aren't.
            TdApi.ScopeNotificationSettings().apply { muteFor = if (request.scope is TdApi.NotificationSettingsScopeGroupChats) 3600 else 0 }
        }
        td.on<TdApi.GetChats> { TdApi.Chats(chats.size, chats.keys.toLongArray()) }
        td.on<TdApi.GetChat> { chats.getValue(it.chatId) }
        td.on<TdApi.GetUser> { dev.otmgram.testing.user(it.userId, "Tom") }
    }

    @Test
    fun `folder chats know saved messages, mutes and the last sender`() = runTest {
        val list = ChatRepository(td).folderChats(folderId = 1).first()
        assertEquals(listOf(10L, ME, 20L, 30L), list.map { it.chat.id })
        assertEquals(listOf(false, true, false, false), list.map { it.isSavedMessages })
        // The band follows the muted default for groups; crew is muted itself; Anna isn't.
        assertEquals(listOf(false, false, true, true), list.map { it.isMuted })
        assertEquals("Tom", list[2].lastSenderName)
    }

    @Test
    fun `a sender name that can't be loaded doesn't lose the list`() = runTest {
        td.on<TdApi.GetUser> { error(400, "USER_NOT_FOUND") }
        val list = ChatRepository(td).folderChats(folderId = 1).first()
        assertEquals(4, list.size)
        assertEquals(null, list[2].lastSenderName)
    }
}
