package dev.otmgram.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.R
import dev.otmgram.ui.ProfileRoute
import dev.otmgram.ui.format.dayLabel
import dev.otmgram.ui.openAppSettings
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.ScreenBar
import dev.otmgram.ui.theme.paper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenMedia: (chatId: Long, messageId: Long) -> Unit,
    onRecordVideoNote: (chatId: Long) -> Unit,
    onOpenMap: (chatId: Long, messageId: Long) -> Unit,
    onOpenEmbed: (url: String) -> Unit,
    onOpenProfile: (ProfileRoute) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val liveShare by viewModel.location.live.collectAsStateWithLifecycle()
    var attaching by rememberSaveable { mutableStateOf(false) }
    var choosingLocation by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val unreadMark = viewModel.unreadMark
    val rows = remember(messages, unreadMark) { buildRows(messages, unreadMark) }
    val selection = viewModel.selection.selected
    val selecting = viewModel.selection.isActive
    val search = viewModel.search
    val searching = search.isOpen
    val clipboard = LocalClipboard.current
    val title = header?.title.orEmpty()

    BackHandler(enabled = selecting, onBack = viewModel.selection::clear)
    BackHandler(enabled = searching && !selecting, onBack = search::close)

    // Load older messages when scrolled close to the oldest loaded one.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - LOAD_MORE_THRESHOLD
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) viewModel.loadOlder() }
    }
    // After jumping to an older message: load newer ones when scrolled close to the newest loaded.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex <= LOAD_MORE_THRESHOLD }
            .distinctUntilChanged()
            .collect { nearNewest -> if (nearNewest) viewModel.loadNewer() }
    }
    // Save the draft when the app goes to the background: the process may not come back.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.composer.save() }
    // Mark messages on screen as read.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
            .distinctUntilChanged()
            .collect(viewModel::onMessagesVisible)
    }
    // Follow new messages if the previous newest one was at the bottom (several can arrive
    // at once, e.g. an album), and always after sending. Saved, so coming back from the
    // viewer or a rotation keeps the place instead of jumping to the bottom.
    val newest = messages.firstOrNull()
    val atNewest by viewModel.atNewest.collectAsStateWithLifecycle()
    var previousNewestId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(newest?.id) {
        val previousIndex = previousNewestId?.let { id -> rows.indexOfFirst { it.has(id) } } ?: -1
        previousNewestId = newest?.id
        // Newer messages loaded after a jump aren't new ones.
        if (!atNewest) return@LaunchedEffect
        // Opening at the first unread message instead.
        if (!viewModel.positioned) return@LaunchedEffect
        if (previousIndex < 0 || listState.firstVisibleItemIndex <= previousIndex || newest?.isOutgoing == true) {
            listState.animateScrollToItem(0)
        }
    }
    // Open at the first unread message, its "new since you left" line near the top.
    val topMargin = with(LocalDensity.current) { OPEN_AT_TOP_MARGIN.roundToPx() }
    viewModel.openAt?.let { target ->
        LaunchedEffect(target, rows) {
            val index = rows.indexOfFirst { it is ListRow.Unread }.takeIf { it >= 0 }
                ?: rows.indexOfFirst { it.has(target) }
            if (index < 0) return@LaunchedEffect
            listState.scrollToItem(index)
            // The list starts at the bottom: move the line up to the top, if there's room.
            val line = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (line != null) listState.scrollToItem(index, -(listState.layoutInfo.viewportSize.height - line.size - topMargin))
            viewModel.opened(listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long })
        }
    }
    // Open where the chat was left.
    viewModel.openPosition?.let { position ->
        LaunchedEffect(position, rows) {
            val index = rows.indexOfFirst { it.has(position.messageId) }
            if (index < 0) return@LaunchedEffect
            listState.scrollToItem(index, position.offset)
            viewModel.opened(listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long })
        }
    }
    // Remember where it's left: the message (or album, keyed by its newest) at the bottom of the screen.
    LaunchedEffect(listState) {
        snapshotFlow {
            val bottom = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }
            val atBottom = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            if (bottom == null || (atBottom && atNewest)) null else ChatPosition(bottom.key as Long, -bottom.offset)
        }.distinctUntilChanged().collect(viewModel::leaveAt)
    }
    viewModel.scrollTarget?.let { target ->
        LaunchedEffect(target, rows) {
            val index = rows.indexOfFirst { it.has(target) }
            if (index >= 0) {
                // Center-ish: leave room above the match.
                listState.glideTo(index, offset = -listState.layoutInfo.viewportSize.height / 3)
                viewModel.scrolled()
            }
        }
    }
    viewModel.notice?.let { notice ->
        LaunchedEffect(notice) {
            snackbar.showSnackbar(notice)
            viewModel.noticeShown()
        }
    }

    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The keyboard and the sticker picker take turns: the keyboard opening closes the picker,
    // and so does back.
    val inputFocus = remember { FocusRequester() }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) viewModel.stickers.close() }
    BackHandler(enabled = viewModel.stickers.isOpen && !selecting && !searching, onBack = viewModel.stickers::close)
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), viewModel.outgoing::pickFiles)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_MEDIA), viewModel.outgoing::pickMedia)
    // A refused permission says what it's for, with the way to the settings where it can be
    // allowed again: after two refusals, Android no longer asks.
    val scope = rememberCoroutineScope()
    val settingsLabel = stringResource(R.string.open_settings)
    val resources = LocalResources.current
    val refused: (Int) -> Unit = { reason ->
        scope.launch {
            val result = snackbar.showSnackbar(resources.getString(reason), actionLabel = settingsLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) context.openAppSettings()
        }
    }
    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) viewModel.location.sendCurrent() else refused(R.string.permission_location)
    }
    var pendingLivePeriod by rememberSaveable { mutableStateOf<Int?>(null) }
    val requestLiveLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val period = pendingLivePeriod

        // Only compares the name: fine on Android versions without that permission.
        @SuppressLint("InlinedApi")
        val notifications = Manifest.permission.POST_NOTIFICATIONS
        val located = result.filterKeys { it != notifications }.values.any { it } || hasLocationPermission(context)
        if (period != null && located) viewModel.location.startLive(period)
        if (!located) refused(R.string.permission_location)
        pendingLivePeriod = null
    }
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoice() else refused(R.string.permission_microphone)
    }

    Box(modifier.fillMaxSize().paper()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            val barMode = when {
                selecting -> BarMode.Selecting
                searching -> BarMode.Searching
                else -> BarMode.Chat
            }
            AnimatedContent(barMode, transitionSpec = { fadeIn(tween(QUICK_MS)) togetherWith fadeOut(tween(QUICK_MS)) }, label = "topBar") { mode ->
                when (mode) {
                    BarMode.Selecting -> SelectionBar(
                        count = selection.size,
                        onCancel = viewModel.selection::clear,
                        onCopy = {
                            val text = viewModel.selection.text()
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
                            viewModel.selection.clear()
                        },
                    )
                    BarMode.Searching -> FindBar(search.query.orEmpty(), search::type, search::close)
                    BarMode.Chat -> ScreenBar(
                        title = title,
                        subtitle = header?.subtitle,
                        onBack = onBack,
                        leading = header?.let { h ->
                            { ChatAvatar(viewModel.chatId, h.title, h.photoFileId, h.minithumbnail, size = 38.dp, savedMessages = h.isSavedMessages) }
                        },
                        onTitleClick = header?.profile?.let { profile -> { onOpenProfile(profile) } },
                        actions = { IconBtn(OtmIcons.Search, stringResource(R.string.search_chat), search::open, tag = "search") },
                    )
                }
            }
            val pinned = viewModel.pinned
            if (pinned.messages.isNotEmpty() && !selecting && !searching) {
                PinnedBar(pinned.messages, pinned.shown, onClick = pinned::showNext, onList = pinned::openList)
            }
            liveShare?.let { share ->
                LiveShareBanner(share, onStop = viewModel.location::stopLive)
            }
            if (viewModel.outgoing.preparing) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("preparing"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.preparing_media), style = OtmType.Secondary, color = Otm.InkSoft)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Otm.Slate, trackColor = Otm.Haze)
                }
            }

            val results = search.results
            val resultIds = remember(results) { results?.map { it.messageId }?.toSet().orEmpty() }
            val currentMatch = results?.getOrNull(search.index)?.messageId
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize().testTag("messages"),
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                ) {
                    items(rows, key = { it.key }, contentType = ListRow::contentType) { row ->
                        // New messages fade in and push the others up; removed ones fade out.
                        Box(Modifier.animateItem(fadeInSpec = tween(ROW_FADE_IN_MS), placementSpec = ROW_PLACEMENT, fadeOutSpec = tween(ROW_FADE_OUT_MS))) {
                            when (row) {
                                is ListRow.Day -> Divider(dayLabel(row.date))
                                is ListRow.Unread -> Divider(
                                    pluralStringResource(R.plurals.new_since_you_left, row.count, row.count),
                                    color = Otm.Slate,
                                    line = Otm.RidgeLight,
                                )
                                is ListRow.Message -> if (row.message.isService) {
                                    ServiceNote(row.message.text)
                                } else {
                                    val message = row.message
                                    // Read only by voice messages, so playback (updated every 100 ms) recomposes
                                    // only the playing one, not the whole chat.
                                    val voice = message.media as? MessageMedia.VoiceNote
                                    val audio = voice?.let { viewModel.audio.collectAsStateWithLifecycle().value?.takeIf { it.fileId == voice.fileId } }
                                    MessageBubble(
                                        message,
                                        audio = audio,
                                        onClick = { viewModel.openMenu(message) },
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.startSelecting(listOf(message))
                                        },
                                        onReactionClick = { viewModel.toggleReaction(message, it) },
                                        onOpenMedia = {
                                            when (val media = message.media) {
                                                is MessageMedia.Location -> onOpenMap(viewModel.chatId, message.id)
                                                is MessageMedia.Contact -> onOpenProfile(ProfileRoute(userId = media.userId))
                                                else -> onOpenMedia(viewModel.chatId, message.id)
                                            }
                                        },
                                        onPlayVoice = { fileId, path -> viewModel.playVoice(message, fileId, path) },
                                        onVideoNoteSound = { viewModel.markContentOpened(message) },
                                        onOpenSender = { onOpenProfile(ProfileRoute(userId = it)) },
                                        onOpenReply = viewModel::jumpTo,
                                        onOpenLink = { onOpenEmbed(it.url) },
                                        highlight = if (searching && message.id in resultIds) {
                                            Highlight(search.query.orEmpty(), current = message.id == currentMatch)
                                        } else {
                                            null
                                        },
                                        selecting = selecting,
                                        selected = message.id in selection,
                                        flashed = message.id == viewModel.flashed,
                                        onToggleSelected = { viewModel.selection.toggle(message) },
                                        onRetry = { viewModel.retry(message) },
                                        onDiscard = { viewModel.discard(message) },
                                    )
                                }
                                is ListRow.Album -> {
                                    val ids = row.messages.map { it.id }
                                    MessageBubble(
                                        row.shown,
                                        audio = null,
                                        onClick = { viewModel.openMenu(row.main, row.messages) },
                                        onLongClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.startSelecting(row.messages)
                                        },
                                        onReactionClick = { viewModel.toggleReaction(row.main, it) },
                                        onOpenMedia = {},
                                        onPlayVoice = { _, _ -> },
                                        onVideoNoteSound = {},
                                        onOpenSender = { onOpenProfile(ProfileRoute(userId = it)) },
                                        onOpenReply = viewModel::jumpTo,
                                        highlight = if (searching && ids.any { it in resultIds }) {
                                            Highlight(search.query.orEmpty(), current = currentMatch in ids)
                                        } else {
                                            null
                                        },
                                        selecting = selecting,
                                        selected = ids.all { it in selection },
                                        flashed = viewModel.flashed in ids,
                                        onToggleSelected = { viewModel.selection.toggle(row.messages) },
                                        album = row.messages,
                                        onOpenAlbumItem = { onOpenMedia(viewModel.chatId, it) },
                                        onCancelAlbumItem = { id -> row.messages.find { it.id == id }?.let(viewModel::discard) },
                                    )
                                }
                            }
                        }
                    }
                }
                // The day of the messages at the top, floating there while scrolling and a moment after.
                val scrolling = listState.isScrollInProgress
                var showDay by remember { mutableStateOf(false) }
                LaunchedEffect(scrolling) {
                    if (scrolling) {
                        showDay = true
                    } else {
                        delay(DAY_LINGER_MS)
                        showDay = false
                    }
                }
                val topDate by remember(rows) {
                    derivedStateOf {
                        // reverseLayout: the top row is the last one laid out.
                        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { rows.getOrNull(it)?.date }
                    }
                }
                var shownDate by remember { mutableStateOf(0) }
                topDate?.let { shownDate = it }
                androidx.compose.animation.AnimatedVisibility(
                    showDay && shownDate != 0,
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    enter = fadeIn(tween(QUICK_MS)) + slideInVertically(tween(QUICK_MS)) { -it / 2 },
                    exit = fadeOut(tween(DAY_FADE_MS)),
                ) {
                    Text(
                        dayLabel(shownDate),
                        style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold),
                        color = Otm.InkBody,
                        modifier = Modifier
                            .shadow(2.dp, RoundedCornerShape(14.dp), ambientColor = Otm.Ink.copy(alpha = 0.3f), spotColor = Otm.Ink.copy(alpha = 0.3f))
                            .background(Otm.Card.copy(alpha = 0.95f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                            .testTag("scrollDay"),
                    )
                }
                // Back to the newest message, once scrolled away from it.
                val scrolledAway by remember { derivedStateOf { listState.firstVisibleItemIndex > JUMP_BUTTON_AFTER } }
                // Not ColumnScope's: this sits in the list's box.
                androidx.compose.animation.AnimatedVisibility(
                    scrolledAway || !atNewest,
                    Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    enter = fadeIn(tween(QUICK_MS)) + scaleIn(tween(QUICK_MS), initialScale = 0.6f),
                    exit = fadeOut(tween(QUICK_MS)) + scaleOut(tween(QUICK_MS), targetScale = 0.6f),
                ) {
                    IconBtn(
                        OtmIcons.ChevronDown,
                        stringResource(R.string.jump_to_newest),
                        // After a jump the newest aren't loaded: they replace what's shown.
                        { if (atNewest) scope.launch { listState.glideTo(0) } else viewModel.returnToNewest() },
                        tint = Otm.SlateDeep,
                        background = Otm.Card,
                        tag = "jumpToNewest",
                    )
                }
            }
            // Under the list rather than in it, which would move the rows' indexes.
            if (viewModel.location.locating || liveShare?.located == false) LocatingBubble(Modifier.padding(bottom = 10.dp))

            val current = recording
            val scheduled by viewModel.scheduled.collectAsStateWithLifecycle()
            if (scheduled.isNotEmpty() && !selecting && !searching && current == null) {
                ScheduledBar(scheduled.size, viewModel::openScheduled)
            }
            when {
                selecting -> {
                    val entered = remember { MutableTransitionState(false) }.apply { targetState = true }
                    AnimatedVisibility(entered, enter = slideInVertically(tween(QUICK_MS)) { it / 2 } + fadeIn(tween(QUICK_MS))) {
                        SelectionActions(onForward = viewModel::forwardSelected, onDelete = viewModel::askDeleteSelected)
                    }
                }
                searching -> FindStepper(results, search.index, search.query.orEmpty(), onStep = search::step)
                current != null -> RecordingBar(current, onCancel = viewModel::cancelVoice, onSend = viewModel::sendVoice)
                else -> InputBar(
                    readText = { viewModel.composer.text },
                    onTextChange = viewModel.composer::type,
                    composer = viewModel.composer.mode,
                    chatTitle = title,
                    stickersOpen = viewModel.stickers.isOpen,
                    onCancel = viewModel.composer::cancel,
                    onSend = { viewModel.composer.send() },
                    onSendLater = {
                        keyboard?.hide()
                        viewModel.askSendLater()
                    },
                    onStickers = {
                        if (viewModel.stickers.isOpen) {
                            viewModel.stickers.close()
                            inputFocus.requestFocus()
                            keyboard?.show()
                        } else {
                            keyboard?.hide()
                            viewModel.stickers.toggle()
                        }
                    },
                    focus = inputFocus,
                    onAttach = {
                        keyboard?.hide()
                        attaching = true
                    },
                    onRecordVideoNote = { onRecordVideoNote(viewModel.chatId) },
                )
            }
            if (!selecting && !searching && current == null) {
                viewModel.stickers.tabs?.let { tabs ->
                    StickerPicker(
                        tabs = tabs,
                        selected = viewModel.stickers.selected,
                        stickers = viewModel.stickers.stickers,
                        onSelectTab = viewModel.stickers::select,
                        onSend = viewModel::sendSticker,
                    )
                }
            }
        }
        // Above the input bar, and the keyboard when it's open.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 72.dp)) {
            Snackbar(it, containerColor = Otm.Ink, contentColor = Otm.Paper, actionColor = Otm.Apricot, shape = RoundedCornerShape(14.dp))
        }
    }

    if (attaching) {
        AttachSheet(
            onGallery = {
                attaching = false
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onFile = {
                attaching = false
                pickFiles.launch(arrayOf("*/*"))
            },
            onLocation = {
                attaching = false
                choosingLocation = true
            },
            onVoice = {
                attaching = false
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (granted) viewModel.startVoice() else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            },
            onContact = {
                attaching = false
                viewModel.chooseContact()
            },
            onDismiss = { attaching = false },
        )
    }
    if (viewModel.choosingContact) {
        ContactSheet(viewModel.contacts, onShare = viewModel::shareContact, onDismiss = viewModel::cancelContact)
    }
    if (choosingLocation) {
        LaunchedEffect(Unit) { if (hasLocationPermission(context)) viewModel.location.locate() }
        LocationSheet(
            chatTitle = title,
            here = viewModel.location.here,
            onSendCurrent = {
                choosingLocation = false
                if (hasLocationPermission(context)) {
                    viewModel.location.sendCurrent()
                } else {
                    requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            },
            onShareLive = { period ->
                choosingLocation = false
                val needed = buildList {
                    if (!hasLocationPermission(context)) {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (needed.isEmpty()) {
                    viewModel.location.startLive(period)
                } else {
                    pendingLivePeriod = period
                    requestLiveLocation.launch(needed.toTypedArray())
                }
            },
            onDismiss = { choosingLocation = false },
        )
    }
    val outgoing = viewModel.outgoing
    if (outgoing.files.isNotEmpty()) {
        FilesPreviewScreen(outgoing.files, title, onSend = outgoing::sendFiles, onDismiss = outgoing::cancelFiles)
    }
    if (outgoing.media.isNotEmpty()) {
        val quality by viewModel.sendQuality.collectAsStateWithLifecycle()
        MediaPreviewScreen(
            outgoing.media, title, quality, outgoing.sizes,
            onSend = outgoing::sendMedia, onDismiss = outgoing::cancelMedia,
        )
    }
    viewModel.menu?.let { menu ->
        MessageMenuOverlay(
            menu = menu,
            onReact = { viewModel.react(menu.message, it) },
            onReply = { viewModel.reply(menu.message) },
            onEdit = { viewModel.edit(menu.message) },
            onCopy = {
                val text = menu.message.text
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
                viewModel.closeMenu()
            },
            onForward = { viewModel.startForward(menu) },
            onSave = { viewModel.saveToDownloads(menu.message) },
            onSelect = { viewModel.select(menu) },
            onPin = { viewModel.togglePin(menu) },
            onDelete = { viewModel.askDelete(menu) },
            onDismiss = viewModel::closeMenu,
        )
    }
    viewModel.scheduling?.let { target ->
        SendLaterSheet(
            title = stringResource(if (target == ScheduleTarget.Draft) R.string.send_later else R.string.scheduled_change_time),
            onPick = viewModel::scheduleAt,
            onDismiss = viewModel::cancelScheduling,
        )
    }
    if (viewModel.scheduledOpen) {
        ScheduledSheet(
            viewModel.scheduled.collectAsStateWithLifecycle().value,
            onSendNow = viewModel::sendScheduledNow,
            onChangeTime = viewModel::askChangeTime,
            onEdit = viewModel::editScheduled,
            onDelete = viewModel::deleteScheduled,
            onDismiss = viewModel::closeScheduled,
        )
    }
    if (viewModel.pinned.listOpen) {
        PinnedSheet(viewModel.pinned.messages, onGoTo = viewModel.pinned::goTo, onDismiss = viewModel.pinned::closeList)
    }
    viewModel.deleting?.let { request ->
        DeleteDialog(request, onDelete = viewModel::delete, onDismiss = viewModel::cancelDelete)
    }
    viewModel.forwarding?.let { (_, targets) ->
        ForwardSheet(targets, onForward = viewModel::forward, onDismiss = viewModel::cancelForward)
    }
}

@Composable
private fun Divider(text: String, color: Color = Otm.InkSoft, line: Color = Otm.Line) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(text, style = OtmType.Secondary.copy(fontWeight = FontWeight.Medium), color = color)
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
}

@Composable
private fun ServiceNote(text: String) {
    Text(
        text,
        style = OtmType.Secondary,
        color = Otm.InkSoft,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
    )
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private const val LOAD_MORE_THRESHOLD = 10

/** The "newest messages" button shows once scrolled this many rows up. */
private const val JUMP_BUTTON_AFTER = 3

/** The top bar's three faces, faded between. */
private enum class BarMode { Chat, Searching, Selecting }

// Motion: quick enough not to wait for, slow enough to follow. Small things change in
// QUICK_MS; rows move with a soft spring; scrolling glides only the last stretch.
private const val QUICK_MS = 160
private const val DAY_LINGER_MS = 900L
private const val DAY_FADE_MS = 300
private const val ROW_FADE_IN_MS = 220
private const val ROW_FADE_OUT_MS = 140
private val ROW_PLACEMENT = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

/** Rows a glide covers at most; from further away, the list jumps that close first. */
private const val GLIDE_ROWS = 12

/**
 * Scrolls to row [index] smoothly: from far away it jumps to [GLIDE_ROWS] short of it first,
 * so the glide stays short and quick whatever the distance.
 */
internal suspend fun LazyListState.glideTo(index: Int, offset: Int = 0) {
    val first = firstVisibleItemIndex
    if (abs(index - first) > GLIDE_ROWS) scrollToItem(if (index > first) index - GLIDE_ROWS else index + GLIDE_ROWS)
    animateScrollToItem(index, offset)
}

/** Room above the "new since you left" line when opening there. */
private val OPEN_AT_TOP_MARGIN = 24.dp
private const val MAX_PICKED_MEDIA = 10
