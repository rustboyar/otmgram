package dev.otmgram.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.otmgram.core.location.LiveLocationSharing
import dev.otmgram.ui.media.formatSize
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.Chip
import dev.otmgram.ui.theme.DialogTags
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmDialog
import dev.otmgram.ui.theme.OtmField
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmSwitch
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.PlainDialogWindow
import dev.otmgram.ui.theme.TextAction
import dev.otmgram.ui.theme.paper

/**
 * Long-press menu, floating over the dimmed chat: reactions on top, the message, and the
 * actions below it, on the message's side.
 */
@Composable
fun MessageMenuOverlay(
    menu: MessageMenu,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onSave: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = menu.message
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PlainDialogWindow()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Otm.Paper.copy(alpha = 0.78f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .systemBarsPadding()
                .padding(12.dp)
                .then(DialogTags),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                horizontalAlignment = if (message.isOutgoing) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (menu.reactions.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .widthIn(max = 330.dp)
                            .shadow(6.dp, RoundedCornerShape(26.dp), ambientColor = Otm.Ink.copy(alpha = 0.3f), spotColor = Otm.Ink.copy(alpha = 0.3f))
                            .background(Otm.Card, RoundedCornerShape(26.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(menu.reactions) { emoji ->
                            Box(
                                Modifier.size(42.dp).clip(CircleShape).clickable { onReact(emoji) }.testTag("react-$emoji"),
                                contentAlignment = Alignment.Center,
                            ) { Text(emoji, fontSize = 22.sp) }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .widthIn(max = 290.dp)
                        .shadow(6.dp, BUBBLE_SHAPE, ambientColor = Otm.Ink.copy(alpha = 0.3f), spotColor = Otm.Ink.copy(alpha = 0.3f))
                        .background(if (message.isOutgoing) Otm.Apricot else Otm.Card, BUBBLE_SHAPE)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    message.senderName?.let { Text(it, style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold), color = Otm.Slate) }
                    Text(
                        message.text.takeIf { it.isNotEmpty() } ?: message.media?.label() ?: "",
                        style = OtmType.Body,
                        color = Otm.Ink,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(message.date), style = OtmType.Time, color = Color(0xFF56626B))
                        message.status?.let { StatusMark(it) }
                    }
                }
                Column(
                    modifier = Modifier
                        .width(230.dp)
                        .shadow(6.dp, RoundedCornerShape(18.dp), ambientColor = Otm.Ink.copy(alpha = 0.3f), spotColor = Otm.Ink.copy(alpha = 0.3f))
                        .background(Otm.Card, RoundedCornerShape(18.dp))
                        .padding(vertical = 6.dp),
                ) {
                    if (menu.canReply) MenuAction(OtmIcons.Reply, "Reply", "reply", onReply)
                    if (menu.canEdit) MenuAction(OtmIcons.Edit, "Edit", "edit", onEdit)
                    if (menu.canCopy) MenuAction(OtmIcons.Copy, "Copy text", "copy", onCopy)
                    if (menu.canForward) MenuAction(OtmIcons.Forward, "Forward", "forward", onForward)
                    if (menu.canSave) MenuAction(OtmIcons.Save, "Save to Downloads", "save", onSave)
                    MenuAction(OtmIcons.Select, "Select", "select", onSelect)
                    if (menu.canDeleteForSelf || menu.canDeleteForEveryone) {
                        MenuAction(OtmIcons.Trash, "Delete", "delete", onDelete, danger = true)
                    }
                }
            }
        }
    }
}

private val BUBBLE_SHAPE = RoundedCornerShape(18.dp)

@Composable
private fun MenuAction(icon: ImageVector, label: String, tag: String, onClick: () -> Unit, danger: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).testTag(tag).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (danger) Otm.Error else Color(0xFF56626B), modifier = Modifier.size(22.dp))
        Text(label, style = OtmType.Body.copy(fontSize = 16.sp), color = if (danger) Otm.Error else Otm.Ink)
    }
}

/** One line for media without a caption, e.g. in the menu. */
fun MessageMedia.label(): String = when (this) {
    is MessageMedia.Photo -> "Photo"
    is MessageMedia.Video -> "Video"
    is MessageMedia.Animation -> "GIF"
    is MessageMedia.VoiceNote -> "Voice message"
    is MessageMedia.VideoNote -> "Video message"
    is MessageMedia.Sticker -> "Sticker"
    is MessageMedia.Document -> name
    is MessageMedia.Location -> if (live != null) "Live location" else "Location"
}

@Composable
fun DeleteDialog(request: DeleteRequest, onDelete: (forEveryone: Boolean) -> Unit, onDismiss: () -> Unit) {
    // Only offered when both ways are possible; otherwise it's whichever one is.
    var forEveryone by rememberSaveable { mutableStateOf(request.canDeleteForEveryone) }
    val count = request.messageIds.size
    OtmDialog(
        title = if (count == 1) "Delete message?" else "Delete $count messages?",
        text = request.quote?.let { "“${it.take(140)}”" },
        onDismiss = onDismiss,
        confirm = {
            TextAction(
                "Delete",
                { onDelete(if (request.canDeleteForSelf) forEveryone else true) },
                Modifier.testTag(if (forEveryone) "deleteForEveryone" else "deleteForMe"),
                color = Otm.Error,
            )
        },
        content = if (request.canDeleteForEveryone && request.canDeleteForSelf) {
            {
                Row(
                    modifier = Modifier
                        .toggleable(forEveryone, role = Role.Checkbox) { forEveryone = it }
                        .testTag("forEveryone")
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SelectionMark(forEveryone)
                    Text("Also delete for everyone", style = OtmType.Body, color = Otm.Ink)
                }
            }
        } else {
            null
        },
    )
}

/** Forwarding goes only to chats of the folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardSheet(targets: List<ForwardTarget>, onForward: (ForwardTarget) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    OtmSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Forward to", style = OtmType.BarTitle.copy(fontSize = 20.sp), color = Otm.Ink, modifier = Modifier.weight(1f))
            IconBtn(OtmIcons.Close, "Close", onDismiss, tint = Otm.InkSoft)
        }
        OtmField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Find a chat",
            textStyle = OtmType.Body,
            shape = RoundedCornerShape(22.dp),
            height = 44.dp,
            background = Color(0xFFEFEBE4),
            outlined = false,
            leading = { Icon(OtmIcons.Search, null, tint = Otm.InkSoft, modifier = Modifier.size(20.dp)) },
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        val shown = targets.filter { it.title.contains(query.trim(), ignoreCase = true) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 8.dp)) {
            items(shown, key = { it.chatId }) { target ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onForward(target) }
                        .testTag("forward-${target.title}")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ChatAvatar(target.chatId, target.title, target.photoFileId, target.minithumbnail, size = 44.dp, savedMessages = target.isSavedMessages)
                    Text(target.title, style = OtmType.Name, color = Otm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 📎: what to send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onLocation: () -> Unit,
    onVoice: () -> Unit,
    onDismiss: () -> Unit,
) {
    OtmSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            AttachOption(OtmIcons.Gallery, "Gallery", "attachMedia", onGallery)
            AttachOption(OtmIcons.File, "File", "attachFile", onFile)
            AttachOption(OtmIcons.Pin, "Location", "attachLocation", onLocation)
            AttachOption(OtmIcons.Mic, "Voice", "record", onVoice)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AttachOption(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).testTag(tag).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Otm.Haze), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Otm.SlateDeep, modifier = Modifier.size(24.dp))
        }
        Text(label, style = OtmType.Secondary, color = Otm.InkBody)
    }
}

/** Send the current location once, or share it live for a while. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSheet(chatTitle: String, onSendCurrent: () -> Unit, onShareLive: (periodSeconds: Int) -> Unit, onDismiss: () -> Unit) {
    OtmSheet(onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onSendCurrent)
                .testTag("sendCurrentLocation")
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RoundIcon(OtmIcons.Pin)
            Column {
                Text("Send my current location", style = OtmType.Name, color = Otm.Ink)
                Text("Once, as it is now", style = OtmType.Secondary, color = Otm.InkSoft)
            }
        }
        Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Otm.Line))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundIcon(OtmIcons.LiveLocation)
                Column {
                    Text("Share live location", style = OtmType.Name, color = Otm.Ink)
                    Text("$chatTitle sees you move until it ends or you stop", style = OtmType.Secondary, color = Otm.InkSoft)
                }
            }
            Row(Modifier.padding(start = 62.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveLocationSharing.PERIODS.forEach { (seconds, label) ->
                    Chip(label, selected = false, onClick = { onShareLive(seconds) }, modifier = Modifier.testTag("live-$seconds"))
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFDCE4E9)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Otm.SlateDeep, modifier = Modifier.size(22.dp))
    }
}

/** A bottom sheet on the sheet colour, with the design's grab handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtmSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Otm.Sheet,
        scrimColor = Otm.Ink.copy(alpha = 0.28f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 10.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFCBCFD3)))
        },
        modifier = DialogTags,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), content = content)
    }
}

/** Picked photos and videos, large, with a caption field and Compress, before sending. */
@Composable
fun MediaPreviewScreen(
    uris: List<Uri>,
    chatTitle: String,
    onSend: (caption: String, compress: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var compress by rememberSaveable { mutableStateOf(true) }
    var shown by rememberSaveable { mutableIntStateOf(0) }
    SendPreview(
        title = if (uris.size == 1) "1 item" else "${uris.size} items",
        chatTitle = chatTitle,
        captionTag = "caption",
        sendTag = "sendMedia",
        onSend = { onSend(it, compress) },
        onDismiss = onDismiss,
    ) {
        AsyncImage(
            model = uris.getOrNull(shown),
            contentDescription = "Selected item",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Otm.Haze),
        )
        if (uris.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                itemsIndexed(uris) { index, uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Item ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .then(if (index == shown) Modifier.border(2.dp, Otm.Slate, RoundedCornerShape(12.dp)).padding(3.dp) else Modifier)
                            .clip(RoundedCornerShape(if (index == shown) 9.dp else 12.dp))
                            .clickable { shown = index },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(compress, role = Role.Switch) { compress = it }.testTag("compress").padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Compress", style = OtmType.Body.copy(fontWeight = FontWeight.SemiBold), color = Otm.Ink)
                Text(if (compress) "Smaller and faster to send" else "Sent at full quality", style = OtmType.Secondary, color = Otm.InkSoft)
            }
            OtmSwitch(compress, onCheckedChange = null)
        }
    }
}

/** Picked files (sent as they are) with a caption field, before sending. */
@Composable
fun FilesPreviewScreen(uris: List<Uri>, chatTitle: String, onSend: (caption: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val files = remember(uris) {
        uris.map { uri ->
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) to formatSize(it.getLong(1)) else null }
                ?: (uri.lastPathSegment.orEmpty() to "")
        }
    }
    SendPreview(
        title = if (uris.size == 1) "1 file" else "${uris.size} files",
        chatTitle = chatTitle,
        captionTag = "fileCaption",
        sendTag = "sendFiles",
        onSend = onSend,
        onDismiss = onDismiss,
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files) { (name, size) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Otm.Card).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Otm.Haze), contentAlignment = Alignment.Center) {
                        Icon(OtmIcons.File, null, tint = Otm.SlateDeep, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name, style = OtmType.Body.copy(fontWeight = FontWeight.Medium), color = Otm.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (size.isNotEmpty()) Text(size, style = OtmType.Secondary, color = Otm.InkSoft)
                    }
                }
            }
        }
        Text("Files are sent as they are, without compression.", style = OtmType.Secondary, color = Otm.InkSoft, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun SendPreview(
    title: String,
    chatTitle: String,
    captionTag: String,
    sendTag: String,
    onSend: (caption: String) -> Unit,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    var caption by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PlainDialogWindow()
        Column(Modifier.fillMaxSize().paper().systemBarsPadding().imePadding().then(DialogTags)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(OtmIcons.Close, "Cancel", onDismiss, tag = "cancelSend")
                Column(Modifier.padding(start = 6.dp)) {
                    Text(title, style = OtmType.BarTitle, color = Otm.Ink)
                    Text("to $chatTitle", style = OtmType.Secondary, color = Otm.InkSoft)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OtmField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = "Add a caption",
                    textStyle = OtmType.Body,
                    singleLine = false,
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    height = 44.dp,
                    outlined = false,
                    modifier = Modifier.weight(1f).border(1.dp, Otm.Line, RoundedCornerShape(22.dp)).testTag(captionTag),
                )
                IconBtn(OtmIcons.Send, "Send", { onSend(caption) }, tint = Color.White, background = Otm.Slate, tag = sendTag)
            }
        }
    }
}
