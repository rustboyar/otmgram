package dev.otmgram.ui.settings

import android.content.Context
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.R
import dev.otmgram.core.launchLogged
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.log.LogArchive
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.data.AppSettings
import dev.otmgram.data.AuthRepository
import dev.otmgram.data.CacheLimit
import dev.otmgram.data.CheckInterval
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.MAX_HEADER_NAME
import dev.otmgram.data.StorageKind
import dev.otmgram.data.StorageRepository
import dev.otmgram.data.StorageUsage
import dev.otmgram.data.limitTo
import dev.otmgram.ui.media.formatSize
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    val interval by viewModel.checkInterval.collectAsStateWithLifecycle()
    val compress by viewModel.compressByDefault.collectAsStateWithLifecycle()
    val showText by viewModel.showMessageText.collectAsStateWithLifecycle()
    val cacheLimit by viewModel.cacheLimit.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

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
                            Text(
                                listOfNotNull(account.phone, folderName?.let { stringResource(R.string.account_folder, it) }).joinToString(" · "),
                                style = OtmType.Secondary,
                                color = Otm.InkSoft,
                            )
                            Text(stringResource(R.string.account_offline), style = OtmType.Secondary, color = Otm.InkSoft)
                        }
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
                            modifier = Modifier.clickable(onClick = viewModel::resetHeader).padding(vertical = 4.dp).testTag("resetHeaderName"),
                        )
                    }
                }
            }

            Card {
                Title(stringResource(R.string.interval_title), stringResource(R.string.interval_description))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckInterval.entries.forEach { option ->
                        Chip(stringResource(option.label), option == interval, { viewModel.setCheckInterval(option) }, Modifier.testTag("interval-${option.name}"))
                    }
                }
            }

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
                    {
                        viewModel.shareLogs { zip -> context.startActivity(LogArchive.shareIntent(context, zip)) }
                    },
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
                    .clickable { confirmLogout = true }
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

private const val TAG = "SettingsViewModel"

private val STORAGE_COLORS = mapOf(
    StorageKind.Videos to Otm.Ridge,
    StorageKind.Photos to Otm.Peach,
    StorageKind.Voice to Otm.ApricotDeep,
    StorageKind.Files to Color(0xFFBCC8CF),
    StorageKind.Other to Color(0xFFE2DCD3),
)

/** The logged-in account, for the card on top. */
data class Account(val id: Long, val name: String, val phone: String?, val photoFileId: Int?, val minithumbnail: ByteArray?)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    private val auth: AuthRepository,
    private val folders: FolderRepository,
    private val settings: AppSettings,
    private val storage: StorageRepository,
) : ViewModel() {

    private val _me = MutableStateFlow<Account?>(null)
    val me: StateFlow<Account?> = _me.asStateFlow()

    /** The picked folder's own name in Telegram. */
    val folderName: StateFlow<String?> = combine(folders.folders, folders.selectedFolderId) { all, id ->
        all?.find { it.id == id }?.name
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val checkInterval = settings.checkInterval.stateIn(viewModelScope, SharingStarted.Eagerly, CheckInterval.Minutes15)
    val compressByDefault = settings.compressByDefault.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val showMessageText = settings.showMessageText.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val cacheLimit = settings.cacheLimit.stateIn(viewModelScope, SharingStarted.Eagerly, CacheLimit.Gb2)

    /** The name on top as being edited; null until loaded. */
    var header by mutableStateOf<String?>(null)
        private set

    var usage by mutableStateOf<StorageUsage?>(null)
        private set

    var clearing by mutableStateOf(false)
        private set

    var sharingLogs by mutableStateOf(false)
        private set

    init {
        viewModelScope.launchLogged(TAG) {
            val user = td.send(TdApi.GetMe())
            _me.value = Account(
                id = user.id,
                name = listOf(user.firstName, user.lastName).filter { it.isNotEmpty() }.joinToString(" "),
                phone = user.phoneNumber.takeIf { it.isNotEmpty() }?.let { "+$it" },
                photoFileId = user.profilePhoto?.small?.id,
                minithumbnail = user.profilePhoto?.minithumbnail?.data,
            )
        }
        viewModelScope.launch { header = folders.displayName.first() }
        loadUsage()
    }

    fun editHeader(name: String) {
        val limited = name.limitTo(MAX_HEADER_NAME)
        header = limited
        viewModelScope.launch { folders.setHeaderName(limited) }
    }

    fun resetHeader() {
        header = folderName.value.orEmpty()
        viewModelScope.launch { folders.setHeaderName(null) }
    }

    fun setCheckInterval(interval: CheckInterval) {
        viewModelScope.launch { settings.setCheckInterval(interval) }
    }

    fun setShowMessageText(show: Boolean) {
        viewModelScope.launch { settings.setShowMessageText(show) }
    }

    fun setCompressByDefault(compress: Boolean) {
        viewModelScope.launch { settings.setCompressByDefault(compress) }
    }

    fun setCacheLimit(limit: CacheLimit) {
        viewModelScope.launch {
            settings.setCacheLimit(limit)
            quietly { storage.applyLimit() }
            loadUsage()
        }
    }

    fun clearMedia() {
        clearing = true
        viewModelScope.launch {
            quietly { storage.clear() }
            clearing = false
            loadUsage()
        }
    }

    /** Zips the logs, then hands the file to [share]. */
    fun shareLogs(share: (File) -> Unit) {
        if (sharingLogs) return
        sharingLogs = true
        viewModelScope.launchLogged(TAG) {
            try {
                share(LogArchive.create(context))
            } finally {
                sharingLogs = false
            }
        }
    }

    fun logOut() {
        viewModelScope.launch { quietly { auth.logOut() } }
    }

    private fun loadUsage() {
        viewModelScope.launch { quietly { usage = storage.usage() } }
    }

    /** TDLib errors here aren't worth more than a log line. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: TdException) {
            AppLog.w(TAG, "Settings action failed", e)
        }
    }
}
