package dev.otmgram.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.otmgram.R
import dev.otmgram.data.CheckInterval
import dev.otmgram.data.NotificationMode
import dev.otmgram.ui.theme.Card
import dev.otmgram.ui.theme.Chip
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.TextAction
import java.text.DateFormat

/** What the settings say about push, and what can be done about it. */
sealed interface PushLine {
    /** No push app installed. */
    data object GetApp : PushLine

    /** Several installed, none picked. */
    data object Pick : PushLine

    data class Connecting(val app: String) : PushLine

    data class Failed(val app: String) : PushLine

    data class Working(val app: String, val lastPushAt: Long?) : PushLine
}

/** How messages arrive: instantly by push, by checking every so often, or not at all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationsCard(
    mode: NotificationMode,
    interval: CheckInterval,
    push: PushLine?,
    onMode: (NotificationMode) -> Unit,
    onInterval: (CheckInterval) -> Unit,
    onPickPushApp: () -> Unit,
    onRetryPush: () -> Unit,
) {
    val context = LocalContext.current
    Card {
        Text(stringResource(R.string.notifications_title), style = OtmType.Name, color = Otm.Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NotificationMode.entries.forEach { option ->
                Chip(stringResource(option.label), option == mode, { onMode(option) }, Modifier.testTag("mode-${option.name}"))
            }
        }
        val description = when (mode) {
            NotificationMode.Push -> R.string.mode_push_description
            NotificationMode.Interval -> R.string.mode_interval_description
            NotificationMode.Off -> R.string.mode_off_description
        }
        Text(stringResource(description), style = OtmType.Secondary, color = Otm.InkSoft)
        when (mode) {
            NotificationMode.Push -> push?.let { line ->
                when (line) {
                    PushLine.GetApp -> Status(stringResource(R.string.push_get_app), stringResource(R.string.push_get_app_action), context::openNtfyListing)
                    PushLine.Pick -> Status(stringResource(R.string.push_pick), stringResource(R.string.push_pick_action), onPickPushApp)
                    is PushLine.Connecting -> Status(stringResource(R.string.push_connecting, line.app), stringResource(R.string.push_retry), onRetryPush)
                    is PushLine.Failed -> Status(stringResource(R.string.push_failed, line.app), stringResource(R.string.push_retry), onRetryPush)
                    is PushLine.Working -> Status(
                        line.lastPushAt?.let { stringResource(R.string.push_last, line.app, formatTime(it)) } ?: stringResource(R.string.push_working, line.app),
                    )
                }
            }
            NotificationMode.Interval -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckInterval.entries.forEach { option ->
                    Chip(stringResource(option.label), option == interval, { onInterval(option) }, Modifier.testTag("interval-${option.name}"))
                }
            }
            NotificationMode.Off -> {}
        }
    }
}

@Composable
private fun Status(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = OtmType.Secondary, color = Otm.Ink, modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("pushStatus"))
        if (action != null) TextAction(action, onAction, Modifier.testTag("pushAction"))
    }
}

/** Time for today, else the date. */
private fun formatTime(millis: Long): String =
    DateUtils.formatSameDayTime(millis, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT).toString()

/** ntfy in the phone's app store, or on F-Droid's site without one. */
private fun Context.openNtfyListing() {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$NTFY".toUri()))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Intent.ACTION_VIEW, "https://f-droid.org/packages/$NTFY/".toUri()))
    }
}

private const val NTFY = "io.heckel.ntfy"
