package dev.otmgram.ui.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import dev.otmgram.core.launchLogged
import dev.otmgram.core.media.SoftwareFirstCodecSelector
import dev.otmgram.core.media.mediaAudio
import dev.otmgram.data.FileRepository
import dev.otmgram.data.FileState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/** Player for a local file, playing once prepared. */
@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, path: String, softwareDecoders: Boolean): ExoPlayer {
    val renderers = DefaultRenderersFactory(context)
    if (softwareDecoders) renderers.setMediaCodecSelector(SoftwareFirstCodecSelector)
    return ExoPlayer.Builder(context, renderers).build().apply {
        setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        prepare()
        playWhenReady = true
    }
}

val LocalFileRepository = staticCompositionLocalOf<FileRepository> { error("No FileRepository provided") }

/** State of a TDLib file, downloading it while in composition; null until known. */
@Composable
fun rememberFile(fileId: Int?, priority: Int = FileRepository.PRIORITY_VISIBLE): FileState? {
    val files = LocalFileRepository.current
    val flow = remember(fileId, priority) { fileId?.let { files.download(it, priority) } ?: emptyFlow() }
    val file by flow.collectAsState(initial = fileId?.let(files::lastKnown))
    return file
}

/** State of a TDLib file without downloading it; null until known. */
@Composable
fun rememberFileState(fileId: Int): FileState? {
    val files = LocalFileRepository.current
    val flow = remember(fileId) { files.observe(fileId) }
    val file by flow.collectAsState(initial = files.lastKnown(fileId))
    return file
}

/** A file downloaded only when the user asks: its state, and a toggle to start or cancel it. */
class OnDemandFile(val file: FileState?, val toggle: () -> Unit) {
    /** Local path once fully downloaded. */
    val path: String? get() = file?.path
    val downloading: Boolean get() = file?.isDownloading == true

    /** Downloaded fraction, 0..1. */
    val progress: Float get() = file?.downloadProgress ?: 0f

    /** Size in bytes, 0 if unknown. */
    val size: Long get() = file?.size ?: 0L
}

/** State of a file that downloads on tap (videos, voice, GIFs); see [OnDemandFile]. */
@Composable
fun rememberOnDemandFile(fileId: Int): OnDemandFile {
    val files = LocalFileRepository.current
    val scope = rememberCoroutineScope()
    val file = rememberFileState(fileId)
    return OnDemandFile(file) {
        if (file?.isDownloaded == true) return@OnDemandFile
        scope.launchLogged("OnDemandFile") {
            if (file?.isDownloading == true) files.cancelDownload(fileId) else files.startDownload(fileId)
        }
    }
}

/** Local path of a TDLib file once fully downloaded. */
@Composable
fun rememberDownloadedPath(fileId: Int?, priority: Int = FileRepository.PRIORITY_VISIBLE): String? =
    rememberFile(fileId, priority)?.path

/** Image from a TDLib file; shows the blurred [minithumbnail] (JPEG bytes) until it's downloaded. */
@Composable
fun TdImage(
    fileId: Int?,
    minithumbnail: ByteArray?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val path = rememberDownloadedPath(fileId)
    Box(modifier) {
        if (path == null) {
            Minithumbnail(minithumbnail, Modifier.matchParentSize())
        } else {
            AsyncImage(model = File(path), contentDescription = null, contentScale = contentScale, modifier = Modifier.matchParentSize())
        }
    }
}

@Composable
fun Minithumbnail(minithumbnail: ByteArray?, modifier: Modifier = Modifier) {
    val bitmap = remember(minithumbnail) {
        minithumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    } ?: return
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.blur(8.dp))
}

/**
 * Muted, looping video from a local file, e.g. video messages and GIFs.
 *
 * Hardware decoders first, which take far less battery; if one fails (devices have few
 * instances, and the emulator's fails on these videos), software ones take over. Paused
 * while the app is in the background.
 */
@OptIn(UnstableApi::class)
@Composable
fun LoopingVideo(
    path: String,
    modifier: Modifier = Modifier,
    muted: Boolean = true,
    onEnded: () -> Unit = {},
    /** Called about 20 times a second with the played fraction, 0..1. */
    onProgress: ((Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    var softwareDecoders by remember(path) { mutableStateOf(false) }
    val player = remember(path, softwareDecoders) { buildPlayer(context, path, softwareDecoders) }
    // Muted: loop silently. With sound: play once from the start, then report the end.
    DisposableEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
        // A silent loop leaves other apps' sound alone.
        player.mediaAudio(focus = !muted)
        player.repeatMode = if (muted) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        if (!muted) player.seekTo(0)
        player.play()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!softwareDecoders) softwareDecoders = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> player.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (onProgress != null) {
        LaunchedEffect(player) {
            while (true) {
                val duration = player.duration
                if (duration > 0) onProgress((player.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }
    // A texture view, unlike a surface view, can be clipped, e.g. to a circle.
    PlayerSurface(player, modifier, surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
}

private const val PROGRESS_INTERVAL_MS = 50L
