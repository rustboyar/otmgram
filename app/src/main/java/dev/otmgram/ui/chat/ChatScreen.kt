package dev.otmgram.ui.chat

import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.R
import dev.otmgram.core.location.LiveShare
import dev.otmgram.core.media.RecorderState
import dev.otmgram.ui.openAppSettings
import dev.otmgram.ui.theme.Banner
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.Hairline
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmField
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.ScreenBar
import dev.otmgram.ui.theme.SoftButton
import dev.otmgram.ui.theme.paper
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenMedia: (chatId: Long, messageId: Long) -> Unit,
    onRecordVideoNote: (chatId: Long) -> Unit,
    onOpenMap: (chatId: Long, messageId: Long) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val liveShare by viewModel.liveShare.collectAsStateWithLifecycle()
    var attaching by rememberSaveable { mutableStateOf(false) }
    var choosingLocation by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val unreadMark = viewModel.unreadMark
    val rows = remember(messages, unreadMark) { buildRows(messages, unreadMark) }
    val selection = viewModel.selection.selected
    val selecting = viewModel.selection.isActive
    val search = viewModel.search
    val searching = search.isOpen
    val clipboard = LocalClipboardManager.current
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
    // Save the draft when the app goes to the background: the process may not come back.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.saveDraft() }
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
    var previousNewestId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(newest?.id) {
        val previousIndex = rows.indexOfFirst { it is ListRow.Message && it.message.id == previousNewestId }
        previousNewestId = newest?.id
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
                ?: rows.indexOfFirst { it is ListRow.Message && it.message.id == target }
            if (index < 0) return@LaunchedEffect
            listState.scrollToItem(index)
            // The list starts at the bottom: move the line up to the top, if there's room.
            val line = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (line != null) listState.scrollToItem(index, -(listState.layoutInfo.viewportSize.height - line.size - topMargin))
            viewModel.opened(listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long })
        }
    }
    viewModel.scrollTarget?.let { target ->
        LaunchedEffect(target, rows) {
            val index = rows.indexOfFirst { it is ListRow.Message && it.message.id == target }
            if (index >= 0) {
                // Center-ish: leave room above the match.
                listState.scrollToItem(index, scrollOffset = -listState.layoutInfo.viewportSize.height / 3)
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
        if (result.values.any { it }) viewModel.sendCurrentLocation() else refused(R.string.permission_location)
    }
    var pendingLivePeriod by rememberSaveable { mutableStateOf<Int?>(null) }
    val requestLiveLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val period = pendingLivePeriod
        // Only compares the name: fine on Android versions without that permission.
        @SuppressLint("InlinedApi")
        val notifications = Manifest.permission.POST_NOTIFICATIONS
        val located = result.filterKeys { it != notifications }.values.any { it } || hasLocationPermission(context)
        if (period != null && located) viewModel.startLiveLocation(period)
        if (!located) refused(R.string.permission_location)
        pendingLivePeriod = null
    }
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoice() else refused(R.string.permission_microphone)
    }

    Box(Modifier.fillMaxSize().paper()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            when {
                selecting -> SelectionBar(
                    count = selection.size,
                    onCancel = viewModel.selection::clear,
                    onCopy = {
                        clipboard.setText(AnnotatedString(viewModel.selection.text()))
                        viewModel.selection.clear()
                    },
                )
                searching -> FindBar(search.query.orEmpty(), search::type, search::close)
                else -> ScreenBar(
                    title = title,
                    subtitle = header?.subtitle,
                    onBack = onBack,
                    leading = header?.let { h ->
                        { ChatAvatar(viewModel.chatId, h.title, h.photoFileId, h.minithumbnail, size = 38.dp, savedMessages = h.isSavedMessages) }
                    },
                    actions = { IconBtn(OtmIcons.Search, stringResource(R.string.search_chat), search::open, tag = "search") },
                )
            }
            liveShare?.takeIf { it.chatId == viewModel.chatId }?.let { share ->
                LiveShareBanner(share, onStop = viewModel::stopLiveLocation)
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
                            MessageBubble(
                                message,
                                // Only the playing bubble changes as it plays.
                                audio = audio?.takeIf { it.fileId == (message.media as? MessageMedia.VoiceNote)?.fileId },
                                onLongClick = { viewModel.openMenu(message) },
                                onReactionClick = { viewModel.toggleReaction(message, it) },
                                onOpenMedia = {
                                    if (message.media is MessageMedia.Location) {
                                        onOpenMap(viewModel.chatId, message.id)
                                    } else {
                                        onOpenMedia(viewModel.chatId, message.id)
                                    }
                                },
                                onPlayVoice = { fileId, path -> viewModel.playVoice(message, fileId, path) },
                                onVideoNoteSound = { viewModel.markContentOpened(message) },
                                highlight = if (searching && message.id in resultIds) {
                                    Highlight(search.query.orEmpty(), current = message.id == currentMatch)
                                } else {
                                    null
                                },
                                selecting = selecting,
                                selected = message.id in selection,
                                onToggleSelected = { viewModel.selection.toggle(message) },
                                onRetry = { viewModel.retry(message) },
                                onDiscard = { viewModel.discard(message) },
                            )
                        }
                    }
                }
            }
            // Back to the newest message, once scrolled away from it.
            val scrolledAway by remember { derivedStateOf { listState.firstVisibleItemIndex > JUMP_BUTTON_AFTER } }
            if (scrolledAway) {
                IconBtn(
                    OtmIcons.ChevronDown,
                    stringResource(R.string.jump_to_newest),
                    { scope.launch { listState.animateScrollToItem(0) } },
                    Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    tint = Otm.SlateDeep,
                    background = Otm.Card,
                    tag = "jumpToNewest",
                )
            }
            }

            val current = recording
            when {
                selecting -> SelectionActions(onForward = viewModel::forwardSelected, onDelete = viewModel::askDeleteSelected)
                searching -> FindStepper(results, search.index, search.query.orEmpty(), onStep = search::step)
                current != null -> RecordingBar(current, onCancel = viewModel::cancelVoice, onSend = viewModel::sendVoice)
                else -> InputBar(
                    text = viewModel.text,
                    onTextChange = viewModel::type,
                    composer = viewModel.composer,
                    chatTitle = title,
                    stickersOpen = viewModel.stickers.isOpen,
                    onCancel = viewModel::cancelComposer,
                    onSend = viewModel::send,
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
            onDismiss = { attaching = false },
        )
    }
    if (choosingLocation) {
        LaunchedEffect(Unit) { if (hasLocationPermission(context)) viewModel.locateMe() }
        LocationSheet(
            chatTitle = title,
            here = viewModel.myLocation,
            onSendCurrent = {
                choosingLocation = false
                if (hasLocationPermission(context)) {
                    viewModel.sendCurrentLocation()
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
                    viewModel.startLiveLocation(period)
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
        val compress by viewModel.compressByDefault.collectAsStateWithLifecycle()
        MediaPreviewScreen(
            outgoing.media, title, compress, outgoing.sizes,
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
                clipboard.setText(AnnotatedString(menu.message.text))
                viewModel.closeMenu()
            },
            onForward = { viewModel.startForward(menu.message) },
            onSave = { viewModel.saveToDownloads(menu.message) },
            onSelect = { viewModel.select(menu.message) },
            onDelete = { viewModel.askDelete(menu) },
            onDismiss = viewModel::closeMenu,
        )
    }
    viewModel.deleting?.let { request ->
        DeleteDialog(request, onDelete = viewModel::delete, onDismiss = viewModel::cancelDelete)
    }
    viewModel.forwarding?.let { (_, targets) ->
        ForwardSheet(targets, onForward = viewModel::forward, onDismiss = viewModel::cancelForward)
    }
}

@Composable
private fun ScreenBarRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
        Hairline()
    }
}

@Composable
private fun SelectionBar(count: Int, onCancel: () -> Unit, onCopy: () -> Unit) {
    ScreenBarRow {
        IconBtn(OtmIcons.Close, stringResource(R.string.selection_cancel), onCancel, tag = "cancelSelection")
        Text(pluralStringResource(R.plurals.selected_count, count, count), style = OtmType.BarTitle, color = Otm.Ink, modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("selectedCount"))
        IconBtn(OtmIcons.Copy, stringResource(R.string.action_copy), onCopy, tint = Color(0xFF56626B), tag = "copySelected")
    }
}

@Composable
private fun SelectionActions(onForward: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.6f)).navigationBarsPadding()) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(stringResource(R.string.action_forward), onForward, Modifier.weight(1f).testTag("forwardSelected"), icon = OtmIcons.Forward, height = 52.dp)
            SoftButton(stringResource(R.string.action_delete), onDelete, Modifier.weight(1f).testTag("deleteSelected"), icon = OtmIcons.Trash, background = Otm.ErrorSoft, color = Otm.Error, height = 52.dp)
        }
    }
}

@Composable
private fun FindBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    ScreenBarRow {
        IconBtn(OtmIcons.Back, stringResource(R.string.search_close), onClose, tag = "closeSearch")
        OtmField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.search_chat),
            textStyle = OtmType.Body.copy(fontSize = OtmType.Name.fontSize),
            shape = RoundedCornerShape(22.dp),
            height = 44.dp,
            outlined = false,
            trailing = if (query.isEmpty()) null else {
                { IconBtn(OtmIcons.Close, stringResource(R.string.search_clear), { onQueryChange("") }, tint = Otm.InkSoft, size = 40.dp, iconSize = 20.dp) }
            },
            modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("query"),
        )
    }
}

/** "3 of 4" and arrows to step through matches, in the input bar's place. */
@Composable
private fun FindStepper(results: List<SearchResult>?, index: Int, query: String, onStep: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.6f)).navigationBarsPadding()) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).testTag("matches")) {
                Text(
                    when {
                        query.isBlank() -> stringResource(R.string.search_type)
                        results == null -> stringResource(R.string.search_searching)
                        results.isEmpty() -> stringResource(R.string.search_none)
                        // Counted from the oldest, as they read top to bottom.
                        else -> stringResource(R.string.search_position, results.size - index, results.size)
                    },
                    style = OtmType.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = Otm.Ink,
                )
            }
            val any = !results.isNullOrEmpty()
            IconBtn(OtmIcons.ChevronUp, stringResource(R.string.search_earlier), { onStep(1) }, tint = Otm.SlateDeep, background = Otm.Haze, enabled = any && index < results!!.lastIndex, tag = "olderMatch")
            Box(Modifier.size(8.dp))
            IconBtn(OtmIcons.ChevronDown, stringResource(R.string.search_later), { onStep(-1) }, tint = Otm.SlateDeep, background = Otm.Haze, enabled = any && index > 0, tag = "newerMatch")
        }
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

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    composer: Composer,
    chatTitle: String,
    stickersOpen: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onStickers: () -> Unit,
    onAttach: () -> Unit,
    onRecordVideoNote: () -> Unit,
    focus: FocusRequester,
) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.6f)).then(if (stickersOpen) Modifier else Modifier.navigationBarsPadding())) {
        Hairline()
        val banner = when (composer) {
            Composer.New -> null
            is Composer.Reply -> Triple(
                OtmIcons.Reply,
                stringResource(R.string.reply_to, composer.message.senderName ?: if (composer.message.isOutgoing) stringResource(R.string.reply_to_yourself) else chatTitle),
                composer.message.text.ifEmpty { composer.message.media?.label().orEmpty() },
            )
            is Composer.Edit -> Triple(OtmIcons.Edit, stringResource(R.string.edit_message), composer.message.text)
        }
        banner?.let { (icon, label, quote) ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(icon, null, tint = Otm.Slate, modifier = Modifier.size(22.dp))
                ReplyQuote(ReplyPreview(label, quote), Modifier.weight(1f).padding(start = 10.dp))
                IconBtn(
                    OtmIcons.Close, stringResource(R.string.cancel),
                    onCancel,
                    tint = Otm.InkSoft,
                    tag = "cancelComposer",
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconBtn(OtmIcons.Attach, stringResource(R.string.attach), onAttach, tint = Color(0xFF56626B), tag = "attach")
            OtmField(
                value = text,
                onValueChange = onTextChange,
                placeholder = if (chatTitle.isEmpty()) stringResource(R.string.composer_message) else stringResource(R.string.composer_write_to, chatTitle),
                textStyle = OtmType.Body,
                singleLine = false,
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                height = 44.dp,
                outlined = false,
                trailing = {
                    IconBtn(
                        if (stickersOpen) OtmIcons.Keyboard else OtmIcons.Smile,
                        if (stickersOpen) stringResource(R.string.keyboard) else stringResource(R.string.stickers),
                        onStickers,
                        tint = if (stickersOpen) Otm.SlateDeep else Color(0xFF56626B),
                        size = 40.dp,
                        tag = "stickers",
                    )
                },
                modifier = Modifier.weight(1f).border(1.dp, Otm.Line, RoundedCornerShape(22.dp)).focusRequester(focus).testTag("input"),
            )
            Box(Modifier.size(4.dp))
            if (text.isBlank() && composer !is Composer.Edit) {
                IconBtn(OtmIcons.CircleCamera, stringResource(R.string.record_video_note), onRecordVideoNote, tint = Otm.SlateDeep, background = Color(0xFFDCE4E9), tag = "recordVideoNote")
            } else {
                // Editing a caption may leave it empty.
                val canSend = text.isNotBlank() || (composer is Composer.Edit && composer.message.media != null)
                IconBtn(
                    if (composer is Composer.Edit) OtmIcons.Check else OtmIcons.Send,
                    if (composer is Composer.Edit) stringResource(R.string.save) else stringResource(R.string.send),
                    onSend,
                    tint = Color.White,
                    background = if (canSend) Otm.Slate else Otm.RidgeLight,
                    enabled = canSend,
                    tag = "send",
                )
            }
        }
    }
}

@Composable
private fun LiveShareBanner(share: LiveShare, onStop: () -> Unit) {
    val left = ((share.endsAt - rememberNow()) / 60).coerceAtLeast(0)
    Banner(
        OtmIcons.Pin,
        stringResource(R.string.live_sharing),
        Modifier.testTag("liveShareBanner"),
        subtitle = when {
            left >= 120 -> pluralStringResource(R.plurals.live_hours_left, left / 60, left / 60)
            left >= 1 -> stringResource(R.string.live_minutes_left, left)
            else -> stringResource(R.string.live_ending)
        },
        action = { SoftButton(stringResource(R.string.live_stop_short), onStop, Modifier.testTag("stopLiveLocation"), height = 36.dp) },
    )
}

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Discard, a running timer with a live waveform, send. */
@Composable
private fun RecordingBar(state: RecorderState, onCancel: () -> Unit, onSend: () -> Unit) {
    val levels = remember { mutableStateListOf<Int>() }
    LaunchedEffect(state.elapsedMs) {
        levels.add((state.level * 31).toInt().coerceIn(0, 31))
        if (levels.size > LIVE_BARS) levels.removeAt(0)
    }
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.6f)).navigationBarsPadding()) {
        Hairline()
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp).testTag("recording"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconBtn(OtmIcons.Trash, stringResource(R.string.voice_discard), onCancel, tint = Otm.Error, tag = "cancelRecording")
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Otm.Card)
                    .border(1.dp, Otm.Line, RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Canvas(Modifier.size(10.dp)) {
                    drawCircle(Otm.Rose.copy(alpha = 0.25f), radius = size.minDimension)
                    drawCircle(Otm.Rose)
                }
                Text(formatDuration((state.elapsedMs / 1000).toInt()), style = OtmType.Body, color = Otm.Ink)
                Waveform(levels.toList(), progress = 1f, played = Otm.Ridge, rest = Otm.Ridge, modifier = Modifier.weight(1f).height(24.dp), bars = LIVE_BARS)
            }
            IconBtn(OtmIcons.Send, stringResource(R.string.voice_send), onSend, tint = Color.White, background = Otm.Slate, tag = "sendRecording")
        }
    }
}

fun formatTime(unixTime: Int): String =
    Instant.ofEpochSecond(unixTime.toLong()).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private const val LOAD_MORE_THRESHOLD = 10

/** The "newest messages" button shows once scrolled this many rows up. */
private const val JUMP_BUTTON_AFTER = 3

/** Room above the "new since you left" line when opening there. */
private val OPEN_AT_TOP_MARGIN = 24.dp
private const val MAX_PICKED_MEDIA = 10
private const val LIVE_BARS = 24
