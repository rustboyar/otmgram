package dev.otmgram.ui.chat

import android.annotation.SuppressLint
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.launchLogged
import dev.otmgram.core.location.LiveLocationSharing
import dev.otmgram.core.location.LiveShare
import dev.otmgram.core.location.LocationSource
import dev.otmgram.core.media.AudioPlayer
import dev.otmgram.core.media.AudioState
import dev.otmgram.core.media.RecorderState
import dev.otmgram.core.media.VoiceRecorder
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.core.userMessage
import dev.otmgram.data.AppSettings
import dev.otmgram.data.ChatHistory
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.DownloadsFolder
import dev.otmgram.data.FileRepository
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.MessageRepository
import dev.otmgram.data.OutgoingMedia
import dev.otmgram.data.SizeEstimate
import dev.otmgram.data.StickerRepository
import dev.otmgram.data.StickerTab
import dev.otmgram.data.isGroup
import dev.otmgram.data.isSavedMessages
import dev.otmgram.ui.isService
import dev.otmgram.ui.summary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject

data class ChatHeader(
    val title: String,
    val isGroup: Boolean,
    /** "3 members", "last seen recently"; null if there's nothing to say. */
    val subtitle: String?,
    val photoFileId: Int?,
    val minithumbnail: TdApi.Minithumbnail?,
    val isSavedMessages: Boolean,
)

/** Where the unread messages began when the chat was opened. */
data class UnreadMark(val afterMessageId: Long, val count: Int)

enum class MessageStatus { Sending, Sent, Read, Failed }

data class ReplyPreview(val senderName: String?, val text: String)

/** A reaction under a message. [emoji] is null for kinds Otmgram can't add (custom emoji, paid). */
data class ReactionChip(val label: String, val emoji: String?, val count: Int, val isChosen: Boolean)

data class SearchResult(val messageId: Long, val senderName: String?, val text: String, val date: Int)

data class MessageItem(
    val id: Long,
    val isOutgoing: Boolean,
    /** An event like "group created" rather than a message. */
    val isService: Boolean,
    /** Shown above the first of consecutive incoming group messages from the same sender. */
    val senderName: String?,
    val forwardedFrom: String?,
    val replyTo: ReplyPreview?,
    /** Message text, or the caption of [media]. */
    val text: String,
    val media: MessageMedia?,
    /** True if [text] describes content that can't be shown, like a document. */
    val isMediaPlaceholder: Boolean,
    /** Unix time. */
    val date: Int,
    val isEdited: Boolean,
    /** Null for incoming messages. */
    val status: MessageStatus?,
    val reactions: List<ReactionChip>,
)

/** What the input bar does with the next send. */
sealed interface Composer {
    data object New : Composer
    data class Reply(val message: MessageItem) : Composer
    data class Edit(val message: MessageItem) : Composer
}

/** Long-press menu of a message, with the actions it allows. */
data class MessageMenu(
    val message: MessageItem,
    /** Emoji reactions that can be added. */
    val reactions: List<String>,
    val canReply: Boolean,
    val canForward: Boolean,
    val canEdit: Boolean,
    val canDeleteForSelf: Boolean,
    val canDeleteForEveryone: Boolean,
    /** Files can be saved to the device's Downloads folder. */
    val canSave: Boolean,
    val canCopy: Boolean,
)

/** Messages waiting for a delete confirmation, with what the user may choose. */
data class DeleteRequest(
    val messageIds: List<Long>,
    /** Text of a single message, quoted in the dialog. */
    val quote: String?,
    val canDeleteForSelf: Boolean,
    val canDeleteForEveryone: Boolean,
)

data class ForwardTarget(
    val chatId: Long,
    val title: String,
    val photoFileId: Int?,
    val minithumbnail: TdApi.Minithumbnail?,
    val isSavedMessages: Boolean,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    messages: MessageRepository,
    private val chats: ChatRepository,
    private val folders: FolderRepository,
    private val stickerRepository: StickerRepository,
    private val outgoingMedia: OutgoingMedia,
    private val voiceRecorder: VoiceRecorder,
    private val locationSource: LocationSource,
    private val liveLocation: LiveLocationSharing,
    private val files: FileRepository,
    private val downloads: DownloadsFolder,
    private val td: TdClient,
    private val audioPlayer: AudioPlayer,
    appSettings: AppSettings,
) : ViewModel() {

    val chatId: Long = checkNotNull(savedState["chatId"])
    private val session = messages.openChat(chatId, viewModelScope)

    private val _header = MutableStateFlow<ChatHeader?>(null)
    val header: StateFlow<ChatHeader?> = _header.asStateFlow()

    /** Messages, newest first. */
    val messages: StateFlow<List<MessageItem>> = combine(session.history, _header) { history, header ->
        history.toItems(isGroup = header?.isGroup == true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Live location being shared from this device, in any chat. */
    val liveShare: StateFlow<LiveShare?> = liveLocation.share

    /** Voice message being recorded, or null. */
    val recording: StateFlow<RecorderState?> = voiceRecorder.state

    /** Whether "Compress" starts on in the send preview. */
    val compressByDefault: StateFlow<Boolean> = appSettings.compressByDefault.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Voice message being played, anywhere in the app. */
    val audio: StateFlow<AudioState?> = audioPlayer.state

    var composer by mutableStateOf<Composer>(Composer.New)
        private set

    var menu by mutableStateOf<MessageMenu?>(null)
        private set

    /** Messages waiting for a delete confirmation. */
    var deleting by mutableStateOf<DeleteRequest?>(null)
        private set

    /** Messages to forward, with the folder chats they can go to. */
    var forwarding by mutableStateOf<Pair<List<Long>, List<ForwardTarget>>?>(null)
        private set

    /** Selected messages; selection mode is on while it isn't empty. */
    var selection by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** Unread messages when the chat was opened, for the "new since you left" line. */
    var unreadMark by mutableStateOf<UnreadMark?>(null)
        private set

    /** One-time message: an error or a confirmation. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Search text, or null when not searching. */
    var searchQuery by mutableStateOf<String?>(null)
        private set

    /** Results for [searchQuery], newest first, or null while searching or not searching. */
    var searchResults by mutableStateOf<List<SearchResult>?>(null)
        private set

    /** Index in [searchResults] of the match shown. */
    var searchIndex by mutableStateOf(0)
        private set

    /** Message to scroll to, consumed by the screen. */
    var scrollTarget by mutableStateOf<Long?>(null)
        private set

    private val searchQueries = MutableStateFlow("")

    /** Tabs of the sticker picker, or null while it's closed. */
    var stickerTabs by mutableStateOf<List<StickerTab>?>(null)
        private set

    var selectedStickerTab by mutableStateOf<StickerTab?>(null)
        private set

    /** Stickers of [selectedStickerTab], or null while loading. */
    var stickers by mutableStateOf<List<TdApi.Sticker>?>(null)
        private set

    private var stickersJob: Job? = null

    private val viewedIds = mutableSetOf<Long>()

    init {
        action {
            val chat = td.send(TdApi.GetChat(chatId))
            val myId = td.send(TdApi.GetMe()).id
            val saved = chat.isSavedMessages(myId)
            if (chat.unreadCount > 0) unreadMark = UnreadMark(chat.lastReadInboxMessageId, chat.unreadCount)
            _header.value = ChatHeader(
                title = if (saved) "Saved Messages" else chat.title,
                isGroup = chat.isGroup,
                subtitle = if (saved) null else subtitle(chat),
                photoFileId = chat.photo?.small?.id,
                minithumbnail = chat.photo?.minithumbnail,
                isSavedMessages = saved,
            )
        }
        viewModelScope.launch {
            searchQueries.collectLatest { query ->
                searchResults = null
                if (query.isBlank()) return@collectLatest
                delay(SEARCH_DELAY_MS)
                try {
                    val names = session.history.value.senderNames
                    val results = session.search(query.trim()).map {
                        SearchResult(it.id, names[it.senderKey()], it.summary(), it.date)
                    }
                    searchResults = results
                    searchIndex = 0
                    results.firstOrNull()?.let { show(it.messageId) }
                } catch (e: TdException) {
                    notice = e.error.message
                }
            }
        }
    }

    fun loadOlder() = action { session.loadOlder() }

    fun send(text: String) {
        val mode = composer
        // A caption may be removed by saving it empty; text messages can't be empty.
        val removingCaption = mode is Composer.Edit && mode.message.media != null
        if (text.isBlank() && !removingCaption) return
        composer = Composer.New
        action {
            when (mode) {
                Composer.New -> session.sendText(text.trim())
                is Composer.Reply -> session.sendText(text.trim(), replyToMessageId = mode.message.id)
                is Composer.Edit -> if (mode.message.media != null) {
                    session.editCaption(mode.message.id, text.trim())
                } else {
                    session.editText(mode.message.id, text.trim())
                }
            }
        }
    }

    /** Called with the ids of messages currently on screen. */
    fun onMessagesVisible(ids: List<Long>) {
        val new = ids.filter { viewedIds.add(it) }
        if (new.isNotEmpty()) action { session.markViewed(new) }
    }

    /** "3 members", "online", "last seen recently". */
    private suspend fun subtitle(chat: TdApi.Chat): String? = when (val type = chat.type) {
        is TdApi.ChatTypeBasicGroup -> members(td.send(TdApi.GetBasicGroup(type.basicGroupId)).memberCount)
        is TdApi.ChatTypeSupergroup -> members(td.send(TdApi.GetSupergroup(type.supergroupId)).memberCount)
        is TdApi.ChatTypePrivate -> {
            val user = td.send(TdApi.GetUser(type.userId))
            if (user.type is TdApi.UserTypeBot) "bot" else lastSeen(user.status)
        }
        else -> null
    }

    private fun members(count: Int) = if (count <= 0) null else if (count == 1) "1 member" else "$count members"

    fun openMenu(message: MessageItem) {
        if (message.isService) return
        if (selection.isNotEmpty()) {
            toggleSelected(message)
            return
        }
        action {
            val properties = session.properties(message.id)
            val reactions = try {
                session.availableReactions(message.id)
            } catch (e: TdException) {
                emptyList() // Reactions disabled in this chat.
            }
            menu = MessageMenu(
                message = message,
                reactions = reactions,
                canReply = properties.canBeReplied,
                canForward = properties.canBeForwarded,
                canEdit = properties.canBeEdited && message.hasEditableText,
                canDeleteForSelf = properties.canBeDeletedOnlyForSelf,
                canDeleteForEveryone = properties.canBeDeletedForAllUsers,
                canSave = message.media is MessageMedia.Document,
                canCopy = message.text.isNotEmpty() && !message.isMediaPlaceholder,
            )
        }
    }

    fun playVoice(message: MessageItem, fileId: Int, path: String) {
        audioPlayer.toggle(fileId, path)
        markContentOpened(message)
    }

    /** Marks voice and video messages as listened/viewed. */
    fun markContentOpened(message: MessageItem) {
        action { td.send(TdApi.OpenMessageContent(chatId, message.id)) }
    }

    fun closeMenu() {
        menu = null
    }

    fun saveToDownloads(message: MessageItem) {
        menu = null
        val document = message.media as? MessageMedia.Document ?: return
        action {
            notice = "Downloading ${document.name}…"
            val file = files.downloadNow(document.fileId)
            downloads.save(File(file.local.path), document.name, document.mimeType)
            notice = "Saved to Downloads"
        }
    }

    /** Files picked to send, waiting for a caption. */
    var pendingFiles by mutableStateOf<List<Uri>>(emptyList())
        private set

    fun pickedFiles(uris: List<Uri>) {
        pendingFiles = uris
    }

    /** Sends the picked files as they are; several go as an album, captioned on the first. */
    fun sendPendingFiles(caption: String) {
        val uris = pendingFiles
        pendingFiles = emptyList()
        action {
            val contents = uris.mapIndexed { index, uri ->
                outgoingMedia.document(uri, caption = if (index == 0) caption else "")
            }
            session.sendMedia(contents)
        }
    }

    fun cancelPendingFiles() {
        pendingFiles = emptyList()
    }

    fun react(message: MessageItem, emoji: String) {
        menu = null
        val chosen = message.reactions.any { it.emoji == emoji && it.isChosen }
        action { session.setReaction(message.id, emoji, chosen = !chosen) }
    }

    fun toggleReaction(message: MessageItem, chip: ReactionChip) {
        val emoji = chip.emoji ?: return
        action { session.setReaction(message.id, emoji, chosen = !chip.isChosen) }
    }

    fun toggleStickers() {
        if (stickerTabs != null) {
            stickerTabs = null
            return
        }
        action {
            val tabs = stickerRepository.tabs()
            stickerTabs = tabs
            selectStickerTab(tabs.first())
        }
    }

    fun selectStickerTab(tab: StickerTab) {
        selectedStickerTab = tab
        stickers = null
        stickersJob?.cancel()
        stickersJob = viewModelScope.launch {
            try {
                stickers = stickerRepository.stickers(tab)
            } catch (e: TdException) {
                notice = e.error.message
            }
        }
    }

    fun sendSticker(sticker: TdApi.Sticker) {
        stickerTabs = null
        action { session.sendSticker(sticker) }
    }

    /** Starts recording a voice message; the microphone permission must be granted. */
    fun startVoice() {
        try {
            voiceRecorder.start()
        } catch (e: Exception) {
            voiceRecorder.cancel()
            notice = "Can't record: ${e.message}"
        }
    }

    fun sendVoice() {
        val recording = voiceRecorder.stop() ?: return
        action { session.sendVoice(recording) }
    }

    fun cancelVoice() = voiceRecorder.cancel()

    override fun onCleared() {
        if (voiceRecorder.state.value != null) voiceRecorder.cancel()
    }

    /** Sends the current position; the location permission must be granted. */
    @SuppressLint("MissingPermission")
    fun sendCurrentLocation() {
        action {
            val location = locationSource.current()
            if (location == null) {
                notice = "Couldn't get your location. Is location turned on?"
            } else {
                session.sendLocation(location.latitude, location.longitude, location.accuracy.toDouble())
            }
        }
    }

    /** Where you are, for the map in the location sheet; null until known. */
    var myLocation by mutableStateOf<android.location.Location?>(null)
        private set

    /** Shows the last known position at once, then a fresh one; the location permission must be granted. */
    @SuppressLint("MissingPermission")
    fun locateMe() {
        myLocation = locationSource.lastKnown()
        viewModelScope.launchLogged(TAG) { locationSource.current()?.let { myLocation = it } }
    }

    /** Shares live location here; the location permission must be granted. */
    fun startLiveLocation(periodSeconds: Int) {
        liveLocation.start(chatId, header.value?.title.orEmpty(), periodSeconds)
    }

    fun stopLiveLocation() = liveLocation.stop()

    /** Photos and videos picked from the gallery, waiting for a caption before sending. */
    var pendingMedia by mutableStateOf<List<Uri>>(emptyList())
        private set

    /** Size of [pendingMedia] now and after compression, once estimated. */
    var pendingSizes by mutableStateOf<SizeEstimate?>(null)
        private set

    fun pickedMedia(uris: List<Uri>) {
        pendingMedia = uris
        pendingSizes = null
        if (uris.isNotEmpty()) viewModelScope.launch { pendingSizes = outgoingMedia.estimate(uris) }
    }

    /** True while picked media is being prepared (compressed) for sending. */
    var preparingMedia by mutableStateOf(false)
        private set

    /** Sends the picked media; an album gets the caption on its first item. */
    fun sendPendingMedia(caption: String, compress: Boolean) {
        val uris = pendingMedia
        pendingMedia = emptyList()
        preparingMedia = true
        action {
            try {
                val contents = uris.mapIndexedNotNull { index, uri ->
                    outgoingMedia.fromGallery(uri, caption = if (index == 0) caption else "", compress = compress)
                }
                session.sendMedia(contents)
            } finally {
                preparingMedia = false
            }
        }
    }

    fun cancelPendingMedia() {
        pendingMedia = emptyList()
    }

    fun startSearch() {
        searchQuery = ""
    }

    fun setSearch(query: String) {
        searchQuery = query
        searchQueries.value = query
    }

    fun closeSearch() {
        searchQuery = null
        searchResults = null
        searchQueries.value = ""
    }

    /** Steps to an older (+1) or newer (-1) match. */
    fun stepSearch(direction: Int) {
        val results = searchResults ?: return
        val index = (searchIndex + direction).coerceIn(0, results.lastIndex)
        if (index == searchIndex) return
        searchIndex = index
        show(results[index].messageId)
    }

    /** Scrolls to [messageId], loading older history as needed. */
    private fun show(messageId: Long) {
        action {
            if (session.loadUntil(messageId)) scrollTarget = messageId else notice = "Message not found"
        }
    }

    fun scrolled() {
        scrollTarget = null
    }

    fun reply(message: MessageItem) {
        menu = null
        composer = Composer.Reply(message)
    }

    fun edit(message: MessageItem) {
        menu = null
        composer = Composer.Edit(message)
    }

    fun cancelComposer() {
        composer = Composer.New
    }

    fun askDelete(menu: MessageMenu) {
        this.menu = null
        deleting = DeleteRequest(
            messageIds = listOf(menu.message.id),
            quote = menu.message.text.takeIf { it.isNotEmpty() && !menu.message.isMediaPlaceholder },
            canDeleteForSelf = menu.canDeleteForSelf,
            canDeleteForEveryone = menu.canDeleteForEveryone,
        )
    }

    /** Asks to delete the selected messages; "for everyone" only if all of them allow it. */
    fun askDeleteSelected() {
        val ids = selection.toList()
        action {
            val properties = ids.map { session.properties(it) }
            if (properties.none { it.canBeDeletedOnlyForSelf || it.canBeDeletedForAllUsers }) {
                notice = "These messages can't be deleted"
                return@action
            }
            deleting = DeleteRequest(
                messageIds = ids.filterIndexed { i, _ -> properties[i].canBeDeletedOnlyForSelf || properties[i].canBeDeletedForAllUsers },
                quote = null,
                canDeleteForSelf = properties.any { it.canBeDeletedOnlyForSelf },
                canDeleteForEveryone = properties.all { it.canBeDeletedForAllUsers },
            )
        }
    }

    fun delete(forEveryone: Boolean) {
        val request = deleting ?: return
        deleting = null
        selection = emptySet()
        action { session.delete(request.messageIds, forEveryone) }
    }

    /** Sends a failed message again. */
    fun retry(message: MessageItem) = action { session.resend(listOf(message.id)) }

    /** Deletes a message that failed to send; it exists only on this device. */
    fun discard(message: MessageItem) = action { session.delete(listOf(message.id), forEveryone = false) }

    /** Starts selecting messages, with [message] selected. */
    fun select(message: MessageItem) {
        menu = null
        selection = setOf(message.id)
    }

    fun toggleSelected(message: MessageItem) {
        if (message.isService) return
        selection = if (message.id in selection) selection - message.id else selection + message.id
    }

    fun clearSelection() {
        selection = emptySet()
    }

    /** Text of the selected messages, oldest first, for the clipboard. */
    fun selectedText(): String {
        val byId = messages.value.associateBy { it.id }
        return selection.sorted().mapNotNull { byId[it] }
            .filter { it.text.isNotEmpty() && !it.isMediaPlaceholder }
            .joinToString("\n\n") { message -> listOfNotNull(message.senderName?.let { "$it:" }, message.text).joinToString(" ") }
    }

    fun cancelDelete() {
        deleting = null
    }

    fun startForward(message: MessageItem) {
        menu = null
        forward(listOf(message.id))
    }

    fun forwardSelected() = forward(selection.toList())

    private fun forward(ids: List<Long>) {
        action {
            val folderId = folders.selectedFolderId.filterNotNull().first()
            val targets = chats.folderChats(folderId).first().map {
                ForwardTarget(
                    chatId = it.chat.id,
                    title = if (it.isSavedMessages) "Saved Messages" else it.chat.title,
                    photoFileId = it.chat.photo?.small?.id,
                    minithumbnail = it.chat.photo?.minithumbnail,
                    isSavedMessages = it.isSavedMessages,
                )
            }
            forwarding = ids to targets
        }
    }

    fun forward(target: ForwardTarget) {
        val ids = forwarding?.first ?: return
        forwarding = null
        selection = emptySet()
        action {
            session.forward(ids, target.chatId)
            notice = "Forwarded to ${target.title}"
        }
    }

    fun cancelForward() {
        forwarding = null
    }

    fun noticeShown() {
        notice = null
    }

    /** Runs [block]; any failure becomes a notice instead of a crash. */
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launchLogged(TAG, onError = { notice = it.userMessage() }) { block() }
    }
}

private const val TAG = "ChatViewModel"
private const val SEARCH_DELAY_MS = 300L

private fun lastSeen(status: TdApi.UserStatus?): String? = when (status) {
    is TdApi.UserStatusOnline -> "online"
    is TdApi.UserStatusOffline -> {
        val time = java.time.Instant.ofEpochSecond(status.wasOnline.toLong()).atZone(java.time.ZoneId.systemDefault())
        if (time.toLocalDate() == java.time.LocalDate.now()) "last seen at ${formatTime(status.wasOnline)}"
        else "last seen ${time.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))}"
    }
    is TdApi.UserStatusRecently -> "last seen recently"
    is TdApi.UserStatusLastWeek -> "last seen within a week"
    is TdApi.UserStatusLastMonth -> "last seen within a month"
    else -> null
}

/** Text messages, and media that can carry a caption. */
private val MessageItem.hasEditableText: Boolean
    get() = when (media) {
        null -> !isMediaPlaceholder && !isService
        is MessageMedia.Photo, is MessageMedia.Video, is MessageMedia.Animation, is MessageMedia.VoiceNote -> true
        else -> false
    }

private fun ChatHistory.toItems(isGroup: Boolean): List<MessageItem> {
    val byId = messages.associateBy { it.id }
    return messages.mapIndexed { index, message ->
        val media = message.toMedia()
        val older = messages.getOrNull(index + 1)
        val showSender = isGroup && !message.isOutgoing && !message.isService &&
            (older == null || older.isOutgoing || older.isService || older.senderKey() != message.senderKey())
        MessageItem(
            id = message.id,
            isOutgoing = message.isOutgoing,
            isService = message.isService,
            senderName = if (showSender) senderNames[message.senderKey()] else null,
            forwardedFrom = message.forwardInfo?.origin?.let(::originName),
            replyTo = (message.replyTo as? TdApi.MessageReplyToMessage)?.let { replyTo ->
                val replied = byId[replyTo.messageId] ?: repliedMessages[replyTo.messageId]
                when {
                    replied != null -> ReplyPreview(senderNames[replied.senderKey()], replied.summary())
                    replyTo.content != null -> ReplyPreview(replyTo.origin?.let(::originName), replyTo.content.summary())
                    else -> ReplyPreview(null, "Deleted message")
                }
            },
            text = if (media != null) message.content.caption() else message.summary(),
            media = media,
            isMediaPlaceholder = media == null && message.content !is TdApi.MessageText && !message.isService,
            date = message.date,
            isEdited = message.editDate > 0,
            status = if (!message.isOutgoing) null else when {
                message.sendingState is TdApi.MessageSendingStateFailed -> MessageStatus.Failed
                message.sendingState is TdApi.MessageSendingStatePending -> MessageStatus.Sending
                message.id <= lastReadOutboxMessageId -> MessageStatus.Read
                else -> MessageStatus.Sent
            },
            reactions = message.interactionInfo?.reactions?.reactions.orEmpty().map { reaction ->
                when (val type = reaction.type) {
                    is TdApi.ReactionTypeEmoji -> ReactionChip(type.emoji, type.emoji, reaction.totalCount, reaction.isChosen)
                    is TdApi.ReactionTypePaid -> ReactionChip("⭐", null, reaction.totalCount, reaction.isChosen)
                    else -> ReactionChip("✨", null, reaction.totalCount, reaction.isChosen)
                }
            },
        )
    }
}

private fun ChatHistory.originName(origin: TdApi.MessageOrigin): String? = when (origin) {
    is TdApi.MessageOriginUser -> senderNames[origin.senderUserId]
    is TdApi.MessageOriginHiddenUser -> origin.senderName
    is TdApi.MessageOriginChat -> senderNames[origin.senderChatId]
    is TdApi.MessageOriginChannel -> senderNames[origin.chatId]
    else -> null
}

private fun TdApi.Message.senderKey(): Long = when (val sender = senderId) {
    is TdApi.MessageSenderUser -> sender.userId
    is TdApi.MessageSenderChat -> sender.chatId
    else -> 0
}
