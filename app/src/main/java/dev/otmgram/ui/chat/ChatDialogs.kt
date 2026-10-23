package dev.otmgram.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

// Dialogs are separate windows; each exposes its test tags for UI Automator.
private val dialogModifier = Modifier.semantics { testTagsAsResourceId = true }

@Composable
fun MessageMenuDialog(
    menu: MessageMenu,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = dialogModifier,
        text = {
            Column {
                if (menu.reactions.isNotEmpty()) {
                    LazyRow {
                        items(menu.reactions) { emoji ->
                            TextButton(onClick = { onReact(emoji) }, modifier = Modifier.testTag("react-$emoji")) {
                                Text(emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
                if (menu.canReply) MenuItem("Reply", "reply", onReply)
                if (menu.canForward) MenuItem("Forward", "forward", onForward)
                if (menu.canEdit) MenuItem("Edit", "edit", onEdit)
                if (menu.canDeleteForSelf || menu.canDeleteForEveryone) MenuItem("Delete", "delete", onDelete)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MenuItem(label: String, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}

@Composable
fun DeleteDialog(menu: MessageMenu, onDelete: (forEveryone: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = dialogModifier,
        title = { Text("Delete message?") },
        text = {
            Column {
                if (menu.canDeleteForEveryone) MenuItem("Delete for everyone", "deleteForEveryone") { onDelete(true) }
                if (menu.canDeleteForSelf) MenuItem("Delete for me", "deleteForMe") { onDelete(false) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Forwarding goes only to chats of the folder. */
@Composable
fun ForwardDialog(targets: List<ForwardTarget>, onForward: (ForwardTarget) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = dialogModifier,
        title = { Text("Forward to") },
        text = {
            LazyColumn {
                items(targets, key = { it.chatId }) { target ->
                    MenuItem(target.title, "forward-${target.title}") { onForward(target) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
