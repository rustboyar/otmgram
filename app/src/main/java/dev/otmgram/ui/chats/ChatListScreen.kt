package dev.otmgram.ui.chats

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.R
import dev.otmgram.sync.MessagesNotification
import dev.otmgram.ui.format.chatListDate
import dev.otmgram.ui.highlightMatches
import dev.otmgram.ui.openNotificationSettings
import dev.otmgram.ui.theme.Banner
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.CountPill
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.LoadingScreen
import dev.otmgram.ui.theme.MentionPill
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmDialog
import dev.otmgram.ui.theme.OtmField
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmSheet
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.SectionLabel
import dev.otmgram.ui.theme.SkyBand
import dev.otmgram.ui.theme.TextAction
import dev.otmgram.ui.theme.paper
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ChatListScreen(
    onOpenChat: (Long) -> Unit,
    /** A message found: its chat, opened at it. */
    onOpenMessage: (chatId: Long, messageId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    val closeSearch = {
        searching = false
        viewModel.setQuery("")
    }
    val askNotifications by viewModel.askNotifications.collectAsStateWithLifecycle()
    val notifies by viewModel.notifies.collectAsStateWithLifecycle()
    BackHandler(enabled = searching, onBack = closeSearch)
    var actionsFor by remember { mutableStateOf<ChatItem?>(null) }

    actionsFor?.let { chat ->
        ChatActions(
            chat,
            onPin = { pinned ->
                actionsFor = null
                viewModel.setPinned(chat, pinned)
            },
            onMute = { muted ->
                actionsFor = null
                viewModel.setMuted(chat, muted)
            },
            onDismiss = { actionsFor = null },
        )
    }
    viewModel.actionError?.let { error ->
        OtmDialog(
            title = stringResource(error.title),
            text = error.message,
            onDismiss = viewModel::actionErrorShown,
            confirm = {},
            dismissLabel = stringResource(R.string.ok),
        )
    }

    Column(modifier.fillMaxSize().paper()) {
        if (searching) {
            SkyBand(folderName, content = { SearchField(query, viewModel::setQuery, closeSearch) })
        } else {
            SkyBand(
                folderName,
                actions = {
                    IconBtn(OtmIcons.Search, stringResource(R.string.search_messages), { searching = true }, tag = "search")
                    IconBtn(OtmIcons.More, stringResource(R.string.settings), onOpenSettings, tag = "menu")
                },
            )
        }
        // Slides in and out rather than pushing the list at once; keeps its text while leaving.
        var shownConnection by remember { mutableStateOf("") }
        connection?.let { shownConnection = it }
        AnimatedVisibility(connection != null, enter = expandVertically(), exit = shrinkVertically()) {
            Banner(OtmIcons.Offline, shownConnection, Modifier.testTag("connection"))
        }
        NotificationsCheck(ask = askNotifications, notifies = notifies, onAsked = viewModel::notificationsAsked)

        val list = chats
        val searchText = query.trim()
        val listState = rememberLazyListState()
        // A chat moving to the top stays in sight: the list would otherwise keep showing the
        // chat that was first, with the new one above it.
        // Runs before the new order is laid out, while the position is still the old one.
        val firstId = list?.firstOrNull()?.id
        val shownFirstId = remember { mutableStateOf(firstId) }
        SideEffect {
            if (firstId == shownFirstId.value) return@SideEffect
            shownFirstId.value = firstId
            if (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) listState.requestScrollToItem(0)
        }
        val found = viewModel.found
        when {
            searching && found != null -> FoundList(found, onOpen = onOpenMessage, onNearEnd = viewModel::loadMoreFound)
            list == null -> LoadingScreen()
            list.isEmpty() -> Empty(stringResource(R.string.chats_empty))
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("chats"),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                items(list, key = { it.id }) { chat ->
                    // Slides to its new place when the order changes, rather than the rows
                    // swapping at once.
                    ChatRow(
                        chat,
                        highlight = "",
                        onClick = { onOpenChat(chat.id) },
                        onLongClick = { actionsFor = chat },
                        Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/** Messages found in the folder's chats, newest first; more load near the end. */
@Composable
private fun FoundList(found: FoundMessages, onOpen: (chatId: Long, messageId: Long) -> Unit, onNearEnd: () -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(state, found.query) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 5 }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) onNearEnd() }
    }
    if (found.messages.isEmpty() && !found.loading) {
        Empty(stringResource(R.string.search_messages_none))
        return
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().testTag("found"),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
        item(key = "label") { SectionLabel(stringResource(R.string.search_messages_label), Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp)) }
        items(found.messages, key = { "${it.chat.id}/${it.messageId}" }) { message ->
            FoundRow(message, found.query) { onOpen(message.chat.id, message.messageId) }
        }
        if (found.loading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Otm.Slate, strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun FoundRow(message: FoundMessage, query: String, onClick: () -> Unit) {
    val chat = message.chat
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("found-${message.messageId}")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ChatAvatar(chat.id, chat.title, chat.photoFileId, chat.photoMinithumbnail, savedMessages = chat.isSavedMessages)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chat.title, style = OtmType.Name, color = Otm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(chatListDate(message.date), style = OtmType.Secondary, color = Otm.InkSoft, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                highlightMatches(message.text, query, Otm.Mark),
                style = OtmType.Preview,
                color = Otm.InkBody,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, style = OtmType.Body, color = Otm.InkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 32.dp))
    }
}

/**
 * Asks once for the permission to notify about new messages (Android 13+). While
 * notifications can't show, though the app checks for messages, a banner leads to the
 * system settings to turn them on.
 */
@Composable
private fun NotificationsCheck(ask: Boolean?, notifies: Boolean, onAsked: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(MessagesNotification.allowed(context)) }
    var asking by remember { mutableStateOf(false) }
    // Back from the system settings, or from the shade.
    LifecycleResumeEffect(Unit) {
        allowed = MessagesNotification.allowed(context)
        onPauseOrDispose {}
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asking = false
        allowed = MessagesNotification.allowed(context)
    }
    LaunchedEffect(ask) {
        if (ask != true) return@LaunchedEffect
        onAsked()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !allowed) {
            asking = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if (notifies && !allowed && !asking && ask == false) {
        Banner(
            OtmIcons.Alert,
            stringResource(R.string.notifications_off),
            Modifier.testTag("notificationsOff"),
            action = { TextAction(stringResource(R.string.notifications_turn_on), context::openNotificationSettings) },
        )
    }
}

/** The search field takes the folder name's place in the sky. */
@Composable
private fun RowScope.SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    IconBtn(OtmIcons.Back, stringResource(R.string.search_close), onClose, Modifier.padding(end = 4.dp), tag = "closeSearch")
    OtmField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.search_messages),
        textStyle = OtmType.Body.copy(fontSize = OtmType.Name.fontSize),
        shape = RoundedCornerShape(23.dp),
        height = 46.dp,
        background = Otm.Card.copy(alpha = 0.85f),
        outlined = false,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        leading = { Icon(OtmIcons.Search, null, tint = Otm.InkSoft, modifier = Modifier.size(20.dp)) },
        trailing = if (query.isEmpty()) null else {
            { IconBtn(OtmIcons.Close, stringResource(R.string.search_clear), { onQueryChange("") }, tint = Otm.InkSoft, size = 40.dp, iconSize = 20.dp) }
        },
        modifier = Modifier.weight(1f).padding(end = 4.dp).focusRequester(focusRequester).testTag("query"),
    )
}

@Composable
private fun ChatRow(chat: ChatItem, highlight: String, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val unread = chat.unreadCount > 0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .testTag("chat-${chat.title}")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ChatAvatar(chat.id, chat.title, chat.photoFileId, chat.photoMinithumbnail, savedMessages = chat.isSavedMessages)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        highlightMatches(chat.title, highlight, Otm.Mark),
                        style = OtmType.Name,
                        color = Otm.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (chat.isMuted) {
                        Icon(OtmIcons.Muted, stringResource(R.string.muted), tint = Otm.InkFaint, modifier = Modifier.padding(start = 5.dp).size(15.dp))
                    }
                }
                chat.date?.let {
                    Text(chatListDate(it), style = OtmType.Secondary, color = Otm.InkSoft, modifier = Modifier.padding(start = 8.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.draft?.let { draft ->
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Otm.Error)) { append(stringResource(R.string.draft_label)) }
                            append(" ")
                            append(draft)
                        }
                    } ?: AnnotatedString(chat.preview),
                    style = OtmType.Preview,
                    color = if (unread && !chat.isMuted) Otm.InkBody else Otm.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unreadMentions > 0) MentionPill(stringResource(R.string.mentions_you), Modifier.padding(start = 8.dp).testTag("mention"))
                if (unread) CountPill(chat.unreadCount, quiet = chat.isMuted, Modifier.padding(start = 6.dp))
                if (chat.isPinned && !unread && chat.unreadMentions == 0) {
                    Icon(OtmIcons.Pushpin, stringResource(R.string.chat_pinned), tint = Otm.InkFaint, modifier = Modifier.padding(start = 6.dp).size(16.dp).testTag("pinnedChat"))
                }
            }
        }
    }
}

/** A long press on a chat: pin it to the top of the folder or unpin it; mute or unmute it. */
@Composable
private fun ChatActions(chat: ChatItem, onPin: (Boolean) -> Unit, onMute: (Boolean) -> Unit, onDismiss: () -> Unit) {
    OtmSheet(onDismiss) {
        Text(
            chat.title,
            style = OtmType.BarTitle.copy(fontSize = 20.sp),
            color = Otm.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        ChatAction(
            if (chat.isPinned) OtmIcons.Unpin else OtmIcons.Pushpin,
            stringResource(if (chat.isPinned) R.string.chat_unpin else R.string.chat_pin),
            if (chat.isPinned) "unpinChat" else "pinChat",
        ) { onPin(!chat.isPinned) }
        // Saved Messages never notifies.
        if (!chat.isSavedMessages) {
            ChatAction(
                if (chat.isMuted) OtmIcons.Unmuted else OtmIcons.Muted,
                stringResource(if (chat.isMuted) R.string.chat_unmute else R.string.chat_mute),
                if (chat.isMuted) "unmuteChat" else "muteChat",
            ) { onMute(!chat.isMuted) }
        }
    }
}

@Composable
private fun ChatAction(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = Otm.Ink, modifier = Modifier.size(22.dp))
        Text(label, style = OtmType.Body, color = Otm.Ink)
    }
}
