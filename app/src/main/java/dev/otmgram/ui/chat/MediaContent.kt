@file:OptIn(ExperimentalFoundationApi::class)

package dev.otmgram.ui.chat

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import dev.otmgram.core.location.MapSnapshots
import dev.otmgram.core.media.AudioState
import dev.otmgram.data.FileRepository
import dev.otmgram.core.td.TdException
import dev.otmgram.ui.media.LocalFileRepository
import dev.otmgram.ui.media.LoopingVideo
import dev.otmgram.ui.media.Minithumbnail
import dev.otmgram.ui.media.TdImage
import dev.otmgram.ui.media.rememberDownloadedPath
import dev.otmgram.ui.media.formatSize
import dev.otmgram.ui.media.openFile
import dev.otmgram.ui.media.rememberFile
import dev.otmgram.ui.media.rememberFileState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

/** Stickers and video messages are shown without a bubble around them. */
val MessageMedia.isBare: Boolean
    get() = this is MessageMedia.Sticker || this is MessageMedia.VideoNote

@Composable
fun MediaContent(
    media: MessageMedia,
    audio: AudioState?,
    onOpen: () -> Unit,
    /** The message menu: media handles taps, so it must pass long presses on. */
    onLongClick: () -> Unit,
    onPlayVoice: (fileId: Int, path: String) -> Unit,
    onVideoNoteSound: () -> Unit,
) {
    when (media) {
        is MessageMedia.Photo -> TdImage(
            fileId = media.fileId,
            minithumbnail = media.minithumbnail,
            modifier = Modifier
                .width(MEDIA_WIDTH)
                .aspectRatio(aspect(media.width, media.height))
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
                .testTag("photo"),
        )
        is MessageMedia.Video -> VideoPreview(media, onOpen, onLongClick)
        is MessageMedia.Animation -> {
            val path = rememberDownloadedPath(media.fileId)
            val modifier = Modifier
                .width(MEDIA_WIDTH)
                .aspectRatio(aspect(media.width, media.height))
                .clip(RoundedCornerShape(12.dp))
            if (path != null) LoopingVideo(path, modifier) else Minithumbnail(media.minithumbnail, modifier)
        }
        is MessageMedia.VoiceNote -> VoiceNote(media, audio, onPlayVoice, onLongClick)
        is MessageMedia.VideoNote -> VideoNote(media, onVideoNoteSound, onLongClick)
        is MessageMedia.Sticker -> StickerView(media, Modifier.size(STICKER_SIZE).testTag("sticker-${media.kind}"))
        is MessageMedia.Location -> LocationPreview(media, onOpen, onLongClick)
        is MessageMedia.Document -> DocumentContent(media, onLongClick)
    }
}

/** A file: tap downloads it, tap again (once downloaded) opens it in another app. */
@Composable
private fun DocumentContent(media: MessageMedia.Document, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val files = LocalFileRepository.current
    val scope = rememberCoroutineScope()
    val local = rememberFileState(media.fileId)?.local
    val done = local?.isDownloadingCompleted == true
    val downloading = local?.isDownloadingActive == true
    Row(
        modifier = Modifier
            .widthIn(max = MEDIA_WIDTH)
            .combinedClickable(onLongClick = onLongClick) {
                when {
                    done -> openFile(context, local.path, media.mimeType)
                    !downloading -> scope.launch {
                        try {
                            files.startDownload(media.fileId)
                        } catch (e: TdException) {
                            Log.w("DocumentContent", "Download failed", e)
                        }
                    }
                }
            }
            .testTag("document"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    done -> "📄"
                    downloading -> "…"
                    else -> "⬇"
                },
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Column {
            Text(media.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (downloading && media.size > 0) {
                    "${local.downloadedSize * 100 / media.size}% of ${formatSize(media.size)}"
                } else {
                    formatSize(media.size)
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("documentStatus"),
            )
        }
    }
}

@Composable
private fun LocationPreview(media: MessageMedia.Location, onOpen: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val map by produceState<ImageBitmap?>(null, media.latitude, media.longitude) {
        value = MapSnapshots.get(
            context, media.latitude, media.longitude, MAP_WIDTH_DP, MAP_HEIGHT_DP, density,
        )?.asImageBitmap()
    }
    Column(Modifier.width(MAP_WIDTH_DP.dp).combinedClickable(onClick = onOpen, onLongClick = onLongClick).testTag("location")) {
        Box(
            modifier = Modifier
                .width(MAP_WIDTH_DP.dp)
                .height(MAP_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            map?.let { Image(it, contentDescription = null, modifier = Modifier.matchParentSize()) }
            Text("📍", style = MaterialTheme.typography.headlineMedium)
        }
        media.live?.let { live ->
            Text(
                liveStatus(live, rememberNow()),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp).testTag("liveStatus"),
            )
        }
    }
}

/** Current Unix time, refreshed every 30 s. */
@Composable
fun rememberNow(): Int {
    val now by produceState((System.currentTimeMillis() / 1000).toInt()) {
        while (true) {
            delay(30_000)
            value = (System.currentTimeMillis() / 1000).toInt()
        }
    }
    return now
}

fun liveStatus(live: MessageMedia.LiveInfo, now: Int): String {
    if (now >= live.expiresAt) return "Live location ended"
    val minutes = (now - live.updatedAt).coerceAtLeast(0) / 60
    val updated = if (minutes == 0) "updated just now" else "updated $minutes min ago"
    return "Live location · $updated"
}

@Composable
private fun VideoPreview(media: MessageMedia.Video, onOpen: () -> Unit, onLongClick: () -> Unit) {
    // Auto-download: the video itself downloads in the background, behind visible thumbnails.
    val file = rememberFile(media.fileId, priority = FileRepository.PRIORITY_BACKGROUND)
    Box(
        modifier = Modifier
            .width(MEDIA_WIDTH)
            .aspectRatio(aspect(media.width, media.height))
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .testTag("video"),
        contentAlignment = Alignment.Center,
    ) {
        TdImage(media.thumbnailFileId, media.minithumbnail, Modifier.matchParentSize())
        Text(
            "▶",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(12.dp),
        )
        val local = file?.local
        val progress = if (local == null || local.isDownloadingCompleted || file.size == 0L) {
            null
        } else {
            "${local.downloadedSize * 100 / file.size}%"
        }
        Label(listOfNotNull(formatDuration(media.duration), progress).joinToString(" · "), Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun VoiceNote(media: MessageMedia.VoiceNote, audio: AudioState?, onPlay: (Int, String) -> Unit, onLongClick: () -> Unit) {
    val path = rememberDownloadedPath(media.fileId)
    val current = audio?.takeIf { it.fileId == media.fileId }
    val progress = if (current != null && current.durationMs > 0) current.positionMs.toFloat() / current.durationMs else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .combinedClickable(enabled = path != null, onLongClick = onLongClick) { path?.let { onPlay(media.fileId, it) } }
                .testTag("playVoice"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    path == null -> "…"
                    current?.isPlaying == true -> "❚❚"
                    else -> "▶"
                },
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Waveform(media.waveform, progress, Modifier.width(140.dp).height(28.dp))
        Text(
            formatDuration(if (current != null) (current.positionMs / 1000).toInt() else media.duration),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun Waveform(levels: List<Int>, progress: Float, modifier: Modifier) {
    val played = MaterialTheme.colorScheme.primary
    val rest = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val bars = remember(levels) { resample(levels.ifEmpty { listOf(0) }, WAVEFORM_BARS) }
    Canvas(modifier) {
        val step = size.width / bars.size
        val barWidth = step * 0.6f
        bars.forEachIndexed { i, level ->
            val height = size.height * (0.15f + 0.85f * level / 31f)
            drawRoundRect(
                color = if ((i + 0.5f) / bars.size <= progress) played else rest,
                topLeft = Offset(i * step, (size.height - height) / 2),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

@Composable
private fun VideoNote(media: MessageMedia.VideoNote, onSound: () -> Unit, onLongClick: () -> Unit) {
    val path = rememberDownloadedPath(media.fileId)
    var withSound by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(VIDEO_NOTE_SIZE)
            .clip(CircleShape)
            .combinedClickable(enabled = path != null, onLongClick = onLongClick) {
                withSound = !withSound
                if (withSound) onSound()
            }
            .testTag("videoNote"),
    ) {
        if (path != null) {
            LoopingVideo(path, Modifier.matchParentSize(), muted = !withSound, onEnded = { withSound = false })
        } else {
            TdImage(media.thumbnailFileId, media.minithumbnail, Modifier.matchParentSize())
        }
        Label(formatDuration(media.duration) + if (withSound) "" else " 🔇", Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
fun StickerView(media: MessageMedia.Sticker, modifier: Modifier) {
    when (media.kind) {
        StickerKind.Image -> TdImage(media.fileId, null, modifier, contentScale = ContentScale.Fit)
        StickerKind.Lottie -> {
            val path = rememberDownloadedPath(media.fileId)
            if (path != null) LottieSticker(path, modifier) else Box(modifier)
        }
        // Android's decoders don't support transparent video: show the still thumbnail.
        StickerKind.Video -> TdImage(media.thumbnailFileId, null, modifier, contentScale = ContentScale.Fit)
    }
}

/** TGS stickers are gzipped Lottie animations. */
@Composable
private fun LottieSticker(path: String, modifier: Modifier) {
    val json by produceState<String?>(null, path) {
        value = withContext(Dispatchers.IO) {
            GZIPInputStream(File(path).inputStream()).bufferedReader().use { it.readText() }
        }
    }
    val loaded = json ?: return Box(modifier)
    // Only create the composition once loaded: an empty placeholder one breaks the animation.
    val composition by rememberLottieComposition(LottieCompositionSpec.JsonString(loaded))
    // Telegram stickers use merge paths, which Lottie disables by default.
    LottieAnimation(composition, iterations = LottieConstants.IterateForever, enableMergePaths = true, modifier = modifier)
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Width / height, kept within a range that looks reasonable in a bubble. */
private fun aspect(width: Int, height: Int): Float =
    if (width <= 0 || height <= 0) 1f else (width.toFloat() / height).coerceIn(0.5f, 2f)

private fun resample(levels: List<Int>, count: Int): List<Int> =
    List(count) { i -> levels[i * levels.size / count] }

fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

private val MEDIA_WIDTH = 260.dp
private const val MAP_WIDTH_DP = 260
private const val MAP_HEIGHT_DP = 150
private val VIDEO_NOTE_SIZE = 200.dp
private val STICKER_SIZE = 160.dp
private const val WAVEFORM_BARS = 40
