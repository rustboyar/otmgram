package dev.otmgram.data

import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Basic groups and supergroups, but not channels. */
val TdApi.Chat.isGroup: Boolean
    get() = when (val type = type) {
        is TdApi.ChatTypeBasicGroup -> true
        is TdApi.ChatTypeSupergroup -> !type.isChannel
        else -> false
    }

/** The chat with yourself. */
fun TdApi.Chat.isSavedMessages(myUserId: Long): Boolean = (type as? TdApi.ChatTypePrivate)?.userId == myUserId

/** A chat of the folder list, with what's needed to display it. */
data class FolderChat(
    val chat: TdApi.Chat,
    /** The chat with yourself. */
    val isSavedMessages: Boolean,
    /** In groups, who sent the last message (null for your own messages). */
    val lastSenderName: String?,
    /** Notifications are off, for this chat or by the default for its kind. */
    val isMuted: Boolean = false,
)

@Singleton
class ChatRepository @Inject constructor(
    private val td: TelegramClient,
) {
    /**
     * Chats of a folder in display order, kept up to date.
     *
     * Loaded once, then each update reloads only the chat it's about (and the order, when
     * positions change); updates arriving close together are handled at once. Only a change
     * of folders or of the default mute settings reloads everything.
     */
    fun folderChats(folderId: Int): Flow<List<FolderChat>> = channelFlow {
        val changes = Channel<Change>(Channel.UNLIMITED)
        launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update -> changesOf(update).forEach { changes.send(it) } }
        }
        val folder = FolderList(folderId)
        send(folder.loadAll())
        while (true) {
            val batch = mutableSetOf(changes.receive())
            delay(RELOAD_DELAY_MS)
            while (true) batch += changes.tryReceive().getOrNull() ?: break
            send(folder.apply(batch))
        }
    }

    /** What an update means for a folder list. */
    private sealed interface Change {
        data class Chat(val id: Long) : Change
        data object Order : Change
        data object All : Change
    }

    private fun changesOf(update: TdApi.Update): List<Change> = when (update) {
        is TdApi.UpdateNewChat -> listOf(Change.Chat(update.chat.id), Change.Order)
        is TdApi.UpdateChatPosition -> listOf(Change.Chat(update.chatId), Change.Order)
        is TdApi.UpdateChatLastMessage -> listOf(Change.Chat(update.chatId), Change.Order)
        is TdApi.UpdateChatDraftMessage -> listOf(Change.Chat(update.chatId), Change.Order)
        is TdApi.UpdateChatTitle -> listOf(Change.Chat(update.chatId))
        is TdApi.UpdateChatPhoto -> listOf(Change.Chat(update.chatId))
        is TdApi.UpdateChatReadInbox -> listOf(Change.Chat(update.chatId))
        is TdApi.UpdateChatReadOutbox -> listOf(Change.Chat(update.chatId))
        is TdApi.UpdateChatIsMarkedAsUnread -> listOf(Change.Chat(update.chatId))
        is TdApi.UpdateChatUnreadMentionCount -> listOf(Change.Chat(update.chatId))
        is TdApi.UpdateChatNotificationSettings -> listOf(Change.Chat(update.chatId))
        is TdApi.UpdateChatFolders, is TdApi.UpdateScopeNotificationSettings -> listOf(Change.All)
        else -> emptyList()
    }

    /** One folder's chats as last loaded; used by a single collector at a time. */
    private inner class FolderList(private val folderId: Int) {
        private var myId = 0L
        private var mutedByDefault = mapOf(false to false, true to false)
        private var order = emptyList<Long>()
        private val chats = mutableMapOf<Long, FolderChat>()
        private val firstNames = mutableMapOf<Long, String>()

        suspend fun loadAll(): List<FolderChat> {
            myId = td.send(TdApi.GetMe()).id
            mutedByDefault = mapOf(
                false to (td.send(TdApi.GetScopeNotificationSettings(TdApi.NotificationSettingsScopePrivateChats())).muteFor > 0),
                true to (td.send(TdApi.GetScopeNotificationSettings(TdApi.NotificationSettingsScopeGroupChats())).muteFor > 0),
            )
            order = loadOrder()
            chats.clear()
            order.forEach { chats[it] = load(it) }
            return list()
        }

        suspend fun apply(changes: Set<Change>): List<FolderChat> {
            if (Change.All in changes) return loadAll()
            if (Change.Order in changes) order = loadOrder()
            val changed = changes.filterIsInstance<Change.Chat>().map { it.id }.toSet()
            // Changed chats of the folder, and chats that just joined it.
            order.filter { it in changed || it !in chats }.forEach { chats[it] = load(it) }
            chats.keys.retainAll(order.toSet())
            return list()
        }

        private fun list() = order.mapNotNull(chats::get)

        private suspend fun loadOrder() = td.send(TdApi.GetChats(TdApi.ChatListFolder(folderId), MAX_CHATS)).chatIds.toList()

        private suspend fun load(id: Long): FolderChat {
            val chat = td.send(TdApi.GetChat(id))
            return FolderChat(
                chat = chat,
                isSavedMessages = chat.isSavedMessages(myId),
                lastSenderName = lastSenderName(chat),
                isMuted = chat.isMuted(mutedByDefault.getValue(chat.isGroup)),
            )
        }

        private suspend fun lastSenderName(chat: TdApi.Chat): String? {
            val message = chat.lastMessage ?: return null
            if (!chat.isGroup || message.isOutgoing) return null
            return try {
                when (val sender = message.senderId) {
                    is TdApi.MessageSenderUser ->
                        firstNames[sender.userId] ?: td.send(TdApi.GetUser(sender.userId)).firstName.also { firstNames[sender.userId] = it }
                    is TdApi.MessageSenderChat -> td.send(TdApi.GetChat(sender.chatId)).title
                    else -> null
                }
            } catch (e: TdException) {
                null
            }
        }
    }

    private fun TdApi.Chat.isMuted(mutedByDefault: Boolean): Boolean =
        if (notificationSettings.useDefaultMuteFor) mutedByDefault else notificationSettings.muteFor > 0

    private companion object {
        const val MAX_CHATS = 500
        const val RELOAD_DELAY_MS = 200L
    }
}
