package dev.otmgram.ui.home

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Placeholder until the chat list (M2): shows the account and allows logging out. */
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val me by viewModel.me.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Logged in", style = MaterialTheme.typography.headlineSmall)
            me?.let { Text("${it.firstName} ${it.lastName}".trim() + " · +${it.phoneNumber}") }
            Button(onClick = { confirmLogout = true }, modifier = Modifier.testTag("logout")) {
                Text("Log out")
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            // Dialogs are separate windows; expose their test tags too.
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text("Log out?") },
            text = { Text("All local data of this account will be deleted from the device.") },
            confirmButton = {
                TextButton(onClick = viewModel::logOut, modifier = Modifier.testTag("confirmLogout")) {
                    Text("Log out")
                }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val td: TdClient,
    private val auth: AuthRepository,
) : ViewModel() {

    private val _me = MutableStateFlow<TdApi.User?>(null)
    val me = _me.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _me.value = td.send(TdApi.GetMe())
            } catch (e: TdException) {
                Log.w("HomeViewModel", "GetMe failed", e)
            }
        }
    }

    fun logOut() {
        viewModelScope.launch {
            try {
                auth.logOut()
            } catch (e: TdException) {
                Log.w("HomeViewModel", "LogOut failed", e)
            }
        }
    }
}
