package dev.otmgram.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.otmgram.data.AppSettings
import dev.otmgram.data.ChatRepository
import dev.otmgram.testing.FakeTelegram
import dev.otmgram.testing.ME
import dev.otmgram.testing.TestStrings
import dev.otmgram.testing.chat
import dev.otmgram.testing.message
import dev.otmgram.testing.text
import dev.otmgram.testing.user
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What gets notified, and when not. */
class NewMessagesNotifierTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val td = FakeTelegram()
    private val anna = 10L
    private val band = 20L
    private val group = TdApi.ChatTypeBasicGroup(5)

    /** Each chat's history, newest first, and what's read in it. */
    private val history = mutableMapOf(anna to mutableListOf(message(1, chatId = anna)), band to mutableListOf(message(2, chatId = band)))
    private val lastRead = mutableMapOf(anna to 1L, band to 2L)
    private var bandMuted = false
    private var bandMentionsOff = false

    private val shown = mutableListOf<List<ChatNews>>()
    private var cancels = 0
    private val display = object : NotificationDisplay {
        override fun show(news: List<ChatNews>, withText: Boolean) {
            shown += news
        }

        override fun cancel() {
            cancels++
        }
    }

    private lateinit var notifier: NewMessagesNotifier

    @Before
    fun setUp() {
        td.on<TdApi.GetMe> { user(ME, "Me") }
        td.on<TdApi.GetScopeNotificationSettings> { TdApi.ScopeNotificationSettings() }
        td.on<TdApi.GetChats> { TdApi.Chats(2, longArrayOf(anna, band)) }
        td.on<TdApi.GetChat> { request ->
            val messages = history.getValue(request.chatId)
            val unread = messages.count { !it.isOutgoing && it.id > lastRead.getValue(request.chatId) }
            when (request.chatId) {
                anna -> chat(anna, "Anna", lastMessage = messages.first(), unread = unread)
                else -> chat(band, "band", type = group, lastMessage = messages.first(), unread = unread, muteFor = if (bandMuted) 3600 else 0).apply {
                    notificationSettings.disableMentionNotifications = bandMentionsOff
                }
            }.apply {
                lastReadInboxMessageId = lastRead.getValue(request.chatId)
                unreadMentionCount = messages.count { it.containsUnreadMention }
            }
        }
        td.on<TdApi.SearchChatMessages> { request ->
            val mentions = history.getValue(request.chatId).filter { it.containsUnreadMention }.take(request.limit)
            TdApi.FoundChatMessages(mentions.size, mentions.toTypedArray(), 0)
        }
        td.on<TdApi.GetChatHistory> { request ->
            val messages = history.getValue(request.chatId)
                .filter { request.fromMessageId == 0L || it.id <= request.fromMessageId }
                .take(request.limit)
            TdApi.Messages(messages.size, messages.toTypedArray())
        }
        td.on<TdApi.GetUser> { user(it.userId, "Tom") }
        val state = NotificationState(PreferenceDataStoreFactory.create { dir.newFile("notifications.preferences_pb").also { it.delete() } })
        val settings = AppSettings(PreferenceDataStoreFactory.create { dir.newFile("settings.preferences_pb").also { it.delete() } })
        notifier = NewMessagesNotifier(td, ChatRepository(td), state, settings, TestStrings, display)
    }

    private fun arrives(chatId: Long, id: Long, text: String = "message $id", outgoing: Boolean = false, mentionsMe: Boolean = false) {
        val message = message(id, content = text(text), chatId = chatId, outgoing = outgoing, from = if (outgoing) ME else 3)
        history.getValue(chatId).add(0, message.apply { containsUnreadMention = mentionsMe })
    }

    @Test
    fun `the first check only notes what's there`() = runTest {
        arrives(anna, 5)
        notifier.check(folderId = 1)
        assertTrue(shown.isEmpty())
        arrives(anna, 6, "see you at 7")
        notifier.check(folderId = 1)
        assertEquals(listOf("see you at 7"), shown.single().single().lines)
    }

    @Test
    fun `a message is notified once, and the notification stays`() = runTest {
        notifier.check(folderId = 1)
        arrives(anna, 5, "see you at 7")
        notifier.check(folderId = 1)
        notifier.check(folderId = 1)
        assertEquals(1, shown.size)
        assertEquals(0, cancels)
    }

    @Test
    fun `a message that arrives late is still new, whatever its date`() = runTest {
        notifier.check(folderId = 1)
        arrives(anna, 5, "first")
        notifier.check(folderId = 1)
        // Sent earlier, delivered after the last check.
        history.getValue(band).add(0, message(6, content = text("late"), chatId = band, from = 3, date = 0))
        notifier.check(folderId = 1)
        assertEquals(2, shown.size)
        assertEquals(listOf("Tom: late"), shown.last().single { it.chatId == band }.lines)
    }

    @Test
    fun `what was in the app while open isn't notified`() = runTest {
        notifier.check(folderId = 1)
        arrives(anna, 5)
        notifier.markSeen(folderId = 1)
        notifier.check(folderId = 1)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `read elsewhere, the notification goes`() = runTest {
        notifier.check(folderId = 1)
        arrives(anna, 5)
        notifier.check(folderId = 1)
        lastRead[anna] = 5
        notifier.check(folderId = 1)
        assertEquals(1, shown.size)
        assertTrue(cancels > 0)
    }

    @Test
    fun `muted chats and your own messages don't notify`() = runTest {
        bandMuted = true
        notifier.check(folderId = 1)
        arrives(band, 5)
        arrives(anna, 6, outgoing = true)
        notifier.check(folderId = 1)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `in a muted group, a mention or reply notifies, the rest doesn't`() = runTest {
        bandMuted = true
        notifier.check(folderId = 1)
        arrives(band, 5, "anyone?")
        arrives(band, 6, "@me look", mentionsMe = true)
        arrives(band, 7, "never mind")
        notifier.check(folderId = 1)
        assertEquals(listOf("Tom: @me look"), shown.single().single().lines)
    }

    @Test
    fun `with mention notifications off, a muted group stays quiet`() = runTest {
        bandMuted = true
        bandMentionsOff = true
        notifier.check(folderId = 1)
        arrives(band, 5, "@me look", mentionsMe = true)
        notifier.check(folderId = 1)
        assertTrue(shown.isEmpty())
    }
}
