package dev.otmgram.ui.folder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.data.Folder
import dev.otmgram.data.FolderRepository
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

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(top = 24.dp)) {
            Text(
                "Pick your folder",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                when {
                    previousMissing -> "The folder you picked no longer exists. Pick another one."
                    folders.isEmpty() -> "You have no chat folders. Create one in the official Telegram app, " +
                        "it will show up here."
                    else -> "Otmgram only shows the chats of this folder. " +
                        "You can change it only by logging out."
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
            LazyColumn {
                items(folders, key = { it.id }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.name) },
                        modifier = Modifier.clickable { picked = folder.id }.testTag("folder-${folder.name}"),
                    )
                }
            }
        }
    }

    val folder = folders.find { it.id == picked } ?: return
    AlertDialog(
        onDismissRequest = { picked = null },
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text("Use \"${folder.name}\"?") },
        text = { Text("Otmgram will only show the chats of this folder. To change it, you'll need to log out.") },
        confirmButton = {
            TextButton(onClick = { viewModel.select(folder.id) }, modifier = Modifier.testTag("confirmFolder")) {
                Text("Use this folder")
            }
        },
        dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
    )
}

@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    private val folders: FolderRepository,
) : ViewModel() {

    fun select(id: Int) {
        viewModelScope.launch { folders.selectFolder(id) }
    }
}
