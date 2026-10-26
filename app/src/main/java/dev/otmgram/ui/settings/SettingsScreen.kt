package dev.otmgram.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.otmgram.R
import dev.otmgram.core.log.LogArchive
import dev.otmgram.data.CacheLimit
import dev.otmgram.data.MAX_HEADER_NAME
import dev.otmgram.data.StorageKind
import dev.otmgram.data.StorageUsage
import dev.otmgram.ui.chat.SelectionMark
import dev.otmgram.ui.format.formatSize
import dev.otmgram.ui.theme.Card
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.Chip
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmDialog
import dev.otmgram.ui.theme.OtmField
import dev.otmgram.ui.theme.OtmSwitch
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.ScreenBar
import dev.otmgram.ui.theme.SoftButton
import dev.otmgram.ui.theme.TextAction
import dev.otmgram.ui.theme.paper

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onChangeFolder: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    val interval by viewModel.checkInterval.collectAsStateWithLifecycle()
    val mode by viewModel.notificationMode.collectAsStateWithLifecycle()
    val push by viewModel.pushLine.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    // Back from installing a push app, or from picking one in it.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPush()
        onPauseOrDispose {}
    }
    val compress by viewModel.compressByDefault.collectAsStateWithLifecycle()
    val showText by viewModel.showMessageText.collectAsStateWithLifecycle()
    val cacheLimit by viewModel.cacheLimit.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var askShareLogs by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().paper().imePadding()) {
        ScreenBar(stringResource(R.string.settings), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(14.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            me?.let { account ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChatAvatar(account.id, account.name, account.photoFileId, account.minithumbnail)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(account.name, style = OtmType.Name, color = Otm.Ink)
                            account.phone?.let { Text(it, style = OtmType.Secondary, color = Otm.InkSoft) }
                            Text(stringResource(R.string.account_offline), style = OtmType.Secondary, color = Otm.InkSoft)
                        }
                    }
                }
            }

            folderName?.let { name ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Title(stringResource(R.string.folder_title), stringResource(R.string.folder_card_description, name))
                        }
                        TextAction(stringResource(R.string.folder_change), onChangeFolder, Modifier.testTag("changeFolder"))
                    }
                }
            }

            viewModel.header?.let { header ->
                Card {
                    Title(stringResource(R.string.header_title), stringResource(R.string.header_description))
                    OtmField(
                        value = header,
                        onValueChange = viewModel::editHeader,
                        placeholder = stringResource(R.string.header_none),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag("headerName"),
                        trailing = {
                            Text(
                                "${header.codePointCount(0, header.length)}/$MAX_HEADER_NAME",
                                style = OtmType.Time,
                                color = Otm.InkFaint,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        },
                    )
                    if (folderName != null && header != folderName) {
                        Text(
                            stringResource(R.string.header_reset, folderName.orEmpty()),
                            style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold),
                            color = Otm.SlateDeep,
                            modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = viewModel::resetHeader, role = Role.Button).padding(vertical = 4.dp).testTag("resetHeaderName"),
                        )
                    }
                }
            }

            NotificationsCard(
                mode = mode,
                interval = interval,
                push = push,
                onMode = viewModel::setNotificationMode,
                onInterval = viewModel::setCheckInterval,
                onPickPushApp = { activity?.let(viewModel::pickPushApp) },
                onRetryPush = viewModel::retryPush,
            )

            Card {
                Row(
                    modifier = Modifier.toggleable(showText, role = Role.Switch, onValueChange = viewModel::setShowMessageText).testTag("showMessageText"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Title(
                            stringResource(R.string.message_text_title),
                            if (showText) {
                                stringResource(R.string.message_text_on)
                            } else {
                                stringResource(R.string.message_text_off)
                            },
                        )
                    }
                    OtmSwitch(showText, onCheckedChange = null)
                }
            }

            Card {
                Row(
                    modifier = Modifier.toggleable(compress, role = Role.Switch, onValueChange = viewModel::setCompressByDefault).testTag("compressDefault"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Title(stringResource(R.string.compress_title), stringResource(R.string.compress_description))
                    }
                    OtmSwitch(compress, onCheckedChange = null)
                }
            }

            Card {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.storage), style = OtmType.Name, color = Otm.Ink, modifier = Modifier.weight(1f))
                    Text(viewModel.usage?.let { stringResource(R.string.storage_total, formatSize(it.total)) } ?: stringResource(R.string.storage_counting), style = OtmType.Secondary, color = Otm.InkSoft)
                }
                viewModel.usage?.let { StorageBar(it) }
                Text(stringResource(R.string.cache_title), style = OtmType.Preview.copy(fontWeight = FontWeight.Medium), color = Otm.Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CacheLimit.entries.forEach { option ->
                        Chip(stringResource(option.label), option == cacheLimit, { viewModel.setCacheLimit(option) }, Modifier.testTag("limit-${option.name}"))
                    }
                }
                Text(stringResource(R.string.cache_description), style = OtmType.Secondary, color = Otm.InkSoft)
                SoftButton(
                    if (viewModel.clearing) stringResource(R.string.clearing) else stringResource(R.string.clear_media),
                    { if (!viewModel.clearing) confirmClear = true },
                    Modifier.testTag("clearMedia"),
                )
            }

            Card {
                Title(stringResource(R.string.logs_title), stringResource(R.string.logs_description))
                val context = LocalContext.current
                SoftButton(
                    if (viewModel.sharingLogs) stringResource(R.string.logs_preparing) else stringResource(R.string.logs_share),
                    { askShareLogs = true },
                    Modifier.testTag("shareLogs"),
                )
            }

            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Otm.Card)
                    .clickable(role = Role.Button) { confirmLogout = true }
                    .testTag("logout"),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.log_out), style = OtmType.Button, color = Otm.Error)
            }
        }
    }

    if (confirmClear) {
        OtmDialog(
            title = stringResource(R.string.clear_title),
            text = stringResource(R.string.clear_text),
            onDismiss = { confirmClear = false },
            confirm = {
                TextAction(stringResource(R.string.clear), {
                    confirmClear = false
                    viewModel.clearMedia()
                }, Modifier.testTag("confirmClear"), color = Otm.Error)
            },
        )
    }
    if (askShareLogs) {
        val context = LocalContext.current
        var withTdlib by rememberSaveable { mutableStateOf(false) }
        OtmDialog(
            title = stringResource(R.string.logs_share),
            text = stringResource(R.string.logs_share_text),
            onDismiss = { askShareLogs = false },
            confirm = {
                TextAction(stringResource(R.string.logs_share_confirm), {
                    askShareLogs = false
                    viewModel.shareLogs(withTdlib) { zip -> context.startActivity(LogArchive.shareIntent(context, zip)) }
                }, Modifier.testTag("confirmShareLogs"))
            },
        ) {
            Row(
                modifier = Modifier
                    .toggleable(withTdlib, role = Role.Checkbox) { withTdlib = it }
                    .testTag("withTdlibLog")
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SelectionMark(withTdlib)
                Text(stringResource(R.string.logs_with_tdlib), style = OtmType.Body, color = Otm.Ink)
            }
        }
    }
    if (confirmLogout) {
        OtmDialog(
            title = stringResource(R.string.log_out_title),
            text = stringResource(R.string.log_out_text),
            onDismiss = { confirmLogout = false },
            confirm = { TextAction(stringResource(R.string.log_out), viewModel::logOut, Modifier.testTag("confirmLogout"), color = Otm.Error) },
        )
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = OtmType.Name, color = Otm.Ink)
        Text(subtitle, style = OtmType.Secondary, color = Otm.InkSoft)
    }
}

/** One bar split by kind of file, with a legend. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageBar(usage: StorageUsage) {
    val parts = StorageKind.entries.mapNotNull { kind -> usage.byKind[kind]?.takeIf { it > 0 }?.let { kind to it } }
    if (parts.isEmpty()) {
        Text(stringResource(R.string.storage_empty), style = OtmType.Secondary, color = Otm.InkSoft)
        return
    }
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.forEach { (kind, size) ->
            Box(Modifier.weight(size.toFloat().coerceAtLeast(1f)).height(10.dp).background(STORAGE_COLORS.getValue(kind)))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach { (kind, size) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(STORAGE_COLORS.getValue(kind)))
                Text(stringResource(kind.label), style = OtmType.Secondary, color = Otm.Ink)
                Text(formatSize(size), style = OtmType.Secondary, color = Otm.InkSoft)
            }
        }
    }
}

private val STORAGE_COLORS = mapOf(
    StorageKind.Videos to Otm.Ridge,
    StorageKind.Photos to Otm.Peach,
    StorageKind.Voice to Otm.ApricotDeep,
    StorageKind.Files to Color(0xFFBCC8CF),
    StorageKind.Other to Color(0xFFE2DCD3),
)
