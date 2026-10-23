package dev.otmgram.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(message: MessageItem, onLongClick: () -> Unit, onReactionClick: (ReactionChip) -> Unit) {
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
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
                .testTag("message-${message.id}")
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
            message.forwardedFrom?.let {
                Text(
                    "Forwarded from $it",
                    style = MaterialTheme.typography.labelMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            message.replyTo?.let { ReplyQuote(it, Modifier.padding(vertical = 4.dp)) }
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = if (message.isMediaPlaceholder) FontStyle.Italic else FontStyle.Normal,
            )
            if (message.reactions.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    message.reactions.forEach { chip ->
                        Reaction(chip, onClick = { onReactionClick(chip) })
                    }
                }
            }
            Text(
                listOfNotNull(
                    "edited".takeIf { message.isEdited },
                    formatTime(message.date),
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
private fun Reaction(chip: ReactionChip, onClick: () -> Unit) {
    Text(
        "${chip.label} ${chip.count}",
        style = MaterialTheme.typography.labelLarge,
        color = if (chip.isChosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(
                if (chip.isChosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = chip.emoji != null, onClick = onClick)
            .testTag("reaction-${chip.label}")
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Quoted message with a bar on the left, in bubbles and above the input. */
@Composable
fun ReplyQuote(reply: ReplyPreview, modifier: Modifier = Modifier) {
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(start = 8.dp)) {
            reply.senderName?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                reply.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun statusMark(status: MessageStatus) = when (status) {
    MessageStatus.Sending -> "🕓"
    MessageStatus.Sent -> "✓"
    MessageStatus.Read -> "✓✓"
    MessageStatus.Failed -> "⚠"
}
