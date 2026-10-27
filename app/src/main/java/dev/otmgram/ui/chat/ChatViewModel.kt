package dev.otmgram.ui.chat

import android.annotation.SuppressLint
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.launchLogged
import dev.otmgram.core.location.LiveLocationSharing
import dev.otmgram.core.location.LocationSource
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.media.AudioPlayer
import dev.otmgram.core.media.AudioState
import dev.otmgram.core.media.RecorderState
import dev.otmgram.core.media.VoiceRecorder
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.core.td.updates
import dev.otmgram.core.userMessage
import dev.otmgram.data.AppSettings
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.DownloadsFolder
import dev.otmgram.data.FileRepository
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.MessageRepository
import dev.otmgram.data.OutgoingMedia
import dev.otmgram.data.SendQuality
import dev.otmgram.data.Sticker
import dev.otmgram.data.StickerRepository
import dev.otmgram.data.isGroup
import dev.otmgram.data.isSavedMessages
import dev.otmgram.data.isService
import dev.otmgram.data.summary
import dev.otmgram.di.ApplicationScope
import dev.otmgram.di.onMain
import dev.otmgram.ui.ChatRoute
import dev.otmgram.ui.format.lastSeen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    messageRepository: MessageRepository,
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
    private val positions: ChatPositions,
    appSettings: AppSettings,
    private val strings: Strings,
    @ApplicationScope appScope: CoroutineScope,
) : ViewModel() {

    /** For work that must finish even if the chat is left: sending, saving. */
    private val lasting = appScope.onMain()

    val chatId: Long = savedState.toRoute<ChatRoute>().chatId
    private val session = messageRepository.openChat(chatId, viewModelScope)

    private val _header = MutableStateFlow<ChatHeader?>(null)
    val header: StateFlow<ChatHeader?> = _header.asStateFlow()

    /** Messages, newest first. */
    val messages: StateFlow<List<MessageItem>> = combine(session.history, _header) { history, header ->
        history.toItems(isGroup = header?.isGroup == true, strings)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whether the messages shown reach the newest; not after jumping to an older one. */
    val atNewest: StateFlow<Boolean> = session.history.map { it.reachedEnd }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Voice message being recorded, or null. */
    val recording: StateFlow<RecorderState?> = voiceRecorder.state

    /** The quality the send preview starts with. */
    val sendQuality: StateFlow<SendQuality> = appSettings.sendQuality.stateIn(viewModelScope, SharingStarted.Eagerly, SendQuality.HD)

    /** Voice message being played, anywhere in the app. */
    val audio: StateFlow<AudioState?> = audioPlayer.state

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

    /** The input bar: what's typed, and whether it's a new message, a reply or an edit. */
    val composer = DraftComposer(
        viewModelScope,
        initialText = savedState.get<String>(DRAFT_KEY).orEmpty(),
        keep = { savedState[DRAFT_KEY] = it },
        saveToTelegram = { draft -> td.sendIgnoringResult(TdApi.SetChatDraftMessage(chatId, null, draft)) },
        send = { mode, text ->
            when (mode) {
                Composer.New -> session.sendText(text)
                is Composer.Reply -> session.sendText(text, replyToMessageId = mode.message.id)
                is Composer.Edit -> if (mode.message.media != null) session.editCaption(mode.message.id, text) else session.editText(mode.message.id, text)
            }
        },
        onError = {
            AppLog.w(TAG, "Sending failed", it)
            showError(it)
        },
    )

    /** The first unread message, to open the chat at; null once there or if none. */
    var openAt by mutableStateOf<Long?>(null)
        private set

    /** Where the chat was left last time, to open it there instead; null once there or if none. */
    var openPosition by mutableStateOf<ChatPosition?>(null)
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
                composer.restore(chat.draftMessage) { id -> messages.value.find { it.id == id } }
                if (chat.unreadCount > 0) unreadMark = UnreadMark(chat.lastReadInboxMessageId, chat.unreadCount)
                // Where it was left, if that message is still there: loaded with the newest
                // ones if it's close to them, else on its own, as after a jump.
                val left = positions[chatId]?.takeIf { left ->
                    session.loadUntil(left.messageId, maxPages = POSITION_MAX_PAGES) || session.jumpTo(left.messageId)
                }
                if (left != null) {
                    openPosition = left
                } else if (chat.unreadCount > 0) {
                    // Open at the first unread message: opening at the newest would mark them all
                    // read, seen or not (Telegram marks everything up to the newest one viewed).
                    session.loadUntil(chat.lastReadInboxMessageId, maxPages = FIRST_UNREAD_MAX_PAGES)
                    openAt = session.history.value.messages.filter { it.id > chat.lastReadInboxMessageId }.minOfOrNull { it.id }
                }
            } finally {
                // Nothing to open at (or it failed): mark what's on screen as usual.
                if (openAt == null && openPosition == null) positioned = true
            }
        }
    }

    init {
        viewModelScope.launch {
            audioPlayer.failures.collect { fileId ->
                val inThisChat = messages.value.any { (it.media as? MessageMedia.VoiceNote)?.fileId == fileId }
                if (inThisChat) notice = strings.get(R.string.notice_voice_cant_play)
            }
        }
    }

    fun loadOlder() = action { session.loadOlder() }

    fun loadNewer() = action { session.loadNewer() }

    /** Back to the newest messages after jumping to an older one. */
    fun returnToNewest() = action { session.returnToNewest() }

    /** The chat is shown at [openAt] or [openPosition], with [visibleIds] on screen. */
    fun opened(visibleIds: List<Long>) {
        openAt = null
        openPosition = null
        positioned = true
        onMessagesVisible(visibleIds)
    }

    /** Scrolled to [position]; null at the newest messages, where a chat opens anyway. */
    fun leaveAt(position: ChatPosition?) {
        if (positioned) positions[chatId] = position
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

    /** The menu of [message], acting on all of [album] where it can (forward, select, delete). */
    fun openMenu(message: MessageItem, album: List<MessageItem> = listOf(message)) {
        if (message.isService) return
        if (selection.isActive) {
            selection.toggle(album)
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
                messageIds = album.map { it.id },
                reactions = reactions,
                canReply = properties.canBeReplied,
                canForward = properties.canBeForwarded,
                canEdit = properties.canBeEdited && message.hasEditableText,
                canDeleteForSelf = properties.canBeDeletedOnlyForSelf,
                canDeleteForEveryone = properties.canBeDeletedForAllUsers,
                canSave = message.media is MessageMedia.Document,
                canCopy = message.text.isNotEmpty() && !message.isMediaPlaceholder,
                canPin = properties.canBePinned,
                isPinned = properties.canBePinned && session.isPinned(message.id),
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
        composer.save()
        if (voiceRecorder.state.value != null) voiceRecorder.cancel()
    }

    /** Sending where you are; the screen asks for the location permission first. */
    @SuppressLint("MissingPermission")
    val location = ChatLocation(
        viewModelScope,
        chatId,
        current = locationSource::current,
        lastKnown = locationSource::lastKnown,
        send = { session.sendLocation(it.latitude, it.longitude, it.accuracy.toDouble()) },
        liveShare = liveLocation.share,
        liveFailures = liveLocation.failures,
        startSharing = { period -> liveLocation.start(chatId, header.value?.title.orEmpty(), period) },
        stopSharing = liveLocation::stop,
        strings = strings,
        notify = { notice = it },
        onError = ::showError,
    )

    /** Scrolls to [messageId], loading older history as needed. */
    private fun show(messageId: Long) {
        action {
            if (session.jumpTo(messageId)) scrollTarget = messageId else notice = strings.get(R.string.notice_message_not_found)
        }
    }

    /** Message briefly marked after jumping to it, so the eye finds it. */
    var flashed by mutableStateOf<Long?>(null)
        private set

    /** Jumped to, to mark once the list is there: loading older history can take a while. */
    private var flashOnArrival: Long? = null

    /** Shows a message replied to, or a pinned one, marking it for a moment. */
    fun jumpTo(messageId: Long) {
        flashOnArrival = messageId
        show(messageId)
    }

    /** Pinned messages, for the bar above the messages and its list. */
    val pinned = PinnedMessages(
        viewModelScope,
        load = {
            val names = session.history.value.senderNames
            try {
                session.pinned().map { SearchResult(it.id, names[it.senderKey()], it.summary(strings), it.date) }
            } catch (e: TdException) {
                emptyList()
            }
        },
        changes = td.updates { it is TdApi.UpdateMessageIsPinned && it.chatId == chatId }.map { },
        show = ::jumpTo,
        onError = { AppLog.w(TAG, "Loading pinned messages failed", it) },
    )

    fun togglePin(menu: MessageMenu) {
        this.menu = null
        action { session.setPinned(menu.message.id, !menu.isPinned) }
    }

    fun scrolled() {
        val target = scrollTarget
        scrollTarget = null
        if (target == null || target != flashOnArrival) return
        flashOnArrival = null
        viewModelScope.launch {
            flashed = target
            delay(FLASH_MS)
            if (flashed == target) flashed = null
        }
    }

    fun reply(message: MessageItem) {
        menu = null
        composer.reply(message)
    }

    fun edit(message: MessageItem) {
        menu = null
        composer.edit(message)
    }

    fun askDelete(menu: MessageMenu) {
        this.menu = null
        deleting = DeleteRequest(
            messageIds = menu.messageIds,
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

    /** Starts selecting messages, with the menu's selected. */
    fun select(menu: MessageMenu) {
        this.menu = null
        selection.start(menu.messageIds)
    }

    fun cancelDelete() {
        deleting = null
    }

    fun startForward(menu: MessageMenu) {
        this.menu = null
        forward(menu.messageIds)
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
    private fun action(onFailure: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launchLogged(TAG, onError = {
            notice = it.userMessage(strings)
            onFailure()
        }) { block() }
    }
}

private const val TAG = "ChatViewModel"

private const val DRAFT_KEY = "draft"

/** Opening at the first unread loads history back to it, but not further than this. */
private const val FIRST_UNREAD_MAX_PAGES = 10

/** How far back a chat's last place is loaded along with the newest messages, in pages. */
private const val POSITION_MAX_PAGES = 4

/** How long a message jumped to stays marked. */
private const val FLASH_MS = 2_500L
