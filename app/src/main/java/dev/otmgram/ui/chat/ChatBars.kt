package dev.otmgram.ui.chat

import androidx.compose.foundation.layout.ColumnScope
import dev.otmgram.ui.format.formatDuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.otmgram.R
import dev.otmgram.core.location.LiveShare
import dev.otmgram.core.media.RecorderState
import dev.otmgram.ui.theme.Banner
import dev.otmgram.ui.theme.Hairline
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.PillField
import dev.otmgram.ui.theme.PillHeight
import dev.otmgram.ui.theme.PillShape
import dev.otmgram.ui.theme.ScreenBarFrame
import dev.otmgram.ui.theme.SoftButton

/** Under the messages: a translucent bar with a hairline on top, over the navigation bar unless [overNavigationBar] is false. */
@Composable
private fun BottomBar(overNavigationBar: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.6f)).then(if (overNavigationBar) Modifier.navigationBarsPadding() else Modifier)) {
        Hairline()
        content()
    }
}

@Composable
internal fun SelectionBar(count: Int, onCancel: () -> Unit, onCopy: () -> Unit) {
    ScreenBarFrame {
        IconBtn(OtmIcons.Close, stringResource(R.string.selection_cancel), onCancel, tag = "cancelSelection")
        Text(pluralStringResource(R.plurals.selected_count, count, count), style = OtmType.BarTitle, color = Otm.Ink, modifier = Modifier.weight(1f).padding(start = 4.dp).testTag("selectedCount"))
        IconBtn(OtmIcons.Copy, stringResource(R.string.action_copy), onCopy, tint = Otm.Meta, tag = "copySelected")
    }
}

@Composable
internal fun SelectionActions(onForward: () -> Unit, onDelete: () -> Unit) {
    BottomBar {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(stringResource(R.string.action_forward), onForward, Modifier.weight(1f).testTag("forwardSelected"), icon = OtmIcons.Forward, height = 52.dp)
            SoftButton(stringResource(R.string.action_delete), onDelete, Modifier.weight(1f).testTag("deleteSelected"), icon = OtmIcons.Trash, background = Otm.ErrorSoft, color = Otm.Error, height = 52.dp)
        }
    }
}

@Composable
internal fun FindBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    ScreenBarFrame {
        IconBtn(OtmIcons.Back, stringResource(R.string.search_close), onClose, tag = "closeSearch")
        PillField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.search_chat),
            textStyle = OtmType.Body.copy(fontSize = OtmType.Name.fontSize),
            trailing = if (query.isEmpty()) null else {
                { IconBtn(OtmIcons.Close, stringResource(R.string.search_clear), { onQueryChange("") }, tint = Otm.InkSoft, size = 40.dp, iconSize = 20.dp) }
            },
            modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag("query"),
        )
    }
}

/** "3 of 4" and arrows to step through matches, in the input bar's place. */
@Composable
internal fun FindStepper(results: List<SearchResult>?, index: Int, query: String, onStep: (Int) -> Unit) {
    BottomBar {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).testTag("matches")) {
                Text(
                    when {
                        query.isBlank() -> stringResource(R.string.search_type)
                        results == null -> stringResource(R.string.search_searching)
                        results.isEmpty() -> stringResource(R.string.search_none)
                        // Counted from the oldest, as they read top to bottom.
                        else -> stringResource(R.string.search_position, results.size - index, results.size)
                    },
                    style = OtmType.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = Otm.Ink,
                )
            }
            val any = !results.isNullOrEmpty()
            IconBtn(OtmIcons.ChevronUp, stringResource(R.string.search_earlier), { onStep(1) }, tint = Otm.SlateDeep, background = Otm.Haze, enabled = any && index < results!!.lastIndex, tag = "olderMatch")
            Box(Modifier.size(8.dp))
            IconBtn(OtmIcons.ChevronDown, stringResource(R.string.search_later), { onStep(-1) }, tint = Otm.SlateDeep, background = Otm.Haze, enabled = any && index > 0, tag = "newerMatch")
        }
    }
}

/**
 * A pinned message under the chat's bar, the newest at first: a tap shows it and moves on
 * to the next older one. With several, a button lists them all.
 */
@Composable
internal fun PinnedBar(pinned: List<SearchResult>, shown: Int, onClick: () -> Unit, onList: () -> Unit) {
    val message = pinned.getOrNull(shown) ?: return
    Column(Modifier.fillMaxWidth().background(Otm.Card.copy(alpha = 0.7f))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReplyQuote(
                ReplyPreview(
                    if (pinned.size > 1) stringResource(R.string.pinned_position, pinned.size - shown, pinned.size) else stringResource(R.string.pinned_message),
                    message.text,
                ),
                Modifier.weight(1f).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("pinned"),
                onClick = onClick,
            )
            if (pinned.size > 1) IconBtn(OtmIcons.List, stringResource(R.string.pinned_list), onList, tag = "pinnedList", tint = Otm.InkSoft)
        }
        Hairline()
    }
}

@Composable
internal fun InputBar(
    /** Read here, so typing recomposes only this bar, not the whole chat. */
    readText: () -> String,
    onTextChange: (String) -> Unit,
    composer: Composer,
    chatTitle: String,
    stickersOpen: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onStickers: () -> Unit,
    onAttach: () -> Unit,
    onRecordVideoNote: () -> Unit,
    focus: FocusRequester,
) {
    val text = readText()
    // Over the sticker panel, which has the navigation bar's room.
    BottomBar(overNavigationBar = !stickersOpen) {
        val banner = when (composer) {
            Composer.New -> null
            is Composer.Reply -> Triple(
                OtmIcons.Reply,
                stringResource(R.string.reply_to, composer.message.senderName ?: if (composer.message.isOutgoing) stringResource(R.string.reply_to_yourself) else chatTitle),
                composer.message.text.ifEmpty { composer.message.media?.label().orEmpty() },
            )
            is Composer.Edit -> Triple(OtmIcons.Edit, stringResource(R.string.edit_message), composer.message.text)
        }
        banner?.let { (icon, label, quote) ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(icon, null, tint = Otm.Slate, modifier = Modifier.size(22.dp))
                ReplyQuote(ReplyPreview(label, quote), Modifier.weight(1f).padding(start = 10.dp))
                IconBtn(
                    OtmIcons.Close, stringResource(R.string.cancel),
                    onCancel,
                    tint = Otm.InkSoft,
                    tag = "cancelComposer",
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconBtn(OtmIcons.Attach, stringResource(R.string.attach), onAttach, tint = Otm.Meta, tag = "attach")
            PillField(
                value = text,
                onValueChange = onTextChange,
                placeholder = if (chatTitle.isEmpty()) stringResource(R.string.composer_message) else stringResource(R.string.composer_write_to, chatTitle),
                maxLines = 5,
                bordered = true,
                trailing = {
                    IconBtn(
                        if (stickersOpen) OtmIcons.Keyboard else OtmIcons.Smile,
                        if (stickersOpen) stringResource(R.string.keyboard) else stringResource(R.string.stickers),
                        onStickers,
                        tint = if (stickersOpen) Otm.SlateDeep else Otm.Meta,
                        size = 40.dp,
                        tag = "stickers",
                    )
                },
                modifier = Modifier.weight(1f).focusRequester(focus).testTag("input"),
            )
            Box(Modifier.size(4.dp))
            if (text.isBlank() && composer !is Composer.Edit) {
                IconBtn(OtmIcons.CircleCamera, stringResource(R.string.record_video_note), onRecordVideoNote, tint = Otm.SlateDeep, background = Otm.Tile, tag = "recordVideoNote")
            } else {
                // Editing a caption may leave it empty.
                val canSend = text.isNotBlank() || (composer is Composer.Edit && composer.message.media != null)
                IconBtn(
                    if (composer is Composer.Edit) OtmIcons.Check else OtmIcons.Send,
                    if (composer is Composer.Edit) stringResource(R.string.save) else stringResource(R.string.send),
                    onSend,
                    tint = Color.White,
                    background = if (canSend) Otm.Slate else Otm.RidgeLight,
                    enabled = canSend,
                    tag = "send",
                )
            }
        }
    }
}

@Composable
internal fun LiveShareBanner(share: LiveShare, onStop: () -> Unit) {
    val left = ((share.endsAt - rememberNow()) / 60).coerceAtLeast(0)
    Banner(
        OtmIcons.Pin,
        stringResource(R.string.live_sharing),
        Modifier.testTag("liveShareBanner"),
        subtitle = when {
            left >= 120 -> pluralStringResource(R.plurals.live_hours_left, left / 60, left / 60)
            left >= 1 -> stringResource(R.string.live_minutes_left, left)
            else -> stringResource(R.string.live_ending)
        },
        action = { SoftButton(stringResource(R.string.live_stop_short), onStop, Modifier.testTag("stopLiveLocation"), height = 36.dp) },
    )
}

/** Discard, a running timer with a live waveform, send. */
@Composable
internal fun RecordingBar(state: RecorderState, onCancel: () -> Unit, onSend: () -> Unit) {
    val levels = remember { mutableStateListOf<Int>() }
    LaunchedEffect(state.elapsedMs) {
        levels.add((state.level * 31).toInt().coerceIn(0, 31))
        if (levels.size > LIVE_BARS) levels.removeAt(0)
    }
    BottomBar {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp).testTag("recording"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconBtn(OtmIcons.Trash, stringResource(R.string.voice_discard), onCancel, tint = Otm.Error, tag = "cancelRecording")
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(PillHeight)
                    .clip(PillShape)
                    .background(Otm.Card)
                    .border(1.dp, Otm.Line, PillShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Canvas(Modifier.size(10.dp)) {
                    drawCircle(Otm.Rose.copy(alpha = 0.25f), radius = size.minDimension)
                    drawCircle(Otm.Rose)
                }
                Text(formatDuration((state.elapsedMs / 1000).toInt()), style = OtmType.Body, color = Otm.Ink)
                Waveform(levels.toList(), progress = 1f, played = Otm.Ridge, rest = Otm.Ridge, modifier = Modifier.weight(1f).height(24.dp), bars = LIVE_BARS)
            }
            IconBtn(OtmIcons.Send, stringResource(R.string.voice_send), onSend, tint = Color.White, background = Otm.Slate, tag = "sendRecording")
        }
    }
}

/** Bars of the live waveform while recording. */
private const val LIVE_BARS = 24
