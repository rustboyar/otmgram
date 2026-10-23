package dev.otmgram.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.otmgram.ui.media.formatSize

// Dialogs are separate windows; each exposes its test tags for UI Automator.
private val dialogModifier = Modifier.semantics { testTagsAsResourceId = true }

@Composable
fun MessageMenuDialog(
    menu: MessageMenu,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onSave: () -> Unit,
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
                if (menu.canSave) MenuItem("Save to Downloads", "save", onSave)
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

/** Picked photos and videos with a caption field, before sending. */
@Composable
fun MediaPreviewDialog(uris: List<Uri>, onSend: (caption: String, compress: Boolean) -> Unit, onDismiss: () -> Unit) {
    var caption by rememberSaveable { mutableStateOf("") }
    var compress by rememberSaveable { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = dialogModifier,
        title = { Text(if (uris.size == 1) "Send 1 item" else "Send ${uris.size} items as an album") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uris) { uri ->
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(PREVIEW_SIZE).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = { Text("Caption") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("caption"),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { compress = !compress }.testTag("compress"),
                ) {
                    Checkbox(checked = compress, onCheckedChange = { compress = it })
                    Text("Compress")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(caption, compress) }, modifier = Modifier.testTag("sendMedia")) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val PREVIEW_SIZE = 96.dp

/** Picked files (sent as they are) with a caption field, before sending. */
@Composable
fun FilesPreviewDialog(uris: List<Uri>, onSend: (caption: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var caption by rememberSaveable { mutableStateOf("") }
    val names = remember(uris) {
        uris.map { uri ->
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { if (it.moveToFirst()) "${it.getString(0)} · ${formatSize(it.getLong(1))}" else null }
                ?: uri.lastPathSegment.orEmpty()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = dialogModifier,
        title = { Text(if (uris.size == 1) "Send file" else "Send ${uris.size} files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                names.forEach { Text("📄 $it", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = { Text("Caption") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("fileCaption"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(caption) }, modifier = Modifier.testTag("sendFiles")) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
