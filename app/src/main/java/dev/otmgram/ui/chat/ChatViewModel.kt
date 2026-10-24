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
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.launchLogged
import dev.otmgram.core.location.LiveLocationSharing
import dev.otmgram.core.location.LiveShare
import dev.otmgram.core.location.LocationSource
import dev.otmgram.core.media.AudioPlayer
import dev.otmgram.core.media.AudioState
import dev.otmgram.core.media.RecorderState
import dev.otmgram.core.media.VoiceRecorder
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.core.userMessage
import dev.otmgram.data.AppSettings
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.DownloadsFolder
import dev.otmgram.data.FileRepository
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.MessageRepository
import dev.otmgram.data.OutgoingMedia
import dev.otmgram.data.Sticker
import dev.otmgram.data.StickerRepository
import dev.otmgram.data.isGroup
import dev.otmgram.data.isSavedMessages
import dev.otmgram.di.ApplicationScope
import dev.otmgram.di.onMain
import dev.otmgram.ui.isService
import dev.otmgram.ui.summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
    val minithumbnail: ByteArray?,
    val isSavedMessages: Boolean,
)

/** Where the unread messages began when the chat was opened. */
data class UnreadMark(val afterMessageId: Long, val count: Int)

enum class MessageStatus { Sending, Sent, Read, Failed }

data class ReplyPreview(val senderName: String?, val text: String, val messageId: Long? = null)

/** The chat's newest pinned message, shown above the messages. */
data class PinnedMessage(val id: Long, val text: String)

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
    /** Links and formatting in [text]. */
    val spans: List<TextSpan> = emptyList(),
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
    val minithumbnail: ByteArray?,
    val isSavedMessages: Boolean,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
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
    private val td: TelegramClient,
    private val audioPlayer: AudioPlayer,
    appSettings: AppSettings,
    private val strings: Strings,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {

    /** For work that must finish even if the chat is left: sending, saving. */
    private val lasting = appScope.onMain()

    val chatId: Long = checkNotNull(savedState["chatId"])
    private val session = messages.openChat(chatId, viewModelScope)

    private val _header = MutableStateFlow<ChatHeader?>(null)
    val header: StateFlow<ChatHeader?> = _header.asStateFlow()

    /** Messages, newest first. */
    val messages: StateFlow<List<MessageItem>> = combine(session.history, _header) { history, header ->
        history.toItems(isGroup = header?.isGroup == true, strings)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    /** Unread messages when the chat was opened, for the "new since you left" line. */
    var unreadMark by mutableStateOf<UnreadMark?>(null)
        private set

    /** What's typed in the input bar; here, so searching, selecting or recording keeps it. */
    var text by mutableStateOf(savedState.get<String>(DRAFT_KEY).orEmpty())
        private set

    /** The draft set aside while editing a message, back once done. */
    private var draftBeforeEdit = ""

    /** The draft as Telegram has it (text, reply), so only changes are saved. */
    private var savedDraft: Pair<String, Long?> = "" to null

    /** The first unread message, to open the chat at; null once there or if none. */
    var openAt by mutableStateOf<Long?>(null)
        private set

    /** False until the chat shows where it opens; nothing is marked read before. */
    var positioned by mutableStateOf(false)
        private set

    /** One-time message: an error or a confirmation. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** Message to scroll to, consumed by the screen. */
    var scrollTarget by mutableStateOf<Long?>(null)
        private set

    /** Find in chat. */
    val search = ChatSearch(
        viewModelScope,
        search = { query ->
            val names = session.history.value.senderNames
            session.search(query).map { SearchResult(it.id, names[it.senderKey()], it.summary(strings), it.date) }
        },
        show = ::show,
        onError = ::showError,
    )

    /** Messages selected to copy, forward or delete. */
    val selection = ChatSelection(properties = session::properties, messages = { this.messages.value })

    val stickers = StickerPanel(viewModelScope, stickerRepository::tabs, stickerRepository::stickers, ::showError)

    /** Picked media and files in the send preview. */
    val outgoing = OutgoingQueue<Uri>(
        viewModelScope,
        lasting,
        estimate = outgoingMedia::estimate,
        prepareMedia = outgoingMedia::fromGallery,
        prepareFile = outgoingMedia::document,
        send = session::sendMedia,
        onError = ::showError,
    )

    private val viewedIds = mutableSetOf<Long>()

    init {
        action {
            try {
                val chat = td.send(TdApi.GetChat(chatId))
                val myId = td.send(TdApi.GetMe()).id
                val saved = chat.isSavedMessages(myId)
                _header.value = ChatHeader(
                    title = if (saved) strings.get(R.string.saved_messages) else chat.title,
                    isGroup = chat.isGroup,
                    subtitle = if (saved) null else subtitle(chat),
                    photoFileId = chat.photo?.small?.id,
                    minithumbnail = chat.photo?.minithumbnail?.data,
                    isSavedMessages = saved,
                )
                restoreDraft(chat.draftMessage)
                if (chat.unreadCount > 0) {
                    unreadMark = UnreadMark(chat.lastReadInboxMessageId, chat.unreadCount)
                    // Open at the first unread message: opening at the newest would mark them all
                    // read, seen or not (Telegram marks everything up to the newest one viewed).
                    session.loadUntil(chat.lastReadInboxMessageId, maxPages = FIRST_UNREAD_MAX_PAGES)
                    openAt = session.history.value.messages.filter { it.id > chat.lastReadInboxMessageId }.minOfOrNull { it.id }
                }
            } finally {
                // Nothing to open at (or it failed): mark what's on screen as usual.
                if (openAt == null) positioned = true
            }
        }
    }

    init {
        loadPinned()
        viewModelScope.launch {
            td.updates.collect { if (it is TdApi.UpdateMessageIsPinned && it.chatId == chatId) loadPinned() }
        }
    }

    fun loadOlder() = action { session.loadOlder() }

    fun type(value: String) {
        text = value
        if (composer !is Composer.Edit) savedState[DRAFT_KEY] = value
    }

    /** A draft from Telegram (typed here before, or on another device), unless something's typed. */
    private fun restoreDraft(draft: TdApi.DraftMessage?) {
        val draftText = (draft?.content as? TdApi.DraftMessageContentText)?.text?.text.orEmpty()
        val replyId = (draft?.replyTo as? TdApi.InputMessageReplyToMessage)?.messageId
        savedDraft = draftText to replyId
        if (text.isNotEmpty() || draftText.isEmpty()) return
        type(draftText)
        // The reply only if that message is loaded already.
        messages.value.find { it.id == replyId }?.let { composer = Composer.Reply(it) }
    }

    /** Saves what's typed as the chat's draft on Telegram, if it changed; on leaving the chat. */
    fun saveDraft() {
        val draftText = (if (composer is Composer.Edit) draftBeforeEdit else text).trim()
        val replyId = (composer as? Composer.Reply)?.message?.id?.takeIf { draftText.isNotEmpty() }
        if (draftText to replyId == savedDraft) return
        savedDraft = draftText to replyId
        val draft = draftText.takeIf { it.isNotEmpty() }?.let {
            TdApi.DraftMessage(
                replyId?.let { id -> TdApi.InputMessageReplyToMessage(id, null, 0, "") },
                0,
                TdApi.DraftMessageContentText(TdApi.FormattedText(it, emptyArray()), null),
                0,
                null,
            )
        }
        // Not suspending: also called as the view model goes away.
        td.sendIgnoringResult(TdApi.SetChatDraftMessage(chatId, null, draft))
    }

    fun send() {
        val text = text
        val mode = composer
        // A caption may be removed by saving it empty; text messages can't be empty.
        val removingCaption = mode is Composer.Edit && mode.message.media != null
        if (text.isBlank() && !removingCaption) return
        composer = Composer.New
        type(if (mode is Composer.Edit) draftBeforeEdit else "")
        // Sending clears the draft on Telegram.
        if (mode !is Composer.Edit) savedDraft = "" to null
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

    /** The chat is shown at [openAt], with [visibleIds] on screen. */
    fun opened(visibleIds: List<Long>) {
        openAt = null
        positioned = true
        onMessagesVisible(visibleIds)
    }

    /** Called with the ids of messages currently on screen. */
    fun onMessagesVisible(ids: List<Long>) {
        if (!positioned) return
        val new = ids.filter { viewedIds.add(it) }
        if (new.isNotEmpty()) action { session.markViewed(new) }
    }

    /** "3 members", "online", "last seen recently". */
    private suspend fun subtitle(chat: TdApi.Chat): String? = when (val type = chat.type) {
        is TdApi.ChatTypeBasicGroup -> members(td.send(TdApi.GetBasicGroup(type.basicGroupId)).memberCount)
        is TdApi.ChatTypeSupergroup -> members(td.send(TdApi.GetSupergroup(type.supergroupId)).memberCount)
        is TdApi.ChatTypePrivate -> {
            val user = td.send(TdApi.GetUser(type.userId))
            if (user.type is TdApi.UserTypeBot) strings.get(R.string.status_bot) else lastSeen(user.status, strings)
        }
        else -> null
    }

    private fun members(count: Int) = if (count <= 0) null else strings.plural(R.plurals.members, count, count)

    fun openMenu(message: MessageItem) {
        if (message.isService) return
        if (selection.isActive) {
            selection.toggle(message)
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
        notice = strings.get(R.string.notice_downloading, document.name)
        lasting.launchLogged(TAG, onError = ::showError) {
            downloads.save(File(files.downloadNow(document.fileId)), document.name, document.mimeType)
            notice = strings.get(R.string.notice_saved_to_downloads)
        }
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

    fun sendSticker(sticker: Sticker) {
        stickers.close()
        action { session.sendSticker(sticker) }
    }

    /** Starts recording a voice message; the microphone permission must be granted. */
    fun startVoice() {
        try {
            voiceRecorder.start()
        } catch (e: Exception) {
            voiceRecorder.cancel()
            notice = strings.get(R.string.notice_cant_record, e.userMessage(strings))
        }
    }

    fun sendVoice() {
        val recording = voiceRecorder.stop() ?: return
        action { session.sendVoice(recording) }
    }

    fun cancelVoice() = voiceRecorder.cancel()

    override fun onCleared() {
        saveDraft()
        if (voiceRecorder.state.value != null) voiceRecorder.cancel()
    }

    /** Sends the current position; the location permission must be granted. */
    @SuppressLint("MissingPermission")
    fun sendCurrentLocation() {
        action {
            val location = locationSource.current()
            if (location == null) {
                notice = strings.get(R.string.notice_no_location)
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

    /** Scrolls to [messageId], loading older history as needed. */
    private fun show(messageId: Long) {
        action {
            if (session.loadUntil(messageId)) scrollTarget = messageId else notice = strings.get(R.string.notice_message_not_found)
        }
    }

    /** Message briefly marked after jumping to it, so the eye finds it. */
    var flashed by mutableStateOf<Long?>(null)
        private set

    /** Shows a message replied to, or the pinned one, marking it for a moment. */
    fun jumpTo(messageId: Long) {
        show(messageId)
        viewModelScope.launch {
            flashed = messageId
            delay(FLASH_MS)
            if (flashed == messageId) flashed = null
        }
    }

    var pinned by mutableStateOf<PinnedMessage?>(null)
        private set

    private fun loadPinned() = viewModelScope.launchLogged(TAG) {
        pinned = try {
            td.send(TdApi.GetChatPinnedMessage(chatId)).let { PinnedMessage(it.id, it.summary(strings)) }
        } catch (e: TdException) {
            null // None pinned.
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
        if (composer !is Composer.Edit) draftBeforeEdit = text
        composer = Composer.Edit(message)
        text = message.text
    }

    fun cancelComposer() {
        val wasEditing = composer is Composer.Edit
        composer = Composer.New
        if (wasEditing) type(draftBeforeEdit)
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

    /** Asks to delete the selected messages. */
    fun askDeleteSelected() = action {
        val request = selection.deleteRequest()
        if (request == null) notice = strings.get(R.string.notice_cant_delete) else deleting = request
    }

    fun delete(forEveryone: Boolean) {
        val request = deleting ?: return
        deleting = null
        selection.clear()
        action { session.delete(request.messageIds, forEveryone) }
    }

    /** Sends a failed message again. */
    fun retry(message: MessageItem) = action { session.resend(listOf(message.id)) }

    /** Deletes a message that failed to send; it exists only on this device. */
    fun discard(message: MessageItem) = action { session.delete(listOf(message.id), forEveryone = false) }

    /** Starts selecting messages, with [message] selected. */
    fun select(message: MessageItem) {
        menu = null
        selection.start(message)
    }

    fun cancelDelete() {
        deleting = null
    }

    fun startForward(message: MessageItem) {
        menu = null
        forward(listOf(message.id))
    }

    fun forwardSelected() = forward(selection.selected.toList())

    private fun forward(ids: List<Long>) {
        action {
            val folderId = folders.selectedFolderId.filterNotNull().first()
            val targets = chats.folderChats(folderId).first().map {
                ForwardTarget(
                    chatId = it.chat.id,
                    title = if (it.isSavedMessages) strings.get(R.string.saved_messages) else it.chat.title,
                    photoFileId = it.chat.photo?.small?.id,
                    minithumbnail = it.chat.photo?.minithumbnail?.data,
                    isSavedMessages = it.isSavedMessages,
                )
            }
            forwarding = ids to targets
        }
    }

    fun forward(target: ForwardTarget) {
        val ids = forwarding?.first ?: return
        forwarding = null
        selection.clear()
        action {
            session.forward(ids, target.chatId)
            notice = strings.get(R.string.notice_forwarded, target.title)
        }
    }

    fun cancelForward() {
        forwarding = null
    }

    fun noticeShown() {
        notice = null
    }

    private fun showError(e: Exception) {
        notice = e.userMessage(strings)
    }

    /** Runs [block]; any failure becomes a notice instead of a crash. */
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launchLogged(TAG, onError = { notice = it.userMessage(strings) }) { block() }
    }
}

private const val TAG = "ChatViewModel"

private const val DRAFT_KEY = "draft"

/** Opening at the first unread loads history back to it, but not further than this. */
private const val FIRST_UNREAD_MAX_PAGES = 10

/** How long a message jumped to stays marked. */
private const val FLASH_MS = 1_500L
