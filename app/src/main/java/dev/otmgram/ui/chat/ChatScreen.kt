package dev.otmgram.ui.chat

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
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.core.location.LiveShare
import dev.otmgram.core.media.RecorderState
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
    // Mark messages on screen as read.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
            .distinctUntilChanged()
            .collect(viewModel::onMessagesVisible)
    }
    // Follow new messages if the previous newest one was at the bottom (several can arrive
    // at once, e.g. an album), and always after sending.
    val newest = messages.firstOrNull()
    var previousNewestId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(newest?.id) {
        val previousIndex = rows.indexOfFirst { it is ListRow.Message && it.message.id == previousNewestId }
        previousNewestId = newest?.id
        if (previousIndex < 0 || listState.firstVisibleItemIndex <= previousIndex || newest?.isOutgoing == true) {
            listState.animateScrollToItem(0)
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
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), viewModel.outgoing::pickFiles)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_MEDIA), viewModel.outgoing::pickMedia)
    val requestLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) viewModel.sendCurrentLocation()
    }
    var pendingLivePeriod by rememberSaveable { mutableStateOf<Int?>(null) }
    val requestLiveLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val period = pendingLivePeriod
        // Only compares the name: fine on Android versions without that permission.
        @SuppressLint("InlinedApi")
        val notifications = Manifest.permission.POST_NOTIFICATIONS
        val located = result.filterKeys { it != notifications }.values.any { it } || hasLocationPermission(context)
        if (period != null && located) viewModel.startLiveLocation(period)
        pendingLivePeriod = null
    }
    val requestMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startVoice()
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
                    actions = { IconBtn(OtmIcons.Search, "Find in this chat", search::open, tag = "search") },
                )
            }
            liveShare?.takeIf { it.chatId == viewModel.chatId }?.let { share ->
                LiveShareBanner(share, onStop = viewModel::stopLiveLocation)
            }
            if (viewModel.outgoing.preparing) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("preparing"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Preparing media…", style = OtmType.Secondary, color = Otm.InkSoft)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Otm.Slate, trackColor = Otm.Haze)
                }
            }

            val results = search.results
            val resultIds = remember(results) { results?.map { it.messageId }?.toSet().orEmpty() }
            val currentMatch = results?.getOrNull(search.index)?.messageId
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("messages"),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ListRow.Day -> Divider(dayLabel(row.date))
                        is ListRow.Unread -> Divider(
                            if (row.count == 1) "1 new since you left" else "${row.count} new since you left",
                            color = Otm.Slate,
                            line = Otm.RidgeLight,
                        )
                        is ListRow.Message -> if (row.message.isService) {
                            ServiceNote(row.message.text)
                        } else {
                            val message = row.message
                            MessageBubble(
                                message,
                                audio = audio,
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

            val current = recording
            when {
                selecting -> SelectionActions(onForward = viewModel::forwardSelected, onDelete = viewModel::askDeleteSelected)
                searching -> FindStepper(results, search.index, search.query.orEmpty(), onStep = search::step)
                current != null -> RecordingBar(current, onCancel = viewModel::cancelVoice, onSend = viewModel::sendVoice)
                else -> InputBar(
                    composer = viewModel.composer,
                    chatTitle = title,
                    stickersOpen = viewModel.stickers.isOpen,
                    onCancel = viewModel::cancelComposer,
                    onSend = viewModel::send,
                    onStickers = {
                        keyboard?.hide()
                        viewModel.stickers.toggle()
                    },
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp)) {
            Snackbar(it, containerColor = Otm.Ink, contentColor = Otm.Paper, shape = RoundedCornerShape(14.dp))
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
        IconBtn(OtmIcons.Close, "Cancel selection", onCancel, tag = "cancelSelection")
        Text(if (count == 1) "1 selected" else "$count selected", style = OtmType.BarTitle, color = Otm.Ink, modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("selectedCount"))
        IconBtn(OtmIcons.Copy, "Copy text", onCopy, tint = Color(0xFF56626B), tag = "copySelected")
    }
}

@Composable
private fun SelectionActions(onForward: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.6f)).navigationBarsPadding()) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Forward", onForward, Modifier.weight(1f).testTag("forwardSelected"), icon = OtmIcons.Forward, height = 52.dp)
            SoftButton("Delete", onDelete, Modifier.weight(1f).testTag("deleteSelected"), icon = OtmIcons.Trash, background = Otm.ErrorSoft, color = Otm.Error, height = 52.dp)
        }
    }
}

@Composable
private fun FindBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    ScreenBarRow {
        IconBtn(OtmIcons.Back, "Close search", onClose, tag = "closeSearch")
        OtmField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = "Find in this chat",
            textStyle = OtmType.Body.copy(fontSize = OtmType.Name.fontSize),
            shape = RoundedCornerShape(22.dp),
            height = 44.dp,
            outlined = false,
            trailing = if (query.isEmpty()) null else {
                { IconBtn(OtmIcons.Close, "Clear", { onQueryChange("") }, tint = Otm.InkSoft, size = 40.dp, iconSize = 20.dp) }
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
                        query.isBlank() -> "Type to search"
                        results == null -> "Searching…"
                        results.isEmpty() -> "No matches"
                        // Counted from the oldest, as they read top to bottom.
                        else -> "${results.size - index} of ${results.size}"
                    },
                    style = OtmType.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = Otm.Ink,
                )
            }
            val any = !results.isNullOrEmpty()
            IconBtn(OtmIcons.ChevronUp, "Earlier match", { onStep(1) }, tint = Otm.SlateDeep, background = Otm.Haze, enabled = any && index < results!!.lastIndex, tag = "olderMatch")
            Box(Modifier.size(8.dp))
            IconBtn(OtmIcons.ChevronDown, "Later match", { onStep(-1) }, tint = Otm.SlateDeep, background = Otm.Haze, enabled = any && index > 0, tag = "newerMatch")
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
    composer: Composer,
    chatTitle: String,
    stickersOpen: Boolean,
    onCancel: () -> Unit,
    onSend: (String) -> Unit,
    onStickers: () -> Unit,
    onAttach: () -> Unit,
    onRecordVideoNote: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    // Editing starts from the message text.
    LaunchedEffect(composer) {
        if (composer is Composer.Edit) text = composer.message.text
    }
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.6f)).then(if (stickersOpen) Modifier else Modifier.navigationBarsPadding())) {
        Hairline()
        val banner = when (composer) {
            Composer.New -> null
            is Composer.Reply -> Triple(
                OtmIcons.Reply,
                "Reply to " + (composer.message.senderName ?: if (composer.message.isOutgoing) "yourself" else chatTitle),
                composer.message.text.ifEmpty { composer.message.media?.label().orEmpty() },
            )
            is Composer.Edit -> Triple(OtmIcons.Edit, "Edit message", composer.message.text)
        }
        banner?.let { (icon, label, quote) ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(icon, null, tint = Otm.Slate, modifier = Modifier.size(22.dp))
                ReplyQuote(ReplyPreview(label, quote), Modifier.weight(1f).padding(start = 10.dp))
                IconBtn(
                    OtmIcons.Close, "Cancel",
                    {
                        if (composer is Composer.Edit) text = ""
                        onCancel()
                    },
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
            IconBtn(OtmIcons.Attach, "Attach", onAttach, tint = Color(0xFF56626B), tag = "attach")
            OtmField(
                value = text,
                onValueChange = { text = it },
                placeholder = if (chatTitle.isEmpty()) "Message" else "Write to $chatTitle",
                textStyle = OtmType.Body,
                singleLine = false,
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                height = 44.dp,
                outlined = false,
                trailing = {
                    IconBtn(
                        if (stickersOpen) OtmIcons.Keyboard else OtmIcons.Smile,
                        if (stickersOpen) "Keyboard" else "Stickers",
                        onStickers,
                        tint = if (stickersOpen) Otm.SlateDeep else Color(0xFF56626B),
                        size = 40.dp,
                        tag = "stickers",
                    )
                },
                modifier = Modifier.weight(1f).border(1.dp, Otm.Line, RoundedCornerShape(22.dp)).testTag("input"),
            )
            Box(Modifier.size(4.dp))
            if (text.isBlank() && composer !is Composer.Edit) {
                IconBtn(OtmIcons.CircleCamera, "Record video circle", onRecordVideoNote, tint = Otm.SlateDeep, background = Color(0xFFDCE4E9), tag = "recordVideoNote")
            } else {
                // Editing a caption may leave it empty.
                val canSend = text.isNotBlank() || (composer is Composer.Edit && composer.message.media != null)
                IconBtn(
                    if (composer is Composer.Edit) OtmIcons.Check else OtmIcons.Send,
                    if (composer is Composer.Edit) "Save" else "Send",
                    {
                        onSend(text)
                        text = ""
                    },
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
        "Sharing your live location",
        Modifier.testTag("liveShareBanner"),
        subtitle = when {
            left >= 120 -> "${left / 60} hours left"
            left >= 1 -> "$left min left"
            else -> "ending"
        },
        action = { SoftButton("Stop", onStop, Modifier.testTag("stopLiveLocation"), height = 36.dp) },
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
            IconBtn(OtmIcons.Trash, "Discard recording", onCancel, tint = Otm.Error, tag = "cancelRecording")
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
            IconBtn(OtmIcons.Send, "Send voice message", onSend, tint = Color.White, background = Otm.Slate, tag = "sendRecording")
        }
    }
}

fun formatTime(unixTime: Int): String =
    Instant.ofEpochSecond(unixTime.toLong()).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private const val LOAD_MORE_THRESHOLD = 10
private const val MAX_PICKED_MEDIA = 10
private const val LIVE_BARS = 24
