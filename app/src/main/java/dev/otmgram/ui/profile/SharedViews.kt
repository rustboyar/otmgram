package dev.otmgram.ui.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.otmgram.R
import dev.otmgram.core.media.AudioState
import dev.otmgram.ui.chat.DocumentContent
import dev.otmgram.ui.chat.Label
import dev.otmgram.ui.chat.VoiceActions
import dev.otmgram.ui.chat.VoiceNote
import dev.otmgram.ui.embed.MediaLink
import dev.otmgram.ui.format.chatListDate
import dev.otmgram.ui.format.formatDuration
import dev.otmgram.ui.media.TdImage
import dev.otmgram.ui.media.openLink
import dev.otmgram.ui.theme.Chip
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmSheet
import dev.otmgram.ui.theme.OtmType

/** The tabs: a group's members first, then each kind the chat has shared, with how many. */
@Composable
internal fun ProfileTabs(hasMembers: Boolean, counts: Map<SharedKind, Int>, selected: ProfileTab?, onSelect: (ProfileTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            // To the screen's edges, past the list's side padding, so it scrolls under them.
            .layout { measurable, constraints ->
                val bleed = BLEED.roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + 2 * bleed, maxWidth = constraints.maxWidth + 2 * bleed))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = BLEED, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasMembers) {
            Chip(stringResource(R.string.profile_members), selected == ProfileTab.Members, { onSelect(ProfileTab.Members) }, Modifier.testTag("tab-members"))
        }
        SharedKind.entries.forEach { kind ->
            val count = counts[kind] ?: return@forEach
            val tab = ProfileTab.Shared(kind)
            Chip(stringResource(R.string.shared_tab, stringResource(kind.label), count), selected == tab, { onSelect(tab) }, Modifier.testTag("tab-${kind.name}"))
        }
    }
}

/** What actions on a shared item lead to; [onLongPress] null when its chat can't be shown. */
internal class SharedActions(
    val audio: () -> AudioState?,
    val voiceActions: (messageId: Long) -> VoiceActions,
    val onOpenMedia: (messageId: Long) -> Unit,
    /** A YouTube, Instagram or TikTok link, played in the app as in the chat. */
    val onOpenEmbed: (url: String) -> Unit,
    val onLoadMore: () -> Unit,
    val onLongPress: ((messageId: Long) -> Unit)?,
)

/** The shared items of the tab shown: photos and videos in a grid of three, the rest a row each. */
internal fun LazyListScope.sharedItems(media: SharedMedia, actions: SharedActions) {
    val items = media.items
    if (media.kind == SharedKind.Media) {
        items(items.chunked(GRID_COLUMNS), key = { "grid-${it.first().messageId}" }) { row ->
            Row(Modifier.fillMaxWidth().padding(top = GRID_GAP), horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                row.forEach { item -> GridSquare(item as SharedItem.Visual, actions, Modifier.weight(1f)) }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else {
        items(items, key = { it.messageId }) { item ->
            val onLongPress = actions.onLongPress?.let { { it(item.messageId) } }
            when (item) {
                is SharedItem.File -> SharedRow(item.date, onLongPress) { DocumentContent(item.document, onLongPress ?: {}, null) }
                is SharedItem.Link -> LinkRow(item, actions.onOpenEmbed, onLongPress)
                is SharedItem.Voice -> SharedRow(item.date, onLongPress, caption = item.sender) {
                    VoiceNote(item.voice, actions.audio, outgoing = false, actions.voiceActions(item.messageId), onLongPress ?: {})
                }
                is SharedItem.Round -> RoundRow(item, { actions.onOpenMedia(item.messageId) }, onLongPress)
                is SharedItem.Visual -> Unit
            }
        }
    }
    if (!media.complete) {
        // Coming into view, it loads the next page.
        item(key = "more") {
            LaunchedEffect(items.size) { actions.onLoadMore() }
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = Otm.Slate, strokeWidth = 2.dp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridSquare(item: SharedItem.Visual, actions: SharedActions, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Otm.Haze)
            .combinedClickable(onLongClick = actions.onLongPress?.let { { it(item.messageId) } }) { actions.onOpenMedia(item.messageId) }
            .testTag("shared-${item.messageId}"),
    ) {
        TdImage(item.thumbnailFileId, item.minithumbnail, Modifier.fillMaxSize())
        item.duration?.let { Label(formatDuration(it), Modifier.align(Alignment.BottomEnd)) }
    }
}

/** A file or voice message, with when it was sent; [caption] above it, e.g. who sent it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SharedRow(date: Int, onLongPress: (() -> Unit)?, caption: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(enabled = onLongPress != null, onLongClick = onLongPress, onClick = {})
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            caption?.let { Text(it, style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold), color = Otm.SlateDeep, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            Spacer(Modifier.weight(1f))
            Text(chatListDate(date), style = OtmType.Time, color = Otm.InkSoft, modifier = Modifier.padding(start = 8.dp))
        }
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkRow(link: SharedItem.Link, onOpenEmbed: (String) -> Unit, onLongPress: (() -> Unit)?) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onLongClick = onLongPress) {
                MediaLink.parse(link.url)?.let { onOpenEmbed(it.url) } ?: openLink(context, link.url)
            }
            .testTag("link-${link.messageId}")
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(THUMBNAIL).clip(RoundedCornerShape(10.dp)).background(Otm.Slate), contentAlignment = Alignment.Center) {
            if (link.thumbnailFileId != null || link.minithumbnail != null) {
                TdImage(link.thumbnailFileId, link.minithumbnail, Modifier.fillMaxSize())
            } else {
                Text(link.title.take(1).uppercase(), color = Color.White, style = OtmType.Name.copy(fontSize = 18.sp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(link.title, style = OtmType.Name, color = Otm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(chatListDate(link.date), style = OtmType.Time, color = Otm.InkSoft, modifier = Modifier.padding(start = 8.dp))
            }
            Text(link.url, style = OtmType.Secondary, color = Otm.SlateDeep, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The message around the link, when there's more to it than the link.
            if (link.text.trim() != link.url) {
                Text(link.text, style = OtmType.Secondary, color = Otm.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A round video: its picture, how long, who sent it; a tap plays it full screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoundRow(item: SharedItem.Round, onOpen: () -> Unit, onLongPress: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onLongClick = onLongPress, onClick = onOpen)
            .testTag("round-${item.messageId}")
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(THUMBNAIL).clip(CircleShape).background(Otm.Haze), contentAlignment = Alignment.Center) {
            TdImage(item.round.thumbnailFileId, item.round.minithumbnail, Modifier.fillMaxSize())
            Icon(OtmIcons.Play, stringResource(R.string.play_video), tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(item.sender, stringResource(R.string.shared_round)).joinToString(" · "),
                style = OtmType.Name,
                color = Otm.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatDuration(item.round.duration), style = OtmType.Secondary, color = Otm.InkSoft)
        }
        Text(chatListDate(item.date), style = OtmType.Time, color = Otm.InkSoft)
    }
}

/** A long press on something shared: show it where it was sent. */
@Composable
internal fun SharedItemActions(onShowInChat: () -> Unit, onDismiss: () -> Unit) {
    OtmSheet(onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button, onClick = onShowInChat)
                .testTag("showInChat")
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(OtmIcons.Send, null, tint = Otm.Ink, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.shared_show_in_chat), style = OtmType.Body, color = Otm.Ink)
        }
    }
}

/** The profile list's side padding. */
private val BLEED = 16.dp
private const val GRID_COLUMNS = 3
private val GRID_GAP = 3.dp
private val THUMBNAIL = 48.dp
