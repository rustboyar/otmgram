package dev.otmgram.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.ui.auth.LoadingScreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(viewModel: ChatListViewModel = hiltViewModel()) {
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (searching) {
                SearchBar(
                    query = query,
                    onQueryChange = viewModel::setQuery,
                    onClose = {
                        searching = false
                        viewModel.setQuery("")
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(folderName) },
                    actions = {
                        TextButton(onClick = { searching = true }, modifier = Modifier.testTag("search")) {
                            Text("Search")
                        }
                        Box {
                            TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("menu")) {
                                Text("⋮", style = MaterialTheme.typography.titleLarge)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Log out") },
                                    onClick = {
                                        menuOpen = false
                                        confirmLogout = true
                                    },
                                    modifier = Modifier.testTag("logout"),
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        val list = chats
        when {
            list == null -> LoadingScreen()
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(if (query.isBlank()) "No chats in this folder" else "No chats found")
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("chats")) {
                items(list, key = { it.id }) { ChatRow(it) }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text("Log out?") },
            text = { Text("All local data of this account will be deleted from the device.") },
            confirmButton = {
                TextButton(onClick = viewModel::logOut, modifier = Modifier.testTag("confirmLogout")) {
                    Text("Log out")
                }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
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
                placeholder = { Text("Search chats") },
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
private fun ChatRow(chat: ChatItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(chat.title, chat.id)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (chat.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                chat.date?.let {
                    Text(formatDate(it), style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unreadCount > 0) UnreadBadge(chat.unreadCount)
            }
        }
    }
}

@Composable
private fun Avatar(title: String, id: Long) {
    val initials = title.split(' ').filter { it.isNotEmpty() }.take(2)
        .joinToString("") { it.first().uppercase() }
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(AVATAR_COLORS[Math.floorMod(id, AVATAR_COLORS.size)], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .widthIn(min = 22.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 999) "999+" else count.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
        )
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

private val AVATAR_COLORS = listOf(
    Color(0xFFE57373), Color(0xFFF06292), Color(0xFFBA68C8), Color(0xFF7986CB),
    Color(0xFF4FC3F7), Color(0xFF4DB6AC), Color(0xFF81C784), Color(0xFFFFB74D),
)
