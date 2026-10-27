package dev.otmgram.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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
import dev.otmgram.ui.profile.ProfileScreen
import dev.otmgram.ui.settings.SettingsScreen
import dev.otmgram.ui.theme.LoadingScreen
import dev.otmgram.ui.theme.Otm
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
            if (viewModel.isInFolder(openChatId)) navController.navigate(ChatRoute(openChatId))
        }
    }
    NavHost(
        navController,
        startDestination = ChatsRoute,
        enterTransition = { if (targetState.isOverlay()) overlayIn else pageIn },
        exitTransition = { if (targetState.isOverlay()) ExitTransition.KeepUntilTransitionsFinished else pageAside },
        popEnterTransition = { if (initialState.isOverlay()) EnterTransition.None else pageBack },
        popExitTransition = { if (initialState.isOverlay()) overlayOut else pageOut },
    ) {
        composable<ChatsRoute> {
            ChatListScreen(
                onOpenChat = { navController.navigate(ChatRoute(it)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
        screen<SettingsRoute>(navController) {
            SettingsScreen(onBack = navController::popBackStack, onChangeFolder = { navController.navigate(FolderRoute) })
        }
        screen<FolderRoute>(navController) {
            ChangeFolderScreen(onBack = navController::popBackStack)
        }
        screen<ChatRoute>(navController) {
            ChatScreen(
                onBack = navController::popBackStack,
                onOpenMedia = { chatId, messageId -> navController.navigate(MediaRoute(chatId, messageId)) },
                onRecordVideoNote = { chatId -> navController.navigate(VideoNoteRoute(chatId)) },
                onOpenMap = { chatId, messageId -> navController.navigate(MapRoute(chatId, messageId)) },
                onOpenEmbed = { url -> navController.navigate(EmbedRoute(url)) },
                onOpenProfile = { navController.navigate(it) },
            )
        }
        screen<ProfileRoute>(navController) {
            ProfileScreen(
                onBack = navController::popBackStack,
                onOpenProfile = { userId -> navController.navigate(ProfileRoute(userId = userId)) },
                onOpenChat = { chatId -> navController.navigate(ChatRoute(chatId)) },
            )
        }
        screen<EmbedRoute>(navController) { entry ->
            EmbedPlayerScreen(entry.toRoute<EmbedRoute>().url, onBack = navController::popBackStack)
        }
        screen<MapRoute>(navController) {
            MapScreen(onBack = navController::popBackStack)
        }
        screen<VideoNoteRoute>(navController) {
            VideoNoteScreen(onDone = navController::popBackStack)
        }
        screen<MediaRoute>(navController) {
            MediaViewerScreen(onBack = navController::popBackStack)
        }
    }
}

// A page slides in from the right over the one it came from, which moves aside a little, and
// back reverses it, as in Telegram. Full-screen media grows in over the screen below instead.
private const val PAGE_MS = 280
private const val OVERLAY_MS = 200
private val PAGE_EDGE = 12.dp
private val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val pageIn = slideInHorizontally(tween(PAGE_MS, easing = Decelerate)) { it }
private val pageAside = slideOutHorizontally(tween(PAGE_MS, easing = Decelerate)) { -it / 4 }
private val pageBack = slideInHorizontally(tween(PAGE_MS, easing = Decelerate)) { -it / 4 }
private val pageOut = slideOutHorizontally(tween(PAGE_MS, easing = Decelerate)) { it }
private val overlayIn = fadeIn(tween(OVERLAY_MS)) + scaleIn(tween(OVERLAY_MS, easing = Decelerate), initialScale = 0.92f)
private val overlayOut = fadeOut(tween(OVERLAY_MS)) + scaleOut(tween(OVERLAY_MS), targetScale = 0.92f)

private fun NavBackStackEntry.isOverlay() = destination.run { hasRoute<MediaRoute>() || hasRoute<VideoNoteRoute>() || hasRoute<EmbedRoute>() }

/**
 * A screen above the chat list. Back leaves it the way its own back arrow does, with the
 * usual animation. Left to Navigation, the device's back button (animated as a predictive back
 * since Android 16) and the gesture went through a predictive back driven by the system's
 * progress, which then hands over to the pop animation, and sometimes stalled midway. First
 * on the screen, so its own back handling (closing search, a selection, a full-screen video)
 * still comes first.
 */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    navController: NavController,
    crossinline content: @Composable (NavBackStackEntry) -> Unit,
) = composable<T> { entry ->
    // Not for a screen already on its way out.
    BackHandler { if (navController.currentBackStackEntry?.id == entry.id) navController.popBackStack() }
    if (entry.isOverlay()) {
        content(entry)
    } else {
        Box(Modifier.fillMaxSize().pageEdge()) { content(entry) }
    }
}

/** A shadow just left of a page: seen only while it slides over the one below, whose paper is the same. */
private fun Modifier.pageEdge() = drawBehind {
    val width = PAGE_EDGE.toPx()
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, Otm.Ink.copy(alpha = 0.12f)), startX = -width, endX = 0f),
        topLeft = Offset(-width, 0f),
        size = Size(width, size.height),
    )
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
