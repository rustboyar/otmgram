package dev.otmgram.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.otmgram.core.media.AudioState
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.TextAction

/** What search wants marked in a bubble: matches of [query], strongly if [current]. */
data class Highlight(val query: String, val current: Boolean)

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    message: MessageItem,
    audio: AudioState?,
    onLongClick: () -> Unit,
    onReactionClick: (ReactionChip) -> Unit,
    onOpenMedia: () -> Unit,
    onPlayVoice: (fileId: Int, path: String) -> Unit,
    onVideoNoteSound: () -> Unit,
    highlight: Highlight? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelected: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    val outgoing = message.isOutgoing
    val failed = message.status == MessageStatus.Failed
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) Otm.Haze.copy(alpha = 0.7f) else Color.Transparent)
                .then(if (selecting) Modifier.clickable(onClick = onToggleSelected) else Modifier)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) SelectionMark(selected, Modifier.padding(end = 10.dp))
            Box(Modifier.weight(1f), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
                Bubble(
                    message,
                    audio,
                    highlight,
                    onClick = if (selecting) onToggleSelected else ({}),
                    onLongClick = onLongClick,
                    onReactionClick = onReactionClick,
                    onOpenMedia = if (selecting) onToggleSelected else onOpenMedia,
                    onPlayVoice = onPlayVoice,
                    onVideoNoteSound = onVideoNoteSound,
                )
            }
        }
        if (failed && !selecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp).testTag("failed-${message.id}"),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Not sent.", style = OtmType.Secondary, color = Otm.Error)
                TextAction("Retry", onRetry, Modifier.testTag("retry"))
                TextAction("Delete", onDiscard, Modifier.testTag("discard"), color = Otm.InkSoft)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Bubble(
    message: MessageItem,
    audio: AudioState?,
    highlight: Highlight?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReactionClick: (ReactionChip) -> Unit,
    onOpenMedia: () -> Unit,
    onPlayVoice: (fileId: Int, path: String) -> Unit,
    onVideoNoteSound: () -> Unit,
) {
    val outgoing = message.isOutgoing
    val media = message.media
    val bare = media?.isBare == true && message.text.isEmpty() && message.replyTo == null
    // Pictures sit close to the bubble's edge; text gets the usual padding.
    val framed = media?.isFramed == true
    val failed = message.status == MessageStatus.Failed
    val shape = when {
        outgoing -> RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
        else -> RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    }
    val background = when {
        bare -> Color.Transparent
        failed -> Otm.ErrorSoft
        outgoing -> Otm.Apricot
        else -> Otm.Card
    }
    val textPadding = if (framed) Modifier.padding(horizontal = 7.dp) else Modifier
    Column(
        modifier = Modifier
            .widthIn(max = 290.dp)
            .then(if (bare) Modifier else Modifier.shadow(1.dp, shape, ambientColor = Otm.Ink.copy(alpha = 0.3f), spotColor = Otm.Ink.copy(alpha = 0.3f)))
            .clip(shape)
            .background(background)
            .then(if (failed) Modifier.border(1.dp, Color(0xFFE5B9AF), shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .testTag("message-${message.id}")
            .padding(
                horizontal = when {
                    bare -> 0.dp
                    framed -> 5.dp
                    else -> 12.dp
                },
                vertical = when {
                    bare -> 0.dp
                    framed -> 5.dp
                    else -> 8.dp
                },
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        message.senderName?.let {
            Text(it, style = OtmType.Secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Otm.Slate, modifier = textPadding)
        }
        message.forwardedFrom?.let {
            Text("Forwarded from $it", style = OtmType.Time, fontStyle = FontStyle.Italic, color = Otm.Slate, modifier = textPadding)
        }
        message.replyTo?.let { ReplyQuote(it, textPadding.padding(vertical = 4.dp)) }
        if (media != null) {
            MediaContent(
                media,
                audio,
                outgoing = outgoing,
                onOpen = onOpenMedia,
                onLongClick = onLongClick,
                onPlayVoice = onPlayVoice,
                onVideoNoteSound = onVideoNoteSound,
            )
        }
        if (message.text.isNotEmpty()) {
            Text(
                highlighted(message.text, highlight),
                style = OtmType.Body,
                color = Otm.Ink,
                fontStyle = if (message.isMediaPlaceholder) FontStyle.Italic else FontStyle.Normal,
                modifier = textPadding.then(if (media != null) Modifier.padding(top = 4.dp) else Modifier),
            )
        }
        if (message.reactions.isNotEmpty()) {
            FlowRow(
                modifier = textPadding.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.reactions.forEach { chip -> Reaction(chip, outgoing, onClick = { onReactionClick(chip) }) }
            }
        }
        TimeLine(message, bare, textPadding.align(Alignment.End))
    }
}

/** "edited 20:31" and the delivery mark; on stickers and circles, on a small paper chip. */
@Composable
private fun TimeLine(message: MessageItem, onChip: Boolean, modifier: Modifier) {
    Row(
        modifier = modifier
            .padding(top = if (onChip) 4.dp else 0.dp)
            .then(if (onChip) Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            listOfNotNull("edited".takeIf { message.isEdited }, formatTime(message.date)).joinToString(" "),
            style = OtmType.Time,
            color = if (message.status == MessageStatus.Failed) Otm.Error else Color(0xFF56626B),
        )
        message.status?.let { StatusMark(it) }
    }
}

/**
 * Delivery of your messages: a clock while sending, a plain circle with a check once sent,
 * a postmark (circle, check and cancellation waves) once read, a mark when it failed.
 */
@Composable
fun StatusMark(status: MessageStatus) {
    when (status) {
        MessageStatus.Sending -> Icon(OtmIcons.Clock, "Sending", tint = Otm.InkFaint, modifier = Modifier.size(15.dp))
        MessageStatus.Failed -> Icon(OtmIcons.Alert, "Not sent", tint = Otm.Error, modifier = Modifier.size(15.dp))
        MessageStatus.Sent -> Postmark(Otm.InkFaint, waves = false, Modifier.semantics { contentDescription = "Sent" })
        MessageStatus.Read -> Postmark(Otm.Slate, waves = true, Modifier.semantics { contentDescription = "Read" })
    }
}

@Composable
private fun Postmark(color: Color, waves: Boolean, modifier: Modifier) {
    Canvas(modifier.width(if (waves) 22.dp else 16.dp).height(16.dp)) {
        val u = size.height / 16f
        drawCircle(color, 6.5f * u, Offset(8 * u, 8 * u), style = Stroke(1.4f * u))
        val check = Path().apply {
            moveTo(5.2f * u, 8.2f * u)
            lineTo(7.1f * u, 10.1f * u)
            lineTo(10.8f * u, 6.2f * u)
        }
        drawPath(check, color, style = Stroke(1.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (waves) {
            listOf(5.5f, 8f, 10.5f).forEach { y ->
                val wave = Path().apply {
                    moveTo(15 * u, y * u)
                    cubicTo(16.2f * u, (y - 0.8f) * u, 17.4f * u, (y + 0.8f) * u, 18.6f * u, y * u)
                    cubicTo(19.8f * u, (y - 0.8f) * u, 21f * u, (y + 0.8f) * u, 22f * u, y * u)
                }
                drawPath(wave, color, style = Stroke(1f * u))
            }
        }
    }
}

@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(Otm.Slate) else Modifier.border(1.5.dp, Otm.InkFaint, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(OtmIcons.Check, "Selected", tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun Reaction(chip: ReactionChip, outgoing: Boolean, onClick: () -> Unit) {
    Text(
        "${chip.label} ${chip.count}",
        style = OtmType.Secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        color = if (chip.isChosen) Otm.SlateDeep else Otm.Ink,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    chip.isChosen -> Otm.Haze
                    outgoing -> Color(0xFFF3E4D6)
                    else -> Otm.Paper
                },
            )
            .then(if (chip.isChosen) Modifier.border(1.dp, Otm.RidgeLight, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(enabled = chip.emoji != null, onClick = onClick)
            .testTag("reaction-${chip.label}")
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Quoted message with a bar on the left, in bubbles and above the input. */
@Composable
fun ReplyQuote(reply: ReplyPreview, modifier: Modifier = Modifier) {
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Otm.Ridge))
        Column(Modifier.padding(start = 10.dp)) {
            reply.senderName?.let {
                Text(it, style = OtmType.Secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Otm.Slate, maxLines = 1)
            }
            Text(reply.text, style = OtmType.Preview, color = Otm.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** [text] with matches of the search marked. */
private fun highlighted(text: String, highlight: Highlight?): AnnotatedString {
    val query = highlight?.query?.trim().orEmpty()
    if (query.isEmpty()) return AnnotatedString(text)
    val style = if (highlight!!.current) SpanStyle(background = Otm.MarkNow) else SpanStyle(background = Otm.Mark)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            append(text.substring(from, at))
            withStyle(style) { append(text.substring(at, at + query.length)) }
            from = at + query.length
        }
        append(text.substring(from))
    }
}
