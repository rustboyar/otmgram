package dev.otmgram.ui.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.otmgram.R
import dev.otmgram.data.CheckInterval
import dev.otmgram.data.NotificationMode
import dev.otmgram.ui.theme.Card
import dev.otmgram.ui.theme.Chip
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.TextAction

/** How messages arrive: instantly over a connection kept open, by checking every so often, or not at all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationsCard(
    mode: NotificationMode,
    interval: CheckInterval,
    onMode: (NotificationMode) -> Unit,
    onInterval: (CheckInterval) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(modifier) {
        Text(stringResource(R.string.notifications_title), style = OtmType.Name, color = Otm.Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NotificationMode.entries.forEach { option ->
                Chip(stringResource(option.label), option == mode, { onMode(option) }, Modifier.testTag("mode-${option.name}"))
            }
        }
        val description = when (mode) {
            NotificationMode.Instant -> R.string.mode_instant_description
            NotificationMode.Interval -> R.string.mode_interval_description
            NotificationMode.Off -> R.string.mode_off_description
        }
        Text(stringResource(description), style = OtmType.Secondary, color = Otm.InkSoft)
        when (mode) {
            NotificationMode.Instant -> BatteryCheck()
            NotificationMode.Interval -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckInterval.entries.forEach { option ->
                    Chip(stringResource(option.label), option == interval, { onInterval(option) }, Modifier.testTag("interval-${option.name}"))
                }
            }
            NotificationMode.Off -> {}
        }
    }
}

/**
 * Android's battery optimization can stop the connection while the phone is idle; Samsung's
 * more readily. While it applies, a line asks to turn it off for the app (one tap, Android's
 * own dialog). Checked again on coming back.
 */
@Composable
private fun BatteryCheck() {
    val context = LocalContext.current
    var restricted by remember { mutableStateOf(context.batteryRestricted()) }
    LifecycleResumeEffect(Unit) {
        restricted = context.batteryRestricted()
        onPauseOrDispose {}
    }
    if (!restricted) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.battery_restricted),
            style = OtmType.Secondary,
            color = Otm.Ink,
            modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("batteryRestricted"),
        )
        TextAction(stringResource(R.string.battery_allow), context::askBatteryExemption, Modifier.testTag("batteryAllow"))
    }
}

private fun Context.batteryRestricted() = !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

// Allowed outside Play, and what messaging apps without Google's push service ask for.
@SuppressLint("BatteryLife")
private fun Context.askBatteryExemption() {
    try {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
    } catch (e: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
