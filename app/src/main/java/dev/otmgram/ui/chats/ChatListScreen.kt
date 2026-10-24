package dev.otmgram.ui.chats

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.R
import dev.otmgram.ui.auth.LoadingScreen
import dev.otmgram.sync.MessagesNotification
import dev.otmgram.ui.highlightMatches
import dev.otmgram.ui.openNotificationSettings
import dev.otmgram.ui.theme.Banner
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.CountPill
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmField
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.SectionLabel
import dev.otmgram.ui.theme.SkyBand
import dev.otmgram.ui.theme.TextAction
import dev.otmgram.ui.theme.paper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun ChatListScreen(
    onOpenChat: (Long) -> Unit,
    onOpenSettings: () -> Unit,
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

    Column(Modifier.fillMaxSize().paper()) {
        if (searching) {
            SkyBand(folderName, content = { SearchField(query, viewModel::setQuery, closeSearch) })
        } else {
            SkyBand(
                folderName,
                actions = {
                    IconBtn(OtmIcons.Search, stringResource(R.string.find_chat), { searching = true }, tag = "search")
                    IconBtn(OtmIcons.More, stringResource(R.string.settings), onOpenSettings, tag = "menu")
                },
            )
        }
        connection?.let { Banner(OtmIcons.Offline, it, Modifier.testTag("connection")) }
        NotificationsCheck(ask = askNotifications, notifies = notifies, onAsked = viewModel::notificationsAsked)

        val list = chats
        val searchText = query.trim()
        when {
            list == null -> LoadingScreen()
            list.isEmpty() -> Empty(if (searchText.isEmpty()) stringResource(R.string.chats_empty) else stringResource(R.string.chats_no_match))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("chats"),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                if (searching && searchText.isNotEmpty()) {
                    item(key = "count") {
                        SectionLabel(pluralStringResource(R.plurals.chat_count, list.size, list.size), Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp))
                    }
                }
                items(list, key = { it.id }) { chat ->
                    ChatRow(chat, highlight = if (searching) searchText else "", onClick = { onOpenChat(chat.id) })
                }
                if (searching) {
                    item(key = "hint") {
                        Text(
                            stringResource(R.string.chats_search_hint),
                            style = OtmType.Secondary,
                            color = Otm.InkSoft,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 28.dp),
                        )
                    }
                }
            }
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
        placeholder = stringResource(R.string.find_chat),
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
private fun ChatRow(chat: ChatItem, highlight: String, onClick: () -> Unit) {
    val unread = chat.unreadCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
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
                    Text(formatDate(it), style = OtmType.Secondary, color = Otm.InkSoft, modifier = Modifier.padding(start = 8.dp))
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
                if (unread) CountPill(chat.unreadCount, quiet = chat.isMuted, Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** Time for today, weekday within the last week, date otherwise. */
private fun formatDate(unixTime: Int): String {
    val dateTime = Instant.ofEpochSecond(unixTime.toLong()).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when {
        dateTime.toLocalDate() == today ->
            dateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        dateTime.toLocalDate().isAfter(today.minusDays(7)) ->
            dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        else -> dateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
    }
}
