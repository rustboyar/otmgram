package dev.otmgram.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.otmgram.data.AppSettings
import dev.otmgram.data.FolderRepository
import dev.otmgram.testing.FakeTelegram
import dev.otmgram.testing.TestStrings
import dev.otmgram.testing.chat
import dev.otmgram.testing.message
import dev.otmgram.testing.text
import dev.otmgram.testing.user
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What gets shown from TDLib's notifications, and when not. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationCenterTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val td = FakeTelegram()
    private val anna = 10L
    private val band = 20L
    private val elsewhere = 30L
    private val group = TdApi.ChatTypeBasicGroup(5)

    /** Newest message read in each chat. */
    private val lastRead = mutableMapOf(anna to 0L, band to 0L, elsewhere to 0L)

    private class Shown(val chat: ChatNotification, val alert: Boolean)

    private val shows = mutableListOf<Shown>()
    private val showing = mutableMapOf<Long, ChatNotification>()
    private val display = object : NotificationDisplay {
        override fun show(chat: ChatNotification, alert: Boolean) {
            shows += Shown(chat, alert)
            showing[chat.chatId] = chat
        }

        override fun cancel(chatId: Long) {
            showing.remove(chatId)
        }

        override fun cancelAll() = showing.clear()

        override fun shown() = showing.mapValues { it.value.messages.last().id }
    }

    private lateinit var center: NotificationCenter
    private lateinit var state: NotificationState
    private lateinit var settings: AppSettings

    private suspend fun TestScope.start(folderId: Int? = 1) {
        td.on<TdApi.GetChats> { TdApi.Chats(2, longArrayOf(anna, band)) }
        td.on<TdApi.GetChat> { request ->
            when (request.chatId) {
                anna -> chat(anna, "Anna")
                band -> chat(band, "band", type = group)
                else -> chat(elsewhere, "Elsewhere")
            }.apply {
                lastReadInboxMessageId = lastRead.getValue(request.chatId)
                unreadCount = 1
            }
        }
        td.on<TdApi.GetUser> { user(it.userId, "Tom") }
        val settingsStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { dir.newFile("settings.preferences_pb").also { it.delete() } }
        settings = AppSettings(settingsStore)
        val folders = FolderRepository(settingsStore, td, backgroundScope)
        folderId?.let { folders.selectFolder(it) }
        state = NotificationState(PreferenceDataStoreFactory.create(scope = backgroundScope) { dir.newFile("notifications.preferences_pb").also { it.delete() } })
        center = NotificationCenter(td, folders, state, settings, TestStrings, display, backgroundScope)
        center.start()
        runCurrent()
    }

    private fun notification(id: Int, messageId: Long, chatId: Long, text: String = "message $messageId", silent: Boolean = false, preview: Boolean = true) =
        TdApi.Notification(id, 0, silent, TdApi.NotificationTypeNewMessage(message(messageId, content = text(text), chatId = chatId, from = 3), preview))

    private suspend fun TestScope.added(
        groupId: Int,
        chatId: Long,
        vararg notifications: TdApi.Notification,
        type: TdApi.NotificationGroupType = TdApi.NotificationGroupTypeMessages(),
    ) {
        td.emit(TdApi.UpdateNotificationGroup(groupId, type, chatId, chatId, 0, notifications.size, notifications, IntArray(0)))
        runCurrent()
    }

    private suspend fun TestScope.removed(groupId: Int, chatId: Long, vararg ids: Int) {
        td.emit(TdApi.UpdateNotificationGroup(groupId, TdApi.NotificationGroupTypeMessages(), chatId, chatId, 0, 0, emptyArray(), ids))
        runCurrent()
    }

    @Test
    fun `turns TDLib's notifications on once logged in`() = runTest {
        start()
        td.emit(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
        runCurrent()
        val options = td.sent<TdApi.SetOption>().associate { it.name to (it.value as TdApi.OptionValueInteger).value }
        assertEquals(25L, options["notification_group_count_max"])
    }

    @Test
    fun `a new message in a folder chat shows with its sender, and alerts`() = runTest {
        start()
        added(1, band, notification(1, 5, band, "see you at 7"))
        val shown = shows.single()
        assertTrue(shown.alert)
        assertEquals("band", shown.chat.title)
        assertEquals(listOf(NotifiedMessage(5, "Tom", "see you at 7", 0)), shown.chat.messages)
    }

    @Test
    fun `chats outside the folder don't show`() = runTest {
        start()
        added(1, elsewhere, notification(1, 5, elsewhere))
        assertTrue(shows.isEmpty())
    }

    @Test
    fun `each new message alerts, a change to one doesn't`() = runTest {
        start()
        added(1, anna, notification(1, 5, anna))
        added(1, anna, notification(2, 6, anna))
        td.emit(TdApi.UpdateNotification(1, notification(2, 6, anna, "edited")))
        runCurrent()
        assertEquals(listOf(true, true, false), shows.map { it.alert })
        assertEquals(listOf("message 5", "edited"), shows.last().chat.messages.map { it.text })
    }

    @Test
    fun `a silent message shows without alerting`() = runTest {
        start()
        added(1, anna, notification(1, 5, anna, silent = true))
        assertFalse(shows.single().alert)
    }

    @Test
    fun `notifications that alerted before the app started show again, quietly`() = runTest {
        start()
        state.setAlertedUpTo(1)
        val group = TdApi.NotificationGroup(1, TdApi.NotificationGroupTypeMessages(), anna, 1, arrayOf(notification(1, 5, anna)))
        td.emit(TdApi.UpdateActiveNotifications(arrayOf(group)))
        runCurrent()
        assertFalse(shows.single().alert)
    }

    @Test
    fun `a message TDLib got before showing it still alerts once`() = runTest {
        start()
        state.setAlertedUpTo(1)
        val group = TdApi.NotificationGroup(1, TdApi.NotificationGroupTypeMessages(), anna, 2, arrayOf(notification(1, 5, anna), notification(2, 6, anna)))
        td.emit(TdApi.UpdateActiveNotifications(arrayOf(group)))
        runCurrent()
        center.setForeground(true)
        center.setForeground(false)
        td.emit(TdApi.UpdateActiveNotifications(arrayOf(group)))
        runCurrent()
        assertEquals(listOf(true, false), shows.map { it.alert })
    }

    @Test
    fun `mentions and other messages of a chat make one notification`() = runTest {
        start()
        added(1, band, notification(1, 5, band))
        added(2, band, notification(2, 6, band), type = TdApi.NotificationGroupTypeMentions())
        assertEquals(listOf(5L, 6L), shows.last().chat.messages.map { it.id })
    }

    @Test
    fun `what was in the app while open doesn't show`() = runTest {
        start()
        state.markSeen(mapOf(anna to 5L))
        added(1, anna, notification(1, 5, anna))
        assertTrue(shows.isEmpty())
        added(1, anna, notification(2, 6, anna))
        assertEquals(listOf(6L), shows.single().chat.messages.map { it.id })
    }

    @Test
    fun `nothing shows while the app is open, and opening it clears them`() = runTest {
        start()
        added(1, anna, notification(1, 5, anna))
        center.setForeground(true)
        assertTrue(showing.isEmpty())
        added(1, band, notification(2, 6, band))
        assertEquals(1, shows.size)
    }

    @Test
    fun `read elsewhere, the notification goes`() = runTest {
        start()
        added(1, anna, notification(1, 5, anna))
        lastRead[anna] = 5
        removed(1, anna, 1)
        assertTrue(showing.isEmpty())
    }

    @Test
    fun `pushed out by other chats while unread, the notification stays`() = runTest {
        start()
        added(1, anna, notification(1, 5, anna))
        removed(1, anna, 1) // TDLib's limit on groups, with lastRead still 0.
        assertEquals(setOf(anna), showing.keys)
    }

    @Test
    fun `without the chat's preview in Telegram, no text`() = runTest {
        start()
        settings.setShowMessageText(true)
        added(1, anna, notification(1, 5, anna, preview = false))
        added(2, band, notification(2, 6, band))
        assertFalse(showing.getValue(anna).withText)
        assertTrue(showing.getValue(band).withText)
    }
}
