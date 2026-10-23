package dev.otmgram.ui.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import dev.otmgram.data.FileRepository
import kotlinx.coroutines.flow.emptyFlow
import org.drinkless.tdlib.TdApi
import java.io.File

/** Player for a local file, playing once prepared. */
@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, path: String, softwareDecoders: Boolean): ExoPlayer {
    val renderers = DefaultRenderersFactory(context)
    if (softwareDecoders) {
        renderers.setMediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling).sortedBy { it.hardwareAccelerated }
        }
    }
    return ExoPlayer.Builder(context, renderers).build().apply {
        setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        prepare()
        playWhenReady = true
    }
}

val LocalFileRepository = staticCompositionLocalOf<FileRepository> { error("No FileRepository provided") }

/** State of a TDLib file, downloading it while in composition; null until known. */
@Composable
fun rememberFile(fileId: Int?, priority: Int = FileRepository.PRIORITY_VISIBLE): TdApi.File? {
    val files = LocalFileRepository.current
    val flow = remember(fileId, priority) { fileId?.let { files.download(it, priority) } ?: emptyFlow() }
    val file by flow.collectAsState(initial = null)
    return file
}

/** Local path of a TDLib file once fully downloaded. */
@Composable
fun rememberDownloadedPath(fileId: Int?, priority: Int = FileRepository.PRIORITY_VISIBLE): String? =
    rememberFile(fileId, priority)?.local?.takeIf { it.isDownloadingCompleted }?.path

/** Image from a TDLib file; shows the blurred [minithumbnail] until it's downloaded. */
@Composable
fun TdImage(
    fileId: Int?,
    minithumbnail: TdApi.Minithumbnail?,
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
fun Minithumbnail(minithumbnail: TdApi.Minithumbnail?, modifier: Modifier = Modifier) {
    val bitmap = remember(minithumbnail) {
        minithumbnail?.data?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    } ?: return
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.blur(8.dp))
}

/**
 * Muted, looping video from a local file, e.g. video messages and GIFs.
 *
 * Uses software decoders first: these videos are small, several can play at once, and
 * devices have few hardware decoder instances (the emulator's fails on them altogether).
 */
@OptIn(UnstableApi::class)
@Composable
fun LoopingVideo(path: String, modifier: Modifier = Modifier, muted: Boolean = true, onEnded: () -> Unit = {}) {
    val context = LocalContext.current
    val player = remember(path) { buildPlayer(context, path, softwareDecoders = true) }
    // Muted: loop silently. With sound: play once from the start, then report the end.
    DisposableEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
        player.repeatMode = if (muted) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        if (!muted) player.seekTo(0)
        player.play()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // A texture view, unlike a surface view, can be clipped, e.g. to a circle.
    PlayerSurface(player, modifier, surfaceType = SURFACE_TYPE_TEXTURE_VIEW)
}
