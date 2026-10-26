package dev.otmgram.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
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
import dev.otmgram.ui.chat.ChatScreen
import dev.otmgram.ui.chats.ChatListScreen
import dev.otmgram.ui.embed.EmbedPlayerScreen
import dev.otmgram.ui.folder.ChangeFolderScreen
import dev.otmgram.ui.folder.FolderPickerScreen
import dev.otmgram.ui.map.MapScreen
import dev.otmgram.ui.media.MediaViewerScreen
import dev.otmgram.ui.settings.SettingsScreen
import dev.otmgram.ui.theme.LoadingScreen
import dev.otmgram.ui.videonote.VideoNoteScreen
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
fun AppRoot(openChatId: Long? = null, viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        RootState.Loading -> LoadingScreen()
        is RootState.Auth -> AuthScreen(s.state, hiltViewModel())
        is RootState.PickFolder -> FolderPickerScreen(s.folders, s.previousMissing)
        RootState.Chats -> MainNavHost(openChatId)
    }
}

@Composable
private fun MainNavHost(openChatId: Long?, viewModel: RootViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    // Once: not again after a rotation, which keeps the activity's intent.
    var openedChat by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openChatId) {
        if (openChatId != null && !openedChat) {
            openedChat = true
            // Only a chat of the folder: any app can start this activity with any id.
            if (viewModel.isInFolder(openChatId)) navController.navigate("chat/$openChatId")
        }
    }
    NavHost(navController, startDestination = "chats") {
        composable("chats") {
            ChatListScreen(
                onOpenChat = { navController.navigate("chat/$it") },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        screen(navController, "settings") {
            SettingsScreen(onBack = navController::popBackStack, onChangeFolder = { navController.navigate("folder") })
        }
        screen(navController, "folder") {
            ChangeFolderScreen(onBack = navController::popBackStack)
        }
        screen(navController, "chat/{chatId}", arguments = listOf(navArgument("chatId") { type = NavType.LongType })) {
            ChatScreen(
                onBack = navController::popBackStack,
                onOpenMedia = { chatId, messageId -> navController.navigate("media/$chatId/$messageId") },
                onRecordVideoNote = { chatId -> navController.navigate("videoNote/$chatId") },
                onOpenMap = { chatId, messageId -> navController.navigate("map/$chatId/$messageId") },
                onOpenEmbed = { url -> navController.navigate("embed?url=${Uri.encode(url)}") },
            )
        }
        screen(navController, "embed?url={url}", arguments = listOf(navArgument("url") { type = NavType.StringType })) { entry ->
            EmbedPlayerScreen(entry.arguments?.getString("url").orEmpty(), onBack = navController::popBackStack)
        }
        screen(
            navController,
            "map/{chatId}/{messageId}",
            arguments = listOf(
                navArgument("chatId") { type = NavType.LongType },
                navArgument("messageId") { type = NavType.LongType },
            ),
        ) {
            MapScreen(onBack = navController::popBackStack)
        }
        screen(navController, "videoNote/{chatId}", arguments = listOf(navArgument("chatId") { type = NavType.LongType })) {
            VideoNoteScreen(onDone = navController::popBackStack)
        }
        screen(
            navController,
            "media/{chatId}/{messageId}",
            arguments = listOf(
                navArgument("chatId") { type = NavType.LongType },
                navArgument("messageId") { type = NavType.LongType },
            ),
        ) {
            MediaViewerScreen(onBack = navController::popBackStack)
        }
    }
}

/**
 * A screen above the chat list. Back leaves it the way its own back arrow does, with the
 * usual animation. Left to Navigation, the device's back button (animated as a predictive back
 * since Android 16) and the gesture went through a predictive back driven by the system's
 * progress, which then hands over to the pop animation, and sometimes stalled midway. First
 * on the screen, so its own back handling (closing search, a selection, a full-screen video)
 * still comes first.
 */
private fun NavGraphBuilder.screen(
    navController: NavController,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route, arguments) { entry ->
    // Not for a screen already on its way out.
    BackHandler { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() }
    content(entry)
}

@HiltViewModel
class RootViewModel @Inject constructor(
    auth: AuthRepository,
    private val folders: FolderRepository,
) : ViewModel() {

    suspend fun isInFolder(chatId: Long): Boolean = folders.isInFolder(chatId)

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
