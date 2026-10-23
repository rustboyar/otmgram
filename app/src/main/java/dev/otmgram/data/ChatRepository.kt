package dev.otmgram.data

import dev.otmgram.core.td.TdClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** A chat of the folder list, with what's needed to display it. */
data class FolderChat(
    val chat: TdApi.Chat,
    /** The chat with yourself. */
    val isSavedMessages: Boolean,
    /** In groups, who sent the last message (null for your own messages). */
    val lastSenderName: String?,
)

@Singleton
class ChatRepository @Inject constructor(
    private val td: TdClient,
) {
    /**
     * Chats of a folder in display order.
     *
     * Instead of maintaining the list from individual updates, it is reloaded from TDLib's
     * local database shortly after any update that can change it. Simple and always
     * consistent, and cheap for a folder of a few dozen chats.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun folderChats(folderId: Int): Flow<List<FolderChat>> = td.updates
        .filter { update -> CHAT_LIST_UPDATES.any { it.isInstance(update) } }
        .map { }
        .onStart { emit(Unit) }
        .debounce(RELOAD_DELAY_MS)
        .mapLatest {
            val myId = td.send(TdApi.GetMe()).id
            val ids = td.send(TdApi.GetChats(TdApi.ChatListFolder(folderId), MAX_CHATS)).chatIds
            ids.map { id ->
                val chat = td.send(TdApi.GetChat(id))
                FolderChat(
                    chat = chat,
                    isSavedMessages = (chat.type as? TdApi.ChatTypePrivate)?.userId == myId,
                    lastSenderName = lastSenderName(chat),
                )
            }
        }

    private suspend fun lastSenderName(chat: TdApi.Chat): String? {
        val message = chat.lastMessage ?: return null
        val isGroup = when (val type = chat.type) {
            is TdApi.ChatTypeBasicGroup -> true
            is TdApi.ChatTypeSupergroup -> !type.isChannel
            else -> false
        }
        if (!isGroup || message.isOutgoing) return null
        return when (val sender = message.senderId) {
            is TdApi.MessageSenderUser -> td.send(TdApi.GetUser(sender.userId)).firstName
            is TdApi.MessageSenderChat -> td.send(TdApi.GetChat(sender.chatId)).title
            else -> null
        }
    }

    private companion object {
        const val MAX_CHATS = 500
        const val RELOAD_DELAY_MS = 200L

        val CHAT_LIST_UPDATES = listOf(
            TdApi.UpdateNewChat::class,
            TdApi.UpdateChatPosition::class,
            TdApi.UpdateChatLastMessage::class,
            TdApi.UpdateChatDraftMessage::class,
            TdApi.UpdateChatTitle::class,
            TdApi.UpdateChatPhoto::class,
            TdApi.UpdateChatReadInbox::class,
            TdApi.UpdateChatReadOutbox::class,
            TdApi.UpdateChatIsMarkedAsUnread::class,
            TdApi.UpdateChatUnreadMentionCount::class,
            TdApi.UpdateChatNotificationSettings::class,
            TdApi.UpdateChatFolders::class,
        )
    }
}
