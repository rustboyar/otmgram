package dev.otmgram.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.R
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.data.Folder
import dev.otmgram.data.FolderRepository
import dev.otmgram.ui.theme.Hero
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.PrimaryButton
import dev.otmgram.ui.theme.ScreenBar
import dev.otmgram.ui.theme.paper
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** The choice of the folder Otmgram shows, after login (or when it was deleted). */
@Composable
fun FolderPickerScreen(
    folders: List<Folder>,
    previousMissing: Boolean,
    viewModel: FolderPickerViewModel = hiltViewModel(),
) {
    LaunchedEffect(folders) { viewModel.loadCounts(folders) }
    FolderPicker(folders, viewModel.counts, current = null, previousMissing, onBack = null, onPicked = viewModel::select)
}

/** Another folder, from the settings. */
@Composable
fun ChangeFolderScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: FolderPickerViewModel = hiltViewModel()) {
    val folders by viewModel.folders.collectAsStateWithLifecycle(null)
    val current by viewModel.selectedFolderId.collectAsStateWithLifecycle(null)
    Column(modifier.fillMaxSize().paper()) {
        ScreenBar(stringResource(R.string.folder_title), onBack = onBack)
        // Loaded before the settings could open this: the chat list needs them.
        folders?.let { list ->
            LaunchedEffect(list) { viewModel.loadCounts(list) }
            FolderPicker(list, viewModel.counts, current, previousMissing = false, onBack = onBack, onPicked = { id ->
                viewModel.select(id)
                onBack()
            })
        }
    }
}

@Composable
private fun FolderPicker(
    folders: List<Folder>,
    /** Chats per folder, as they're counted. */
    counts: Map<Int, Int>,
    current: Int?,
    previousMissing: Boolean,
    onBack: (() -> Unit)?,
    onPicked: (Int) -> Unit,
) {
    var picked by rememberSaveable(current) { mutableStateOf(current) }

    Column(Modifier.fillMaxSize().paper().verticalScroll(rememberScrollState())) {
        if (onBack == null) Hero(150.dp) else Spacer(Modifier.height(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (onBack == null) Text(
                if (previousMissing) stringResource(R.string.folder_choose_another) else stringResource(R.string.folder_choose),
                style = OtmType.Headline,
                color = Otm.Ink,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                when {
                    previousMissing -> stringResource(R.string.folder_missing)
                    folders.isEmpty() -> stringResource(R.string.folder_none)
                    onBack != null -> stringResource(R.string.folder_change_description)
                    else -> stringResource(R.string.folder_description)
                },
                style = OtmType.Body,
                color = Otm.InkSoft,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Column(Modifier.selectableGroup().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                folders.forEach { folder ->
                    FolderCard(folder, counts[folder.id], selected = folder.id == picked, onClick = { picked = folder.id })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.folder_left_out),
                style = OtmType.Secondary,
                color = Otm.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            val folder = folders.find { it.id == picked }
            PrimaryButton(
                if (folder == null) stringResource(R.string.folder_use_this) else stringResource(R.string.folder_use, folder.name),
                onClick = { folder?.let { onPicked(it.id) } },
                enabled = folder != null && (onBack == null || folder.id != current),
                modifier = Modifier.testTag("confirmFolder"),
            )
        }
    }
}

@Composable
private fun FolderCard(folder: Folder, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Otm.Card)
            .then(if (selected) Modifier.border(2.dp, Otm.Slate, shape) else Modifier)
            .selectable(selected, onClick = onClick, role = Role.RadioButton)
            .testTag("folder-${folder.name}")
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(folder.name, style = OtmType.Name.copy(fontSize = OtmType.BarTitle.fontSize), color = Otm.Ink)
            count?.let { Text(pluralStringResource(R.plurals.chat_count, it, it), style = OtmType.Secondary, color = Otm.InkSoft) }
        }
        Box(
            Modifier
                .size(22.dp)
                .then(
                    if (selected) Modifier.border(7.dp, Otm.Slate, CircleShape)
                    else Modifier.border(1.5.dp, Otm.InkFaint, CircleShape),
                ),
        )
    }
}

@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    private val repository: FolderRepository,
    td: TelegramClient,
) : ViewModel() {

    val folders = repository.folders
    val selectedFolderId = repository.selectedFolderId

    /** Chat count per folder id, filled in as they load. */
    val counts = mutableStateMapOf<Int, Int>()

    private val requested = mutableSetOf<Int>()

    init {
        // It lives as long as the activity, past a logout, and the next account's folders
        // have the same ids: forget the counts.
        viewModelScope.launch {
            td.authorizationState.collect {
                if (it !is TdApi.AuthorizationStateReady) {
                    counts.clear()
                    requested.clear()
                }
            }
        }
    }

    fun loadCounts(list: List<Folder>) {
        list.filter { requested.add(it.id) }.forEach { folder ->
            viewModelScope.launch { repository.chatCount(folder.id)?.let { counts[folder.id] = it } }
        }
    }

    fun select(id: Int) {
        viewModelScope.launch { repository.selectFolder(id) }
    }
}
