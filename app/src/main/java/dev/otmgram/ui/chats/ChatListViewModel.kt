package dev.otmgram.ui.chats

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.td.TdException
import dev.otmgram.data.AuthRepository
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.FolderChat
import dev.otmgram.data.FolderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

data class ChatItem(
    val id: Long,
    val title: String,
    val preview: String,
    /** Unix time of the last message, or null if the chat has none. */
    val date: Int?,
    val unreadCount: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatListViewModel @Inject constructor(
    folders: FolderRepository,
    chats: ChatRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    val folderName: StateFlow<String> = combine(folders.folders, folders.selectedFolderId) { all, id ->
        all?.find { it.id == id }?.name.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _query = MutableStateFlow("")

    /** Search text; filters chats by title. */
    val query: StateFlow<String> = _query.asStateFlow()

    /** Chats of the folder matching [query], or null while loading. */
    val chats: StateFlow<List<ChatItem>?> = combine(
        folders.selectedFolderId.filterNotNull().flatMapLatest(chats::folderChats),
        _query,
    ) { all, query ->
        all.map { it.toItem() }.filter { it.title.contains(query.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(query: String) {
        _query.value = query
    }

    fun logOut() {
        viewModelScope.launch {
            try {
                auth.logOut()
            } catch (e: TdException) {
                Log.w("ChatListViewModel", "LogOut failed", e)
            }
        }
    }
}

private fun FolderChat.toItem() = ChatItem(
    id = chat.id,
    title = if (isSavedMessages) "Saved Messages" else chat.title,
    preview = chat.lastMessage?.let { message ->
        val text = message.preview()
        when {
            isSavedMessages -> text
            message.isOutgoing -> "You: $text"
            lastSenderName != null -> "$lastSenderName: $text"
            else -> text
        }
    }.orEmpty(),
    date = chat.lastMessage?.date,
    unreadCount = chat.unreadCount,
)

private fun TdApi.Message.preview(): String =
    when (val content = content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> withCaption("Photo", content.caption)
        is TdApi.MessageVideo -> withCaption("Video", content.caption)
        is TdApi.MessageVoiceNote -> withCaption("Voice message", content.caption)
        is TdApi.MessageVideoNote -> "Video message"
        is TdApi.MessageSticker -> "${content.sticker.emoji} Sticker".trim()
        is TdApi.MessageAnimation -> withCaption("GIF", content.caption)
        is TdApi.MessageDocument -> withCaption(content.document.fileName.ifEmpty { "File" }, content.caption)
        is TdApi.MessageAudio -> withCaption("Audio", content.caption)
        is TdApi.MessageCall -> "Call"
        is TdApi.MessageBasicGroupChatCreate, is TdApi.MessageSupergroupChatCreate -> "Group created"
        is TdApi.MessageChatAddMembers, is TdApi.MessageChatJoinByLink -> "Joined the group"
        is TdApi.MessageChatDeleteMember -> "Left the group"
        is TdApi.MessageChatChangeTitle -> "Group renamed to \"${content.title}\""
        is TdApi.MessageChatChangePhoto -> "Group photo changed"
        is TdApi.MessagePinMessage -> "Pinned a message"
        is TdApi.MessageContactRegistered -> "Joined Telegram"
        else -> "Message"
    }

private fun withCaption(kind: String, caption: TdApi.FormattedText) =
    if (caption.text.isEmpty()) kind else "$kind, ${caption.text}"
