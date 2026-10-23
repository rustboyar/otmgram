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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.data.Folder
import dev.otmgram.data.FolderRepository
import dev.otmgram.ui.theme.Hero
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.PrimaryButton
import dev.otmgram.ui.theme.paper
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-time choice of the folder Otmgram shows. Changing it later requires logging out. */
@Composable
fun FolderPickerScreen(
    folders: List<Folder>,
    previousMissing: Boolean,
    viewModel: FolderPickerViewModel = hiltViewModel(),
) {
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(folders) { viewModel.loadCounts(folders) }

    Column(Modifier.fillMaxSize().paper().verticalScroll(rememberScrollState())) {
        Hero(150.dp)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (previousMissing) "Choose another folder" else "Choose one folder",
                style = OtmType.Headline,
                color = Otm.Ink,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                when {
                    previousMissing -> "The folder you picked no longer exists."
                    folders.isEmpty() -> "You have no chat folders yet. Create one in the official Telegram app and it will show up here."
                    else -> "Otmgram will show only the chats in it. To pick another folder later, log out and sign in again."
                },
                style = OtmType.Body,
                color = Otm.InkSoft,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Column(Modifier.selectableGroup().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                folders.forEach { folder ->
                    FolderCard(folder, viewModel.counts[folder.id], selected = folder.id == picked, onClick = { picked = folder.id })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Channels and forum topics in the folder are left out.",
                style = OtmType.Secondary,
                color = Otm.InkSoft,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            val folder = folders.find { it.id == picked }
            PrimaryButton(
                if (folder == null) "Use this folder" else "Use ${folder.name}",
                onClick = { folder?.let { viewModel.select(it.id) } },
                enabled = folder != null,
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
            count?.let { Text(if (it == 1) "1 chat" else "$it chats", style = OtmType.Secondary, color = Otm.InkSoft) }
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
    private val folders: FolderRepository,
) : ViewModel() {

    /** Chat count per folder id, filled in as they load. */
    val counts = mutableStateMapOf<Int, Int>()

    private val requested = mutableSetOf<Int>()

    fun loadCounts(list: List<Folder>) {
        list.filter { requested.add(it.id) }.forEach { folder ->
            viewModelScope.launch { folders.chatCount(folder.id)?.let { counts[folder.id] = it } }
        }
    }

    fun select(id: Int) {
        viewModelScope.launch { folders.selectFolder(id) }
    }
}
