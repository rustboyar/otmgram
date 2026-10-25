package dev.otmgram.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.location.LiveLocationSharing
import dev.otmgram.core.location.MapSnapshots
import dev.otmgram.data.SizeEstimate
import dev.otmgram.ui.media.formatSize
import dev.otmgram.ui.rememberStrings
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    onPin: () -> Unit,
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
                                Modifier.size(42.dp).clip(CircleShape).clickable(role = Role.Button) { onReact(emoji) }.testTag("react-$emoji"),
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
                    if (menu.canReply) MenuAction(OtmIcons.Reply, stringResource(R.string.action_reply), "reply", onReply)
                    if (menu.canEdit) MenuAction(OtmIcons.Edit, stringResource(R.string.action_edit), "edit", onEdit)
                    if (menu.canCopy) MenuAction(OtmIcons.Copy, stringResource(R.string.action_copy), "copy", onCopy)
                    if (menu.canForward) MenuAction(OtmIcons.Forward, stringResource(R.string.action_forward), "forward", onForward)
                    if (menu.canSave) MenuAction(OtmIcons.Save, stringResource(R.string.action_save), "save", onSave)
                    if (menu.canPin) {
                        if (menu.isPinned) {
                            MenuAction(OtmIcons.Unpin, stringResource(R.string.action_unpin), "unpin", onPin)
                        } else {
                            MenuAction(OtmIcons.Pushpin, stringResource(R.string.action_pin), "pin", onPin)
                        }
                    }
                    MenuAction(OtmIcons.Select, stringResource(R.string.action_select), "select", onSelect)
                    if (menu.canDeleteForSelf || menu.canDeleteForEveryone) {
                        MenuAction(OtmIcons.Trash, stringResource(R.string.action_delete), "delete", onDelete, danger = true)
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
        modifier = Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick, role = Role.Button).testTag(tag).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (danger) Otm.Error else Color(0xFF56626B), modifier = Modifier.size(22.dp))
        Text(label, style = OtmType.Body.copy(fontSize = 16.sp), color = if (danger) Otm.Error else Otm.Ink)
    }
}

/** One line for media without a caption, e.g. in the menu. */
@Composable
fun MessageMedia.label(): String = when (this) {
    is MessageMedia.Photo -> stringResource(R.string.summary_photo)
    is MessageMedia.Video -> stringResource(R.string.summary_video)
    is MessageMedia.Animation -> stringResource(R.string.summary_gif)
    is MessageMedia.VoiceNote -> stringResource(R.string.summary_voice)
    is MessageMedia.VideoNote -> stringResource(R.string.summary_video_note)
    is MessageMedia.Sticker -> stringResource(R.string.summary_sticker)
    is MessageMedia.Document -> name
    is MessageMedia.Location -> stringResource(if (live != null) R.string.summary_live_location else R.string.summary_location)
}

@Composable
fun DeleteDialog(request: DeleteRequest, onDelete: (forEveryone: Boolean) -> Unit, onDismiss: () -> Unit) {
    // Only offered when both ways are possible; otherwise it's whichever one is.
    var forEveryone by rememberSaveable { mutableStateOf(request.canDeleteForEveryone) }
    val count = request.messageIds.size
    OtmDialog(
        title = pluralStringResource(R.plurals.delete_title, count, count),
        text = request.quote?.let { stringResource(R.string.quote, it.take(140)) },
        onDismiss = onDismiss,
        confirm = {
            TextAction(
                stringResource(R.string.action_delete),
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
                    Text(stringResource(R.string.delete_for_everyone), style = OtmType.Body, color = Otm.Ink)
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
            Text(stringResource(R.string.forward_to), style = OtmType.BarTitle.copy(fontSize = 20.sp), color = Otm.Ink, modifier = Modifier.weight(1f))
            IconBtn(OtmIcons.Close, stringResource(R.string.close), onDismiss, tint = Otm.InkSoft)
        }
        OtmField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.find_chat),
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
                        .clickable(role = Role.Button) { onForward(target) }
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

/** All pinned messages, newest first; a tap goes to one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinnedSheet(pinned: List<SearchResult>, onGoTo: (messageId: Long) -> Unit, onDismiss: () -> Unit) {
    OtmSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pinned_title), style = OtmType.BarTitle.copy(fontSize = 20.sp), color = Otm.Ink, modifier = Modifier.weight(1f))
            IconBtn(OtmIcons.Close, stringResource(R.string.close), onDismiss, tint = Otm.InkSoft)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 8.dp)) {
            items(pinned, key = { it.messageId }) { message ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button) { onGoTo(message.messageId) }
                        .testTag("pinnedItem")
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message.senderName.orEmpty(),
                            style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold),
                            color = Otm.Slate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${dayLabel(message.date)}, ${formatTime(message.date)}", style = OtmType.Time, color = Otm.InkFaint)
                    }
                    Text(message.text, style = OtmType.Preview, color = Otm.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            AttachOption(OtmIcons.Gallery, stringResource(R.string.attach_gallery), "attachMedia", onGallery)
            AttachOption(OtmIcons.File, stringResource(R.string.attach_file), "attachFile", onFile)
            AttachOption(OtmIcons.Pin, stringResource(R.string.attach_location), "attachLocation", onLocation)
            AttachOption(OtmIcons.Mic, stringResource(R.string.attach_voice), "record", onVoice)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AttachOption(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick, role = Role.Button).testTag(tag).padding(vertical = 8.dp),
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
fun LocationSheet(
    chatTitle: String,
    here: android.location.Location?,
    onSendCurrent: () -> Unit,
    onShareLive: (periodSeconds: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    OtmSheet(onDismiss) {
        here?.let { HereMap(it) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onSendCurrent, role = Role.Button)
                .testTag("sendCurrentLocation")
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RoundIcon(OtmIcons.Pin)
            Column {
                Text(stringResource(R.string.location_send_current), style = OtmType.Name, color = Otm.Ink)
                Text(
                    here?.takeIf { it.hasAccuracy() }?.let { stringResource(R.string.location_accuracy, it.accuracy.roundToInt()) } ?: stringResource(R.string.location_once),
                    style = OtmType.Secondary,
                    color = Otm.InkSoft,
                )
            }
        }
        Box(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Otm.Line))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundIcon(OtmIcons.LiveLocation)
                Column {
                    Text(stringResource(R.string.location_share_live), style = OtmType.Name, color = Otm.Ink)
                    Text(stringResource(R.string.location_live_hint, chatTitle), style = OtmType.Secondary, color = Otm.InkSoft)
                }
            }
            Row(Modifier.padding(start = 62.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveLocationSharing.PERIODS.forEach { (seconds, label) ->
                    Chip(stringResource(label), selected = false, onClick = { onShareLive(seconds) }, modifier = Modifier.testTag("live-$seconds"))
                }
            }
        }
    }
}

/** A small map of where you are, on top of the location sheet. */
@Composable
private fun HereMap(here: android.location.Location) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val map by produceState<ImageBitmap?>(null, here.latitude, here.longitude) {
        value = MapSnapshots.get(context, here.latitude, here.longitude, HERE_MAP_WIDTH, HERE_MAP_HEIGHT, density)?.asImageBitmap()
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .height(HERE_MAP_HEIGHT.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFEFEDE6))
            .testTag("hereMap"),
        contentAlignment = Alignment.Center,
    ) {
        map?.let { Image(it, contentDescription = stringResource(R.string.location_map), contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
        MapPin()
    }
}

private const val HERE_MAP_WIDTH = 360
private const val HERE_MAP_HEIGHT = 150

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
    compressByDefault: Boolean,
    sizes: SizeEstimate?,
    onSend: (caption: String, compress: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var compress by rememberSaveable { mutableStateOf(compressByDefault) }
    var shown by rememberSaveable { mutableIntStateOf(0) }
    SendPreview(
        title = pluralStringResource(R.plurals.media_items, uris.size, uris.size),
        chatTitle = chatTitle,
        captionTag = "caption",
        sendTag = "sendMedia",
        onSend = { onSend(it, compress) },
        onDismiss = onDismiss,
    ) {
        AsyncImage(
            model = uris.getOrNull(shown),
            contentDescription = stringResource(R.string.media_selected),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Otm.Haze),
        )
        if (uris.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                itemsIndexed(uris) { index, uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.media_item, index + 1),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .then(if (index == shown) Modifier.border(2.dp, Otm.Slate, RoundedCornerShape(12.dp)).padding(3.dp) else Modifier)
                            .clip(RoundedCornerShape(if (index == shown) 9.dp else 12.dp))
                            .selectable(selected = index == shown, role = Role.Tab) { shown = index },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(compress, role = Role.Switch) { compress = it }.testTag("compress").padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.compress), style = OtmType.Body.copy(fontWeight = FontWeight.SemiBold), color = Otm.Ink)
                Text(sizeLine(sizes, compress, rememberStrings()), style = OtmType.Secondary, color = Otm.InkSoft, modifier = Modifier.testTag("sizeEstimate"))
            }
            OtmSwitch(compress, onCheckedChange = null)
        }
    }
}

/** "About 2.4 MB instead of 31 MB", or the size as it goes. */
internal fun sizeLine(sizes: SizeEstimate?, compress: Boolean, strings: Strings): String = when {
    sizes == null -> strings.get(if (compress) R.string.size_compress_unknown else R.string.size_full_unknown)
    !compress -> strings.get(R.string.size_full, formatSize(sizes.original))
    sizes.compressed < sizes.original * 9 / 10 -> strings.get(R.string.size_compressed, formatSize(sizes.compressed), formatSize(sizes.original))
    else -> strings.get(R.string.size_already_small, formatSize(sizes.original))
}

/** Picked files (sent as they are) with a caption field, before sending. */
@Composable
fun FilesPreviewScreen(uris: List<Uri>, chatTitle: String, onSend: (caption: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Names and sizes come from the app that has the files, which may be slow to answer.
    val files by produceState(emptyList(), uris) {
        value = withContext(Dispatchers.IO) {
            uris.map { uri ->
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) to formatSize(it.getLong(1)) else null }
                }.getOrNull() ?: (uri.lastPathSegment.orEmpty() to "")
            }
        }
    }
    SendPreview(
        title = pluralStringResource(R.plurals.files_count, uris.size, uris.size),
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
        Text(stringResource(R.string.files_hint), style = OtmType.Secondary, color = Otm.InkSoft, modifier = Modifier.padding(horizontal = 4.dp))
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
                IconBtn(OtmIcons.Close, stringResource(R.string.cancel), onDismiss, tag = "cancelSend")
                Column(Modifier.padding(start = 6.dp)) {
                    Text(title, style = OtmType.BarTitle, color = Otm.Ink)
                    Text(stringResource(R.string.send_to, chatTitle), style = OtmType.Secondary, color = Otm.InkSoft)
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
                    placeholder = stringResource(R.string.caption),
                    textStyle = OtmType.Body,
                    singleLine = false,
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                    height = 44.dp,
                    outlined = false,
                    modifier = Modifier.weight(1f).border(1.dp, Otm.Line, RoundedCornerShape(22.dp)).testTag(captionTag),
                )
                IconBtn(OtmIcons.Send, stringResource(R.string.send), { onSend(caption) }, tint = Color.White, background = Otm.Slate, tag = sendTag)
            }
        }
    }
}
