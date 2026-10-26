package dev.otmgram.ui.chat

import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import dev.otmgram.ui.format.formatTime
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import dev.otmgram.ui.theme.OtmDialog
import dev.otmgram.ui.embed.MediaLink
import dev.otmgram.ui.media.openLink
import dev.otmgram.ui.highlight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.otmgram.R
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
    onOpenReply: (messageId: Long) -> Unit = {},
    onOpenLink: (MediaLink) -> Unit = {},
    highlight: Highlight? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
    /** Just jumped to: marked for a moment, so the eye finds it. */
    flashed: Boolean = false,
    onToggleSelected: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    val outgoing = message.isOutgoing
    val failed = message.status == MessageStatus.Failed
    // Comes on at once and fades slowly; to its own colour, as fading to transparent black greys it.
    val flash by animateColorAsState(
        Otm.MarkNow.copy(alpha = if (flashed) 0.55f else 0f),
        tween(if (flashed) FLASH_IN_MS else FLASH_OUT_MS),
        label = "flash",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) Otm.Haze.copy(alpha = 0.7f) else flash)
                .then(if (selecting) Modifier.toggleable(value = selected, onValueChange = { onToggleSelected() }, role = Role.Checkbox) else Modifier)
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
                    selecting = selecting,
                    onLongClick = onLongClick,
                    onReactionClick = onReactionClick,
                    onOpenMedia = if (selecting) onToggleSelected else onOpenMedia,
                    onPlayVoice = onPlayVoice,
                    onVideoNoteSound = onVideoNoteSound,
                    onOpenReply = onOpenReply,
                    onOpenLink = if (selecting) ({ onToggleSelected() }) else onOpenLink,
                    onCancelUpload = if (message.status == MessageStatus.Sending && !selecting) onDiscard else null,
                )
            }
        }
        if (failed && !selecting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp).testTag("failed-${message.id}"),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.not_sent), style = OtmType.Secondary, color = Otm.Error)
                TextAction(stringResource(R.string.retry), onRetry, Modifier.testTag("retry"))
                TextAction(stringResource(R.string.action_delete), onDiscard, Modifier.testTag("discard"), color = Otm.InkSoft)
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
    onOpenReply: (Long) -> Unit,
    onOpenLink: (MediaLink) -> Unit,
    onCancelUpload: (() -> Unit)?,
    selecting: Boolean,
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
            .then(if (failed) Modifier.border(1.dp, Otm.ErrorLine, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.message_actions))
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
            Text(stringResource(R.string.forwarded_from, it), style = OtmType.Time, fontStyle = FontStyle.Italic, color = Otm.Slate, modifier = textPadding)
        }
        message.replyTo?.let { reply ->
            val onClickReply = reply.messageId?.takeIf { !selecting }?.let { id -> { onOpenReply(id) } }
            ReplyQuote(reply, textPadding.padding(vertical = 4.dp).testTag("replyQuote"), onClickReply, onLongClick)
        }
        if (media != null) {
            MediaContent(
                media,
                audio,
                outgoing = outgoing,
                onOpen = onOpenMedia,
                onLongClick = onLongClick,
                onPlayVoice = onPlayVoice,
                onVideoNoteSound = onVideoNoteSound,
                onCancelUpload = onCancelUpload,
            )
        }
        if (message.text.isNotEmpty()) {
            val context = LocalContext.current
            var revealed by rememberSaveable(message.id) { mutableStateOf(emptySet<Int>()) }
            var confirmLink by rememberSaveable { mutableStateOf<String?>(null) }
            Text(
                messageText(
                    message.text,
                    // While selecting, taps select: no links.
                    if (selecting) message.spans.filter { it.kind !is SpanKind.Link && it.kind != SpanKind.Spoiler } else message.spans,
                    highlight,
                    revealed,
                    onReveal = { revealed = revealed + it },
                    onLink = { link -> if (link.hidden) confirmLink = link.url else openLink(context, link.url) },
                ),
                style = OtmType.Body,
                color = Otm.Ink,
                fontStyle = if (message.isMediaPlaceholder) FontStyle.Italic else FontStyle.Normal,
                modifier = textPadding
                    .then(if (media != null) Modifier.padding(top = 4.dp) else Modifier)
                    // Text with links handles touches itself, so the bubble wouldn't get a long
                    // press (the menu) on it.
                    .then(if (!selecting && message.spans.any { it.kind is SpanKind.Link || it.kind == SpanKind.Spoiler }) Modifier.onLongPressFirst(onLongClick) else Modifier),
            )
            // The text shown isn't the address: say where it really goes.
            confirmLink?.let { url ->
                OtmDialog(
                    title = stringResource(R.string.link_confirm_title),
                    text = url,
                    onDismiss = { confirmLink = null },
                    confirm = {
                        TextAction(stringResource(R.string.link_open), {
                            confirmLink = null
                            openLink(context, url)
                        }, Modifier.testTag("openLink"))
                    },
                )
            }
        }
        message.link?.let { card ->
            LinkCardView(card, onOpen = { onOpenLink(card.link) }, onLongClick = onLongClick, modifier = textPadding.padding(top = 6.dp, bottom = 2.dp))
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
            listOfNotNull(stringResource(R.string.edited).takeIf { message.isEdited }, formatTime(message.date)).joinToString(" "),
            style = OtmType.Time,
            color = if (message.status == MessageStatus.Failed) Otm.Error else Otm.Meta,
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
    // Read here: semantics blocks can't read resources.
    val sent = stringResource(R.string.status_sent)
    val read = stringResource(R.string.status_read)
    when (status) {
        MessageStatus.Sending -> Icon(OtmIcons.Clock, stringResource(R.string.status_sending), tint = Otm.InkFaint, modifier = Modifier.size(15.dp))
        MessageStatus.Failed -> Icon(OtmIcons.Alert, stringResource(R.string.status_not_sent), tint = Otm.Error, modifier = Modifier.size(15.dp))
        MessageStatus.Sent -> Postmark(Otm.InkFaint, waves = false, Modifier.semantics { contentDescription = sent })
        MessageStatus.Read -> Postmark(Otm.Slate, waves = true, Modifier.semantics { contentDescription = read })
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
        if (selected) Icon(OtmIcons.Check, stringResource(R.string.message_selected), tint = Color.White, modifier = Modifier.size(15.dp))
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
                    outgoing -> Otm.ApricotQuote
                    else -> Otm.Paper
                },
            )
            .then(if (chip.isChosen) Modifier.border(1.dp, Otm.RidgeLight, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(enabled = chip.emoji != null, onClick = onClick, role = Role.Button)
            .testTag("reaction-${chip.label}")
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Quoted message with a bar on the left, in bubbles and above the input. */
@Composable
fun ReplyQuote(reply: ReplyPreview, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    val label = stringResource(R.string.show_replied_message)
    val clicks = if (onClick != null) Modifier.combinedClickable(onClickLabel = label, role = Role.Button, onLongClick = onLongClick, onClick = onClick) else Modifier
    Row(modifier.height(IntrinsicSize.Min).then(clicks)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Otm.Ridge))
        Column(Modifier.padding(start = 10.dp)) {
            reply.senderName?.let {
                Text(it, style = OtmType.Secondary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Otm.Slate, maxLines = 1)
            }
            Text(reply.text, style = OtmType.Preview, color = Otm.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Calls [onLongPress] on a long press here, seeing touches before what's inside (a text's
 * links) does; after it fires, the rest of the touch is consumed, so the link doesn't open.
 */
private fun Modifier.onLongPressFirst(onLongPress: () -> Unit) = pointerInput(onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
        }
        if (ended == null) {
            onLongPress()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** [text] with its links and formatting, spoilers hidden until tapped, and search matches. */
private fun messageText(
    text: String,
    spans: List<TextSpan>,
    highlight: Highlight?,
    revealed: Set<Int>,
    onReveal: (Int) -> Unit,
    onLink: (SpanKind.Link) -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEachIndexed { i, span ->
        when (val kind = span.kind) {
            is SpanKind.Link -> addLink(
                LinkAnnotation.Clickable("link", LINK_STYLE) { onLink(kind) },
                span.start,
                span.end,
            )
            SpanKind.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), span.start, span.end)
            SpanKind.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
            SpanKind.Strikethrough -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), span.start, span.end)
            SpanKind.Code -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Otm.Haze), span.start, span.end)
            SpanKind.Spoiler -> if (i !in revealed) {
                // A solid bar until tapped.
                addStyle(SpanStyle(color = Otm.InkSoft, background = Otm.InkSoft), span.start, span.end)
                addLink(LinkAnnotation.Clickable("spoiler") { onReveal(i) }, span.start, span.end)
            }
        }
    }
    if (highlight != null) highlight(text, highlight.query, if (highlight.current) Otm.MarkNow else Otm.Mark)
}

private val LINK_STYLE = TextLinkStyles(SpanStyle(color = Otm.SlateDeep, textDecoration = TextDecoration.Underline))

private const val FLASH_IN_MS = 150
private const val FLASH_OUT_MS = 900
