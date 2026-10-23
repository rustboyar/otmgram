package dev.otmgram.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.core.launchLogged
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.core.userMessage
import dev.otmgram.data.DownloadsFolder
import dev.otmgram.data.FileRepository
import dev.otmgram.ui.chat.formatTime
import dev.otmgram.ui.theme.DarkSystemBars
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** Full-screen photo (zoomable) or video of a message: the only dark screen, so pictures read true. */
@Composable
fun MediaViewerScreen(onBack: () -> Unit, viewModel: MediaViewerViewModel = hiltViewModel()) {
    val snackbar = remember { SnackbarHostState() }
    viewModel.notice?.let { notice ->
        LaunchedEffect(notice) {
            snackbar.showSnackbar(notice)
            viewModel.noticeShown()
        }
    }
    DarkSystemBars()
    val pages = viewModel.pages
    val pager = rememberPagerState(initialPage = viewModel.startIndex) { pages.size }
    // The album loads after the pager is created: start on the opened message.
    LaunchedEffect(viewModel.startIndex) { pager.scrollToPage(viewModel.startIndex) }
    val current = pages.getOrNull(pager.currentPage)
    Box(Modifier.fillMaxSize().background(Otm.Night)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { pages[it].messageId }) { index ->
            when (val content = pages[index].content) {
                is ViewerContent.Photo -> ZoomablePhoto(content)
                // Only the page on screen plays.
                is ViewerContent.Video -> if (index == pager.currentPage) FullVideo(content) else VideoStill(content)
            }
        }
        val info = viewModel.info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Otm.Night.copy(alpha = 0.9f))
                .statusBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(OtmIcons.Back, "Close", onBack, tint = Otm.NightText, tag = "closeViewer")
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                info?.let {
                    Text(it.sender, style = OtmType.Name, color = Otm.NightText, maxLines = 1)
                    Text(
                        if (pages.size > 1) "${it.time} · ${pager.currentPage + 1} of ${pages.size}" else it.time,
                        style = OtmType.Secondary,
                        color = Otm.NightSoft,
                        modifier = Modifier.testTag("viewerPosition"),
                    )
                }
            }
            if (current != null) {
                IconBtn(OtmIcons.Save, "Save to Downloads", { viewModel.saveToDownloads(current) }, tint = Otm.NightText, tag = "saveMedia")
            }
        }
        current?.caption?.takeIf { it.isNotEmpty() && current.content is ViewerContent.Photo }?.let { caption ->
            Text(
                caption,
                style = OtmType.Body,
                color = Otm.NightText,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Otm.Night.copy(alpha = 0.9f))
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp)) {
            Snackbar(it, containerColor = Otm.NightText, contentColor = Otm.Night, shape = RoundedCornerShape(14.dp))
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
            .transformable(state, canPan = { scale > 1f })
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            .testTag("viewerPhoto"),
    )
}

@Composable
private fun FullVideo(video: ViewerContent.Video) {
    val file = rememberFile(video.fileId, FileRepository.PRIORITY_VIEWER)
    val path = file?.path
    if (path == null) {
        val percent = ((file?.downloadProgress ?: 0f) * 100).toInt()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Downloading $percent%", style = OtmType.Body, color = Otm.NightSoft)
        }
        return
    }
    val context = LocalContext.current
    // Hardware decoders first; if one fails (as on the emulator), retry with software ones.
    var softwareDecoders by remember { mutableStateOf(false) }
    val player = remember(path, softwareDecoders) { buildPlayer(context, path, softwareDecoders) }
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
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp).testTag("viewerVideo"),
    )
}

@Composable
private fun VideoStill(video: ViewerContent.Video) {
    TdImage(video.thumbnailFileId, video.minithumbnail, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

sealed interface ViewerContent {
    data class Photo(val fileId: Int, val minithumbnail: ByteArray?) : ViewerContent
    data class Video(val fileId: Int, val thumbnailFileId: Int?, val minithumbnail: ByteArray?) : ViewerContent
}

/** One photo or video of the album; [saveAs] is the file name and type for Downloads. */
data class ViewerPage(val messageId: Long, val content: ViewerContent, val caption: String, val saveAs: Pair<String, String>)

/** Who sent it and when: "Rustem", "Today, 19:01". */
data class ViewerInfo(val sender: String, val time: String)

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val td: TdClient,
    private val files: FileRepository,
    private val downloads: DownloadsFolder,
) : ViewModel() {

    /** The opened photo or video, and the rest of its album around it. */
    var pages by mutableStateOf<List<ViewerPage>>(emptyList())
        private set

    var startIndex by mutableIntStateOf(0)
        private set

    var info by mutableStateOf<ViewerInfo?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    init {
        val chatId: Long = checkNotNull(savedState["chatId"])
        val messageId: Long = checkNotNull(savedState["messageId"])
        viewModelScope.launchLogged(TAG, onError = { notice = "This message is no longer available" }) {
            val message = td.send(TdApi.GetMessage(chatId, messageId))
            info = ViewerInfo(sender(message), postedAt(message.date))
            val album = if (message.mediaAlbumId == 0L) listOf(message) else albumOf(message)
            pages = album.mapNotNull(::page)
            startIndex = pages.indexOfFirst { it.messageId == messageId }.coerceAtLeast(0)
        }
    }

    /** Messages of the album, in order; albums have at most 10, all next to each other. */
    private suspend fun albumOf(message: TdApi.Message): List<TdApi.Message> {
        val around = td.send(TdApi.GetChatHistory(message.chatId, message.id, -ALBUM_MAX, 2 * ALBUM_MAX, false)).messages
        return (around.filter { it.mediaAlbumId == message.mediaAlbumId } + message).distinctBy { it.id }.sortedBy { it.id }
    }

    private fun page(message: TdApi.Message): ViewerPage? = when (val media = message.content) {
        is TdApi.MessagePhoto -> media.photo.sizes.maxByOrNull { it.width }?.let {
            ViewerPage(message.id, ViewerContent.Photo(it.photo.id, media.photo.minithumbnail?.data), media.caption.text, "photo-${message.id}.jpg" to "image/jpeg")
        }
        is TdApi.MessageVideo -> ViewerPage(
            message.id,
            ViewerContent.Video(media.video.video.id, media.video.thumbnail?.file?.id, media.video.minithumbnail?.data),
            media.caption.text,
            media.video.fileName.ifEmpty { "video-${message.id}.mp4" } to media.video.mimeType.ifEmpty { "video/mp4" },
        )
        else -> null
    }

    fun saveToDownloads(page: ViewerPage) {
        val fileId = when (val c = page.content) {
            is ViewerContent.Photo -> c.fileId
            is ViewerContent.Video -> c.fileId
        }
        val (name, mimeType) = page.saveAs
        viewModelScope.launchLogged(TAG, onError = { notice = "Couldn't save: ${it.userMessage()}" }) {
            downloads.save(File(files.downloadNow(fileId)), name, mimeType)
            notice = "Saved to Downloads"
        }
    }

    fun noticeShown() {
        notice = null
    }

    private suspend fun sender(message: TdApi.Message): String = when (val sender = message.senderId) {
        is TdApi.MessageSenderUser -> td.send(TdApi.GetUser(sender.userId)).let { listOf(it.firstName, it.lastName).filter(String::isNotEmpty).joinToString(" ") }
        is TdApi.MessageSenderChat -> td.send(TdApi.GetChat(sender.chatId)).title
        else -> ""
    }
}

private const val ALBUM_MAX = 10
private const val TAG = "MediaViewer"

/** "Today, 19:01", or the date. */
private fun postedAt(unixTime: Int): String {
    val date = Instant.ofEpochSecond(unixTime.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = when (date) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    return "$day, ${formatTime(unixTime)}"
}
