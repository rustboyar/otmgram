package dev.otmgram.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.data.AuthRepository
import dev.otmgram.data.AuthState
import dev.otmgram.data.Folder
import dev.otmgram.data.FolderRepository
import dev.otmgram.ui.auth.AuthScreen
import dev.otmgram.ui.auth.LoadingScreen
import dev.otmgram.ui.chat.ChatScreen
import dev.otmgram.ui.chats.ChatListScreen
import dev.otmgram.ui.folder.FolderPickerScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface RootState {
    data object Loading : RootState
    data class Auth(val state: AuthState) : RootState

    /** No folder picked yet, or the picked one was deleted ([previousMissing]). */
    data class PickFolder(val folders: List<Folder>, val previousMissing: Boolean) : RootState
    data object Chats : RootState
}

@Composable
fun AppRoot(viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        RootState.Loading -> LoadingScreen()
        is RootState.Auth -> AuthScreen(s.state, hiltViewModel())
        is RootState.PickFolder -> FolderPickerScreen(s.folders, s.previousMissing)
        RootState.Chats -> MainNavHost()
    }
}

@Composable
private fun MainNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "chats") {
        composable("chats") {
            ChatListScreen(onOpenChat = { navController.navigate("chat/$it") })
        }
        composable("chat/{chatId}", arguments = listOf(navArgument("chatId") { type = NavType.LongType })) {
            ChatScreen(onBack = navController::popBackStack)
        }
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    auth: AuthRepository,
    folders: FolderRepository,
) : ViewModel() {

    val state: StateFlow<RootState> = combine(
        auth.state,
        folders.folders,
        folders.selectedFolderId,
    ) { authState, allFolders, selectedId ->
        when {
            authState != AuthState.Ready -> RootState.Auth(authState)
            allFolders == null -> RootState.Loading
            selectedId == null -> RootState.PickFolder(allFolders, previousMissing = false)
            allFolders.none { it.id == selectedId } -> RootState.PickFolder(allFolders, previousMissing = true)
            else -> RootState.Chats
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState.Loading)
}
