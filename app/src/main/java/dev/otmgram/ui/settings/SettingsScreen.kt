package dev.otmgram.ui.settings

import android.util.Log
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.data.AppSettings
import dev.otmgram.data.AuthRepository
import dev.otmgram.data.CacheLimit
import dev.otmgram.data.CheckInterval
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.StorageKind
import dev.otmgram.data.StorageRepository
import dev.otmgram.data.StorageUsage
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
import javax.inject.Inject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    val interval by viewModel.checkInterval.collectAsStateWithLifecycle()
    val compress by viewModel.compressByDefault.collectAsStateWithLifecycle()
    val cacheLimit by viewModel.cacheLimit.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().paper().imePadding()) {
        ScreenBar("Settings", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(14.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            me?.let { user ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChatAvatar(user.id, user.firstName, user.profilePhoto?.small?.id, user.profilePhoto?.minithumbnail)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(listOf(user.firstName, user.lastName).filter { it.isNotEmpty() }.joinToString(" "), style = OtmType.Name, color = Otm.Ink)
                            Text(
                                listOfNotNull(user.phoneNumber.takeIf { it.isNotEmpty() }?.let { "+$it" }, folderName?.let { "folder $it" }).joinToString(" · "),
                                style = OtmType.Secondary,
                                color = Otm.InkSoft,
                            )
                            Text("Always shown as offline", style = OtmType.Secondary, color = Otm.InkSoft)
                        }
                    }
                }
            }

            viewModel.header?.let { header ->
                Card {
                    Title("Name on top", "Shown over the chat list. Leave it empty for no name. The folder in Telegram keeps its name.")
                    OtmField(
                        value = header,
                        onValueChange = viewModel::editHeader,
                        placeholder = "No name",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag("headerName"),
                    )
                    if (folderName != null && header != folderName) {
                        Text(
                            "Use the folder's name, $folderName",
                            style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold),
                            color = Otm.SlateDeep,
                            modifier = Modifier.clickable(onClick = viewModel::resetHeader).padding(vertical = 4.dp).testTag("resetHeaderName"),
                        )
                    }
                }
            }

            Card {
                Title("Check for new messages", "Notifications arrive at most this often.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CheckInterval.entries.forEach { option ->
                        Chip(option.label, option == interval, { viewModel.setCheckInterval(option) }, Modifier.testTag("interval-${option.name}"))
                    }
                }
            }

            Card {
                Row(
                    modifier = Modifier.toggleable(compress, role = Role.Switch, onValueChange = viewModel::setCompressByDefault).testTag("compressDefault"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Title("Compress photos and videos", "Smaller and faster to send. You can turn it off for each message.")
                    }
                    OtmSwitch(compress, onCheckedChange = null)
                }
            }

            Card {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Storage", style = OtmType.Name, color = Otm.Ink, modifier = Modifier.weight(1f))
                    Text(viewModel.usage?.let { "${formatSize(it.total)} on this phone" } ?: "Counting…", style = OtmType.Secondary, color = Otm.InkSoft)
                }
                viewModel.usage?.let { StorageBar(it) }
                Text("Keep downloaded media up to", style = OtmType.Preview.copy(fontWeight = FontWeight.Medium), color = Otm.Ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CacheLimit.entries.forEach { option ->
                        Chip(option.label, option == cacheLimit, { viewModel.setCacheLimit(option) }, Modifier.testTag("limit-${option.name}"))
                    }
                }
                Text("Older files go first. They download again when you open them.", style = OtmType.Secondary, color = Otm.InkSoft)
                SoftButton(
                    if (viewModel.clearing) "Clearing…" else "Clear downloaded media",
                    { if (!viewModel.clearing) confirmClear = true },
                    Modifier.testTag("clearMedia"),
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
                Text("Log out", style = OtmType.Button, color = Otm.Error)
            }
        }
    }

    if (confirmClear) {
        OtmDialog(
            title = "Clear downloaded media?",
            text = "Photos, videos and files are removed from this phone. They stay in Telegram and download again when you open them.",
            onDismiss = { confirmClear = false },
            confirm = {
                TextAction("Clear", {
                    confirmClear = false
                    viewModel.clearMedia()
                }, Modifier.testTag("confirmClear"), color = Otm.Error)
            },
        )
    }
    if (confirmLogout) {
        OtmDialog(
            title = "Log out?",
            text = "Otmgram will forget this account and remove its downloaded media from the phone. Your chats stay in Telegram.",
            onDismiss = { confirmLogout = false },
            confirm = { TextAction("Log out", viewModel::logOut, Modifier.testTag("confirmLogout"), color = Otm.Error) },
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
        Text("Nothing downloaded yet.", style = OtmType.Secondary, color = Otm.InkSoft)
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
                Text(kind.label, style = OtmType.Secondary, color = Otm.Ink)
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val td: TdClient,
    private val auth: AuthRepository,
    private val folders: FolderRepository,
    private val settings: AppSettings,
    private val storage: StorageRepository,
) : ViewModel() {

    private val _me = MutableStateFlow<TdApi.User?>(null)
    val me: StateFlow<TdApi.User?> = _me.asStateFlow()

    /** The picked folder's own name in Telegram. */
    val folderName: StateFlow<String?> = combine(folders.folders, folders.selectedFolderId) { all, id ->
        all?.find { it.id == id }?.name
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val checkInterval = settings.checkInterval.stateIn(viewModelScope, SharingStarted.Eagerly, CheckInterval.Minutes15)
    val compressByDefault = settings.compressByDefault.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val cacheLimit = settings.cacheLimit.stateIn(viewModelScope, SharingStarted.Eagerly, CacheLimit.Gb2)

    /** The name on top as being edited; null until loaded. */
    var header by mutableStateOf<String?>(null)
        private set

    var usage by mutableStateOf<StorageUsage?>(null)
        private set

    var clearing by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { _me.value = td.send(TdApi.GetMe()) }
        viewModelScope.launch { header = folders.displayName.first() }
        loadUsage()
    }

    fun editHeader(name: String) {
        header = name
        viewModelScope.launch { folders.setHeaderName(name) }
    }

    fun resetHeader() {
        header = folderName.value.orEmpty()
        viewModelScope.launch { folders.setHeaderName(null) }
    }

    fun setCheckInterval(interval: CheckInterval) {
        viewModelScope.launch { settings.setCheckInterval(interval) }
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
            Log.w("SettingsViewModel", "Settings action failed", e)
        }
    }
}
