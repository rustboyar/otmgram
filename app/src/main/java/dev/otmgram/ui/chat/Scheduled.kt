package dev.otmgram.ui.chat

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.otmgram.R
import dev.otmgram.ui.format.sendsAt
import dev.otmgram.ui.rememberStrings
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmDialog
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmSheet
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.TextAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** A message waiting to be sent at [sendAt] (Unix time). */
data class ScheduledMessage(val message: MessageItem, val sendAt: Int)

/** What a time is being picked for: the message typed, or one scheduled already. */
sealed interface ScheduleTarget {
    data object Draft : ScheduleTarget

    data class Message(val messageId: Long) : ScheduleTarget
}

/** A quick choice in the "Send later" sheet. */
internal data class SendLaterOption(@StringRes val label: Int, val sendAt: Int)

/** In an hour; this evening at 20:00, if it's more than an hour away; tomorrow at 09:00. */
internal fun sendLaterOptions(now: ZonedDateTime): List<SendLaterOption> = buildList {
    add(SendLaterOption(R.string.send_later_in_hour, now.plusHours(1).toEpochSecond().toInt()))
    val evening = now.toLocalDate().atTime(EVENING).atZone(now.zone)
    if (now.plusHours(1).isBefore(evening)) add(SendLaterOption(R.string.send_later_evening, evening.toEpochSecond().toInt()))
    add(SendLaterOption(R.string.send_later_tomorrow, now.toLocalDate().plusDays(1).atTime(MORNING).atZone(now.zone).toEpochSecond().toInt()))
}

/** Above the input bar while the chat has scheduled messages; a tap lists them. */
@Composable
internal fun ScheduledBar(count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("scheduledBar")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(OtmIcons.Clock, null, tint = Otm.Slate, modifier = Modifier.size(20.dp))
        Text(pluralStringResource(R.plurals.scheduled_count, count, count), style = OtmType.Secondary, color = Otm.SlateDeep, modifier = Modifier.weight(1f))
        Icon(OtmIcons.ChevronUp, null, tint = Otm.InkFaint, modifier = Modifier.size(18.dp))
    }
}

/** When to send: a few quick choices, or a date and time picked. */
@Composable
internal fun SendLaterSheet(title: String, onPick: (sendAt: Int) -> Unit, onDismiss: () -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    if (picking) {
        DateTimePicker(onPick = onPick, onDismiss = onDismiss)
        return
    }
    val strings = rememberStrings()
    val options = remember { sendLaterOptions(ZonedDateTime.now()) }
    OtmSheet(onDismiss) {
        Text(title, style = OtmType.BarTitle.copy(fontSize = 20.sp), color = Otm.Ink, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        options.forEachIndexed { index, option ->
            SheetChoice(stringResource(option.label), sendsAt(option.sendAt, strings), "sendLater-$index") { onPick(option.sendAt) }
        }
        SheetChoice(stringResource(R.string.send_later_pick), null, "sendLaterPick") { picking = true }
    }
}

@Composable
private fun SheetChoice(label: String, detail: String?, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = OtmType.Body, color = Otm.Ink, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = OtmType.Secondary, color = Otm.InkSoft) }
    }
}

/** A day (today or within a year), then a time on it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePicker(onPick: (sendAt: Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val today = remember { LocalDate.now() }

    // The picker's days are UTC midnights.
    fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = today.utcMillis(),
        selectableDates = remember {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in today.utcMillis()..today.plusYears(1).utcMillis()

                override fun isSelectableYear(year: Int) = year in today.year..today.year + 1
            }
        },
    )
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val picked = day
    if (picked == null) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextAction(stringResource(R.string.next), {
                    day = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                }, Modifier.testTag("pickDate"))
            },
            dismissButton = { TextAction(stringResource(R.string.cancel), onDismiss) },
            colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = Otm.Sheet),
        ) {
            DatePicker(dateState, colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = Otm.Sheet))
        }
    } else {
        val soon = remember { LocalTime.now().plusHours(1) }
        val timeState = rememberTimePickerState(soon.hour, 0, is24Hour = DateFormat.is24HourFormat(context))
        OtmDialog(
            title = stringResource(R.string.send_later_time),
            onDismiss = onDismiss,
            confirm = {
                TextAction(stringResource(R.string.ok), {
                    onPick(picked.atTime(timeState.hour, timeState.minute).atZone(ZoneId.systemDefault()).toEpochSecond().toInt())
                }, Modifier.testTag("pickTime"))
            },
        ) {
            TimePicker(
                timeState,
                colors = TimePickerDefaults.colors(
                    periodSelectorSelectedContainerColor = Otm.Tile,
                    periodSelectorSelectedContentColor = Otm.SlateDeep,
                    periodSelectorBorderColor = Otm.Outline,
                ),
            )
        }
    }
}

/** The chat's scheduled messages, the soonest first, each with what can be done to it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduledSheet(
    scheduled: List<ScheduledMessage>,
    onSendNow: (messageId: Long) -> Unit,
    onChangeTime: (messageId: Long) -> Unit,
    onEdit: (MessageItem) -> Unit,
    onDelete: (messageId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = rememberStrings()
    OtmSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scheduled_title), style = OtmType.BarTitle.copy(fontSize = 20.sp), color = Otm.Ink, modifier = Modifier.weight(1f))
            IconBtn(OtmIcons.Close, stringResource(R.string.close), onDismiss, tint = Otm.InkSoft)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(scheduled, key = { it.message.id }) { item ->
                val message = item.message
                Column(Modifier.fillMaxWidth().testTag("scheduled-${message.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sendsAt(item.sendAt, strings), style = OtmType.Secondary, color = Otm.Slate)
                    Text(
                        message.text.ifEmpty { message.media?.label().orEmpty() },
                        style = OtmType.Body,
                        color = Otm.Ink,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextAction(stringResource(R.string.scheduled_send_now), { onSendNow(message.id) }, Modifier.testTag("sendNow"))
                        TextAction(stringResource(R.string.scheduled_change_time), { onChangeTime(message.id) }, Modifier.testTag("changeTime"))
                        if (message.media == null) TextAction(stringResource(R.string.action_edit), { onEdit(message) }, Modifier.testTag("editScheduled"))
                        TextAction(stringResource(R.string.action_delete), { onDelete(message.id) }, Modifier.testTag("deleteScheduled"), color = Otm.Error)
                    }
                }
            }
        }
    }
}

private val EVENING = LocalTime.of(20, 0)
private val MORNING = LocalTime.of(9, 0)
