package dev.otmgram.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.ui.auth.LoadingScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: () -> Unit, viewModel: ChatViewModel = hiltViewModel()) {
    val header by viewModel.header.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val rows = remember(messages) { buildRows(messages) }

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
    // Follow new messages while at the bottom.
    val newestId = messages.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    viewModel.scrollTarget?.let { target ->
        LaunchedEffect(target, rows) {
            val index = rows.indexOfFirst { it is ListRow.Message && it.message.id == target }
            if (index >= 0) {
                listState.scrollToItem(index)
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

    Scaffold(
        topBar = {
            val query = viewModel.searchQuery
            if (query != null) {
                SearchBar(query, onQueryChange = viewModel::setSearch, onClose = viewModel::closeSearch)
            } else {
                TopAppBar(
                    title = { Text(header?.title.orEmpty()) },
                    navigationIcon = {
                        TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("←") }
                    },
                    actions = {
                        TextButton(onClick = viewModel::startSearch, modifier = Modifier.testTag("search")) {
                            Text("Search")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The scaffold padding already includes the navigation bar, which the keyboard inset overlaps.
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (viewModel.searchQuery != null) {
                SearchResults(viewModel.searchQuery.orEmpty(), viewModel.searchResults, onClick = viewModel::jumpTo)
                return@Column
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("messages"),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ListRow.Day -> DayHeader(row.date)
                        is ListRow.Message -> if (row.message.isService) {
                            ServiceNote(row.message.text)
                        } else {
                            MessageBubble(
                                row.message,
                                onLongClick = { viewModel.openMenu(row.message) },
                                onReactionClick = { viewModel.toggleReaction(row.message, it) },
                            )
                        }
                    }
                }
            }
            InputBar(
                composer = viewModel.composer,
                onCancel = viewModel::cancelComposer,
                onSend = viewModel::send,
            )
        }
    }

    viewModel.menu?.let { menu ->
        MessageMenuDialog(
            menu = menu,
            onReact = { viewModel.react(menu.message, it) },
            onReply = { viewModel.reply(menu.message) },
            onForward = { viewModel.startForward(menu.message) },
            onEdit = { viewModel.edit(menu.message) },
            onDelete = { viewModel.askDelete(menu) },
            onDismiss = viewModel::closeMenu,
        )
    }
    viewModel.deleting?.let { menu ->
        DeleteDialog(menu, onDelete = viewModel::delete, onDismiss = viewModel::cancelDelete)
    }
    viewModel.forwarding?.let { (_, targets) ->
        ForwardDialog(targets, onForward = viewModel::forward, onDismiss = viewModel::cancelForward)
    }
}

/** Rows of the message list, newest first: messages and the day headers above them. */
private sealed interface ListRow {
    val key: Any

    data class Message(val message: MessageItem) : ListRow {
        override val key get() = message.id
    }

    data class Day(val date: Int, override val key: String) : ListRow
}

private fun buildRows(messages: List<MessageItem>): List<ListRow> = buildList {
    messages.forEachIndexed { index, message ->
        add(ListRow.Message(message))
        // reverseLayout: the row after a message is shown above it.
        val older = messages.getOrNull(index + 1)
        if (older == null || day(older.date) != day(message.date)) add(ListRow.Day(message.date, "day-${message.id}"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search messages") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("query"),
            )
        },
        actions = {
            TextButton(onClick = onClose, modifier = Modifier.testTag("closeSearch")) { Text("Close") }
        },
    )
}

@Composable
private fun SearchResults(query: String, results: List<SearchResult>?, onClick: (Long) -> Unit) {
    when {
        query.isBlank() -> Unit
        results == null -> LoadingScreen()
        results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No messages found")
        }
        else -> LazyColumn(Modifier.fillMaxSize().testTag("results")) {
            items(results, key = { it.messageId }) { result ->
                ListItem(
                    overlineContent = result.senderName?.let { { Text(it) } },
                    headlineContent = { Text(result.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Text(formatTime(result.date), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.clickable { onClick(result.messageId) }.testTag("result-${result.messageId}"),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(date: Int) {
    ServiceNote(day(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
}

@Composable
private fun ServiceNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InputBar(composer: Composer, onCancel: () -> Unit, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    // Editing starts from the message text.
    LaunchedEffect(composer) {
        if (composer is Composer.Edit) text = composer.message.text
    }
    Column(Modifier.fillMaxWidth()) {
        val banner = when (composer) {
            Composer.New -> null
            is Composer.Reply -> ReplyPreview(
                composer.message.senderName ?: if (composer.message.isOutgoing) "You" else null,
                composer.message.text,
            )
            is Composer.Edit -> ReplyPreview("Edit message", composer.message.text)
        }
        banner?.let {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReplyQuote(it, Modifier.weight(1f))
                TextButton(
                    onClick = {
                        if (composer is Composer.Edit) text = ""
                        onCancel()
                    },
                    modifier = Modifier.testTag("cancelComposer"),
                ) { Text("✕") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message") },
                maxLines = 5,
                modifier = Modifier.weight(1f).testTag("input"),
            )
            TextButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("send"),
            ) {
                Text(if (composer is Composer.Edit) "Save" else "Send")
            }
        }
    }
}

private fun day(unixTime: Int): LocalDate =
    Instant.ofEpochSecond(unixTime.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()

fun formatTime(unixTime: Int): String =
    Instant.ofEpochSecond(unixTime.toLong()).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private const val LOAD_MORE_THRESHOLD = 10
