package dev.otmgram.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.otmgram.R
import dev.otmgram.ui.format.formatTime
import dev.otmgram.ui.theme.DialogTags
import dev.otmgram.ui.theme.FullScreenDialog
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmDialog
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.TextAction

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
    modifier: Modifier = Modifier,
) {
    val message = menu.message
    FullScreenDialog(onDismiss) {
        Box(
            modifier = modifier
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
                        Text(formatTime(message.date), style = OtmType.Time, color = Otm.Meta)
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
        Icon(icon, null, tint = if (danger) Otm.Error else Otm.Meta, modifier = Modifier.size(22.dp))
        Text(label, style = OtmType.Body.copy(fontSize = 16.sp), color = if (danger) Otm.Error else Otm.Ink)
    }
}

@Composable
fun DeleteDialog(request: DeleteRequest, onDelete: (forEveryone: Boolean) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Only offered when both ways are possible; otherwise it's whichever one is.
    var forEveryone by rememberSaveable { mutableStateOf(request.canDeleteForEveryone) }
    val count = request.messageIds.size
    OtmDialog(
        modifier = modifier,
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
