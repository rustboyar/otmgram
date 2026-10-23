package dev.otmgram.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    viewModel.error?.let { error ->
        LaunchedEffect(error) {
            snackbar.showSnackbar(error)
            viewModel.errorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(header?.title.orEmpty()) },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("←") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The scaffold padding already includes the navigation bar, which the keyboard inset overlaps.
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("messages"),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
            ) {
                messages.forEachIndexed { index, message ->
                    item(key = message.id) {
                        if (message.isService) ServiceNote(message.text) else MessageBubble(message)
                    }
                    // reverseLayout: the item after a message is shown above it.
                    val older = messages.getOrNull(index + 1)
                    if (older == null || day(older.date) != day(message.date)) {
                        item(key = "day-${message.id}") { DayHeader(message.date) }
                    }
                }
            }
            InputBar(onSend = viewModel::send)
        }
    }
}

@Composable
private fun MessageBubble(message: MessageItem) {
    val outgoing = message.isOutgoing
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            message.senderName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (message.isMediaPlaceholder) FontStyle.Italic else FontStyle.Normal,
            )
            Text(
                listOfNotNull(
                    "edited".takeIf { message.isEdited },
                    time(message.date),
                    message.status?.let(::statusMark),
                ).joinToString(" "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
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
private fun InputBar(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
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
            Text("Send")
        }
    }
}

private fun statusMark(status: MessageStatus) = when (status) {
    MessageStatus.Sending -> "🕓"
    MessageStatus.Sent -> "✓"
    MessageStatus.Read -> "✓✓"
    MessageStatus.Failed -> "⚠"
}

private fun day(unixTime: Int): LocalDate =
    Instant.ofEpochSecond(unixTime.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()

private fun time(unixTime: Int): String =
    Instant.ofEpochSecond(unixTime.toLong()).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private const val LOAD_MORE_THRESHOLD = 10
