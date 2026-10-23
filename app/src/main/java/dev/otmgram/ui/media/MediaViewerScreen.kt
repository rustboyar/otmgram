package dev.otmgram.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.td.TdClient
import dev.otmgram.data.FileRepository
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Full-screen photo (zoomable) or video of a message. */
@Composable
fun MediaViewerScreen(onBack: () -> Unit, viewModel: MediaViewerViewModel = hiltViewModel()) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val content = viewModel.content) {
            is ViewerContent.Photo -> ZoomablePhoto(content)
            is ViewerContent.Video -> FullVideo(content)
            null -> Unit
        }
        TextButton(onClick = onBack, modifier = Modifier.safeDrawingPadding().testTag("closeViewer")) {
            Text("✕", color = Color.White)
        }
    }
}

@Composable
private fun ZoomablePhoto(photo: ViewerContent.Photo) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    TdImage(
        fileId = photo.fileId,
        minithumbnail = photo.minithumbnail,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .transformable(state)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            .testTag("viewerPhoto"),
    )
}

@Composable
private fun FullVideo(video: ViewerContent.Video) {
    val file = rememberFile(video.fileId, FileRepository.PRIORITY_VIEWER)
    val local = file?.local
    if (local?.isDownloadingCompleted != true) {
        val percent = if (file == null || file.size == 0L) 0 else local!!.downloadedSize * 100 / file.size
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Downloading $percent%", color = Color.White)
        }
        return
    }
    val context = LocalContext.current
    // Hardware decoders first; if one fails (as on the emulator), retry with software ones.
    var softwareDecoders by remember { mutableStateOf(false) }
    val player = remember(local.path, softwareDecoders) { buildPlayer(context, local.path, softwareDecoders) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!softwareDecoders) softwareDecoders = true
            }
        }
        player.addListener(listener)
        onDispose { player.release() }
    }
    AndroidView(
        factory = { PlayerView(it) },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag("viewerVideo"),
    )
}

sealed interface ViewerContent {
    data class Photo(val fileId: Int, val minithumbnail: TdApi.Minithumbnail?) : ViewerContent
    data class Video(val fileId: Int) : ViewerContent
}

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    td: TdClient,
) : ViewModel() {

    var content by mutableStateOf<ViewerContent?>(null)
        private set

    init {
        val chatId: Long = checkNotNull(savedState["chatId"])
        val messageId: Long = checkNotNull(savedState["messageId"])
        viewModelScope.launch {
            content = when (val media = td.send(TdApi.GetMessage(chatId, messageId)).content) {
                is TdApi.MessagePhoto -> media.photo.sizes.maxByOrNull { it.width }
                    ?.let { ViewerContent.Photo(it.photo.id, media.photo.minithumbnail) }
                is TdApi.MessageVideo -> ViewerContent.Video(media.video.video.id)
                else -> null
            }
        }
    }
}
