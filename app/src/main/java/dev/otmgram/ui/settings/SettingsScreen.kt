package dev.otmgram.ui.settings

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.data.AuthRepository
import dev.otmgram.data.FolderRepository
import dev.otmgram.ui.theme.Card
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmDialog
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.ScreenBar
import dev.otmgram.ui.theme.TextAction
import dev.otmgram.ui.theme.paper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().paper()) {
        ScreenBar("Settings", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(14.dp).navigationBarsPadding(),
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
            Text(
                "To show another folder, log out and sign in again.",
                style = OtmType.Secondary,
                color = Otm.InkSoft,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Spacer(Modifier.weight(1f))
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

    if (confirmLogout) {
        OtmDialog(
            title = "Log out?",
            text = "Otmgram will forget this account and remove its downloaded media from the phone. Your chats stay in Telegram.",
            onDismiss = { confirmLogout = false },
            confirm = { TextAction("Log out", viewModel::logOut, Modifier.testTag("confirmLogout"), color = Otm.Error) },
        )
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val td: TdClient,
    private val auth: AuthRepository,
    folders: FolderRepository,
) : ViewModel() {

    private val _me = MutableStateFlow<TdApi.User?>(null)
    val me: StateFlow<TdApi.User?> = _me.asStateFlow()

    val folderName: StateFlow<String?> = combine(folders.folders, folders.selectedFolderId) { all, id ->
        all?.find { it.id == id }?.name
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { _me.value = td.send(TdApi.GetMe()) }
    }

    fun logOut() {
        viewModelScope.launch {
            try {
                auth.logOut()
            } catch (e: TdException) {
                Log.w("SettingsViewModel", "LogOut failed", e)
            }
        }
    }
}
