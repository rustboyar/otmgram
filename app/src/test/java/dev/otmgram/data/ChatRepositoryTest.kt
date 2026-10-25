package dev.otmgram.data

import dev.otmgram.testing.FakeTelegram
import dev.otmgram.testing.ME
import dev.otmgram.testing.chat
import dev.otmgram.testing.error
import dev.otmgram.testing.message
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryTest {

    private val td = FakeTelegram()
    private val group = TdApi.ChatTypeBasicGroup(5)
    private val chats = mutableMapOf(
        10L to chat(10, "Anna"),
        ME to chat(ME, "Me"),
        20L to chat(20, "band", type = group, lastMessage = message(7, from = 3, chatId = 20)),
        30L to chat(30, "crew", type = group, muteFor = 3600),
    )

    private var order = listOf(10L, ME, 20L, 30L)

    @Before
    fun setUp() {
        td.on<TdApi.GetMe> { dev.otmgram.testing.user(ME, "Me") }
        td.on<TdApi.GetScopeNotificationSettings> { request ->
            // Groups are muted by default, private chats aren't.
            TdApi.ScopeNotificationSettings().apply { muteFor = if (request.scope is TdApi.NotificationSettingsScopeGroupChats) 3600 else 0 }
        }
        td.on<TdApi.GetChats> { TdApi.Chats(order.size, order.toLongArray()) }
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
    fun `chats pinned in the folder are marked, not those pinned in another`() = runTest {
        chats[10L] = chat(10, "Anna", pinnedIn = 1)
        chats[20L] = chat(20, "band", type = group, pinnedIn = 2)
        val list = ChatRepository(td).folderChats(folderId = 1).first()
        assertEquals(listOf(true, false, false, false), list.map { it.isPinned })
    }

    @Test
    fun `pinning is the folder's`() = runTest {
        td.on<TdApi.ToggleChatIsPinned> { TdApi.Ok() }
        ChatRepository(td).setPinned(folderId = 1, chatId = 10, pinned = true)
        val request = td.sent<TdApi.ToggleChatIsPinned>().single()
        assertEquals(1, (request.chatList as TdApi.ChatListFolder).chatFolderId)
        assertEquals(10L, request.chatId)
        assertEquals(true, request.isPinned)
    }

    @Test
    fun `channels and secret chats are left out`() = runTest {
        chats[40L] = chat(40, "news", type = TdApi.ChatTypeSupergroup(6, true))
        chats[50L] = chat(50, "secret", type = TdApi.ChatTypeSecret(1, 10))
        chats[60L] = chat(60, "big group", type = TdApi.ChatTypeSupergroup(7, false))
        order = listOf(10L, 40L, 50L, 60L)
        val list = ChatRepository(td).folderChats(folderId = 1).first()
        assertEquals(listOf(10L, 60L), list.map { it.chat.id })
    }

    @Test
    fun `a sender name that can't be loaded doesn't lose the list`() = runTest {
        td.on<TdApi.GetUser> { error(400, "USER_NOT_FOUND") }
        val list = ChatRepository(td).folderChats(folderId = 1).first()
        assertEquals(4, list.size)
        assertEquals(null, list[2].lastSenderName)
    }

    @Test
    fun `an update reloads only its chat`() = runTest {
        val lists = mutableListOf<List<FolderChat>>()
        backgroundScope.launch { ChatRepository(td).folderChats(1).collect { lists += it } }
        runCurrent()
        val before = td.sent<TdApi.GetChat>().size
        chats[10L] = chat(10, "Anna", unread = 3)
        td.emit(TdApi.UpdateChatReadInbox(10, 0, 3))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(1, td.sent<TdApi.GetChat>().size - before)
        assertEquals(3, lists.last().first().chat.unreadCount)
        assertEquals(0, td.sent<TdApi.GetChats>().size - 1) // Order unchanged, not reloaded.
    }

    @Test
    fun `updates close together are handled at once`() = runTest {
        var lists = 0
        backgroundScope.launch { ChatRepository(td).folderChats(1).collect { lists++ } }
        runCurrent()
        repeat(5) { td.emit(TdApi.UpdateChatReadInbox(10, 0, it)) }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, lists)
    }

    @Test
    fun `new order, and chats leaving the folder`() = runTest {
        val lists = mutableListOf<List<FolderChat>>()
        backgroundScope.launch { ChatRepository(td).folderChats(1).collect { lists += it } }
        runCurrent()
        order = listOf(30L, 10L)
        td.emit(TdApi.UpdateChatPosition(30, TdApi.ChatPosition()))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(30L, 10L), lists.last().map { it.chat.id })
    }

    @Test
    fun `a folder change reloads everything`() = runTest {
        backgroundScope.launch { ChatRepository(td).folderChats(1).collect {} }
        runCurrent()
        val before = td.sent<TdApi.GetChat>().size
        td.emit(TdApi.UpdateChatFolders(emptyArray(), 0, false))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(4, td.sent<TdApi.GetChat>().size - before)
    }
}
