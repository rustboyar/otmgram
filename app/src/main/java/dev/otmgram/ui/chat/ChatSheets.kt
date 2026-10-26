package dev.otmgram.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.otmgram.R
import dev.otmgram.core.location.LiveLocationSharing
import dev.otmgram.core.location.MapSnapshots
import dev.otmgram.ui.format.dayLabel
import dev.otmgram.ui.format.formatTime
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.Chip
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmSheet
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.PillField
import kotlin.math.roundToInt

/** Forwarding goes only to chats of the folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardSheet(targets: List<ForwardTarget>, onForward: (ForwardTarget) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    OtmSheet(onDismiss, modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.forward_to), style = OtmType.BarTitle.copy(fontSize = 20.sp), color = Otm.Ink, modifier = Modifier.weight(1f))
            IconBtn(OtmIcons.Close, stringResource(R.string.close), onDismiss, tint = Otm.InkSoft)
        }
        PillField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(R.string.find_chat),
            background = Otm.Field,
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
fun PinnedSheet(pinned: List<SearchResult>, onGoTo: (messageId: Long) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    OtmSheet(onDismiss, modifier) {
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
    modifier: Modifier = Modifier,
) {
    OtmSheet(onDismiss, modifier) {
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
    modifier: Modifier = Modifier,
) {
    OtmSheet(onDismiss, modifier) {
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
            .background(Otm.Placeholder)
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
    Box(Modifier.size(48.dp).clip(CircleShape).background(Otm.Tile), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Otm.SlateDeep, modifier = Modifier.size(22.dp))
    }
}
