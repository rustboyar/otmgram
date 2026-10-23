@file:OptIn(ExperimentalFoundationApi::class)

package dev.otmgram.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.location.MapSnapshots
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.media.AudioState
import dev.otmgram.core.td.TdException
import dev.otmgram.data.FileState
import dev.otmgram.data.StickerKind
import dev.otmgram.ui.media.LocalFileRepository
import dev.otmgram.ui.media.LoopingVideo
import dev.otmgram.ui.media.OnDemandFile
import dev.otmgram.ui.media.TdImage
import dev.otmgram.ui.media.formatSize
import dev.otmgram.ui.media.openFile
import dev.otmgram.ui.media.rememberDownloadedPath
import dev.otmgram.ui.media.rememberFileState
import dev.otmgram.ui.media.rememberOnDemandFile
import dev.otmgram.ui.rememberStrings
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

/** Stickers and video messages are shown without a bubble around them. */
val MessageMedia.isBare: Boolean
    get() = this is MessageMedia.Sticker || this is MessageMedia.VideoNote

/** Pictures fill the bubble up to a thin margin. */
val MessageMedia.isFramed: Boolean
    get() = this is MessageMedia.Photo || this is MessageMedia.Video || this is MessageMedia.Animation || this is MessageMedia.Location

@Composable
fun MediaContent(
    media: MessageMedia,
    audio: AudioState?,
    outgoing: Boolean,
    onOpen: () -> Unit,
    /** The message menu: media handles taps, so it must pass long presses on. */
    onLongClick: () -> Unit,
    onPlayVoice: (fileId: Int, path: String) -> Unit,
    onVideoNoteSound: () -> Unit,
    /** Set while the message is being sent: stops the upload. */
    onCancelUpload: (() -> Unit)? = null,
) {
    when (media) {
        is MessageMedia.Photo -> Box(contentAlignment = Alignment.Center) {
            TdImage(
                fileId = media.fileId,
                minithumbnail = media.minithumbnail,
                modifier = Modifier
                    .width(MEDIA_WIDTH)
                    .aspectRatio(aspect(media.width, media.height))
                    .clip(RoundedCornerShape(13.dp))
                    .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
                    .testTag("photo"),
            )
            onCancelUpload?.let { UploadRing(media.fileId, it) }
        }
        is MessageMedia.Video -> Box(contentAlignment = Alignment.Center) {
            VideoPreview(media, onOpen, onLongClick, uploading = onCancelUpload != null)
            onCancelUpload?.let { UploadRing(media.fileId, it) }
        }
        is MessageMedia.Animation -> Gif(media, onLongClick)
        is MessageMedia.VoiceNote -> VoiceNote(media, audio, outgoing, onPlayVoice, onLongClick)
        is MessageMedia.VideoNote -> VideoNote(media, onVideoNoteSound, onLongClick)
        is MessageMedia.Sticker -> StickerView(media, Modifier.size(STICKER_SIZE).testTag("sticker-${media.kind}"))
        is MessageMedia.Location -> LocationPreview(media, outgoing, onOpen, onLongClick)
        is MessageMedia.Document -> DocumentContent(media, onLongClick, onCancelUpload)
    }
}

/** A file: tap downloads it, tap again (once downloaded) opens it in another app. */
@Composable
private fun DocumentContent(media: MessageMedia.Document, onLongClick: () -> Unit, onCancelUpload: (() -> Unit)?) {
    val context = LocalContext.current
    val files = LocalFileRepository.current
    val scope = rememberCoroutineScope()
    val file = rememberFileState(media.fileId)
    val path = file?.path
    val downloading = file?.isDownloading == true
    if (onCancelUpload != null && file != null) {
        UploadingDocument(media, file, onCancelUpload, onLongClick)
        return
    }
    Row(
        modifier = Modifier
            .widthIn(max = MEDIA_WIDTH)
            .combinedClickable(onLongClick = onLongClick) {
                when {
                    path != null -> openFile(context, path, media.mimeType)
                    downloading -> scope.launch { files.cancelDownload(media.fileId) }
                    else -> scope.launch {
                        try {
                            files.startDownload(media.fileId)
                        } catch (e: TdException) {
                            AppLog.w("DocumentContent", "Download failed", e)
                        }
                    }
                }
            }
            .padding(vertical = 2.dp)
            .testTag("document"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(if (downloading) CircleShape else RoundedCornerShape(12.dp)).background(Otm.Haze),
            contentAlignment = Alignment.Center,
        ) {
            if (downloading) {
                val progress = if (media.size > 0) file.downloaded.toFloat() / media.size else 0f
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.matchParentSize().padding(2.dp),
                    color = Otm.Slate,
                    trackColor = Otm.Slate.copy(alpha = 0.18f),
                    strokeWidth = 3.dp,
                )
                Icon(OtmIcons.Close, stringResource(R.string.download_cancel), tint = Otm.SlateDeep, modifier = Modifier.size(16.dp))
            } else {
                Icon(if (path != null) OtmIcons.File else OtmIcons.Download, null, tint = Otm.SlateDeep, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(media.name, style = OtmType.Body.copy(fontWeight = FontWeight.Medium), color = Otm.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    downloading -> stringResource(R.string.size_of, formatSize(file.downloaded), formatSize(media.size))
                    path != null -> stringResource(R.string.file_tap_to_open, formatSize(media.size))
                    else -> stringResource(R.string.file_tap_to_download, formatSize(media.size))
                },
                style = OtmType.Secondary,
                color = Otm.InkSoft,
                modifier = Modifier.testTag("documentStatus"),
            )
        }
    }
}

/** A file of yours being sent: upload progress, tap to stop. */
@Composable
private fun UploadingDocument(media: MessageMedia.Document, file: FileState, onCancel: () -> Unit, onLongClick: () -> Unit) {
    val total = file.size.takeIf { it > 0 } ?: media.size
    val sent = file.uploaded
    Row(
        modifier = Modifier.widthIn(max = MEDIA_WIDTH).combinedClickable(onLongClick = onLongClick, onClick = onCancel).padding(vertical = 2.dp).testTag("cancelUpload"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Otm.Haze), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { if (total > 0) sent.toFloat() / total else 0f },
                modifier = Modifier.matchParentSize().padding(2.dp),
                color = Otm.Slate,
                trackColor = Otm.Slate.copy(alpha = 0.18f),
                strokeWidth = 3.dp,
            )
            Icon(OtmIcons.Close, stringResource(R.string.upload_stop), tint = Otm.SlateDeep, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(media.name, style = OtmType.Body.copy(fontWeight = FontWeight.Medium), color = Otm.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.size_of, formatSize(sent), formatSize(total)), style = OtmType.Secondary, color = Otm.InkSoft)
        }
    }
}

/** Over a photo or video being sent: how much is uploaded, and a cross to stop it. */
@Composable
private fun UploadRing(fileId: Int, onCancel: () -> Unit) {
    val progress = rememberFileState(fileId)?.uploadProgress ?: 0f
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Otm.Ink.copy(alpha = 0.5f))
            .clickable(onClick = onCancel, onClickLabel = stringResource(R.string.upload_stop))
            .testTag("cancelUpload"),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.matchParentSize().padding(3.dp),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            strokeWidth = 3.dp,
        )
        Icon(OtmIcons.Close, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun LocationPreview(media: MessageMedia.Location, outgoing: Boolean, onOpen: () -> Unit, onLongClick: () -> Unit) {
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
                .clip(RoundedCornerShape(13.dp))
                .background(Color(0xFFEFEDE6)),
            contentAlignment = Alignment.Center,
        ) {
            map?.let { Image(it, contentDescription = null, modifier = Modifier.matchParentSize()) }
            MapPin()
        }
        val live = media.live
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 7.dp, end = 7.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (live != null) {
                Text(
                    liveStatus(live, rememberNow(), rememberStrings()),
                    style = OtmType.Time.copy(fontWeight = FontWeight.SemiBold),
                    color = Otm.SlateDeep,
                    modifier = Modifier.testTag("liveStatus"),
                )
            } else {
                Text(stringResource(R.string.summary_location), style = OtmType.Time, color = Color(0xFF56626B))
            }
        }
    }
}

/** A slate dot with a soft halo, where the location is. */
@Composable
fun MapPin(modifier: Modifier = Modifier) {
    Canvas(modifier.size(44.dp)) {
        drawCircle(Otm.Slate.copy(alpha = 0.18f))
        drawCircle(Color.White, radius = 12.dp.toPx())
        drawCircle(Otm.Slate, radius = 9.dp.toPx())
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

fun liveStatus(live: MessageMedia.LiveInfo, now: Int, strings: Strings): String {
    if (now >= live.expiresAt) return strings.get(R.string.live_ended)
    val minutes = (now - live.updatedAt).coerceAtLeast(0) / 60
    return if (minutes == 0) strings.get(R.string.live_updated_now) else strings.get(R.string.live_updated_ago, minutes)
}

/** A video downloads when played: tapping opens the viewer, which shows the progress. */
@Composable
private fun VideoPreview(media: MessageMedia.Video, onOpen: () -> Unit, onLongClick: () -> Unit, uploading: Boolean) {
    val file = rememberFileState(media.fileId)
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
        if (!uploading) {
            Box(Modifier.size(48.dp).background(Otm.Sheet.copy(alpha = 0.88f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(OtmIcons.Play, stringResource(R.string.play_video), tint = Otm.Ink, modifier = Modifier.size(18.dp))
            }
        }
        val status = when {
            file == null || uploading || file.isDownloaded -> null
            file.isDownloading && file.size > 0 -> "${(file.downloadProgress * 100).toInt()}%"
            file.size > 0 -> formatSize(file.size)
            else -> null
        }
        Label(listOfNotNull(formatDuration(media.duration), status).joinToString(" · "), Modifier.align(Alignment.TopStart))
    }
}

/** A GIF downloads on tap, then loops in place; tap again while downloading to stop. */
@Composable
private fun Gif(media: MessageMedia.Animation, onLongClick: () -> Unit) {
    val gif = rememberOnDemandFile(media.fileId)
    val path = gif.path
    Box(
        modifier = Modifier
            .width(MEDIA_WIDTH)
            .aspectRatio(aspect(media.width, media.height))
            .clip(RoundedCornerShape(13.dp))
            .combinedClickable(onClick = gif.toggle, onLongClick = onLongClick)
            .testTag("gif"),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            LoopingVideo(path, Modifier.matchParentSize())
            return@Box
        }
        TdImage(media.thumbnailFileId, media.minithumbnail, Modifier.matchParentSize())
        DownloadButton(gif, stringResource(R.string.play_gif))
        Label(listOfNotNull(stringResource(R.string.summary_gif), gif.size.takeIf { it > 0 }?.let(::formatSize)).joinToString(" · "), Modifier.align(Alignment.TopStart))
    }
}

/** Play while not downloaded yet; a progress ring with a cross while downloading. */
@Composable
private fun DownloadButton(file: OnDemandFile, label: String) {
    Box(Modifier.size(48.dp).background(Otm.Sheet.copy(alpha = 0.88f), CircleShape), contentAlignment = Alignment.Center) {
        if (file.downloading) {
            CircularProgressIndicator(
                progress = { file.progress },
                modifier = Modifier.matchParentSize().padding(3.dp),
                color = Otm.Slate,
                trackColor = Otm.Slate.copy(alpha = 0.18f),
                strokeWidth = 3.dp,
            )
            Icon(OtmIcons.Close, stringResource(R.string.download_stop), tint = Otm.Ink, modifier = Modifier.size(16.dp))
        } else {
            Icon(OtmIcons.Play, label, tint = Otm.Ink, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun VoiceNote(
    media: MessageMedia.VoiceNote,
    audio: AudioState?,
    outgoing: Boolean,
    onPlay: (Int, String) -> Unit,
    onLongClick: () -> Unit,
) {
    // Downloads on the first tap, then plays as soon as it's in.
    val voice = rememberOnDemandFile(media.fileId)
    val path = voice.path
    var playWhenReady by remember { mutableStateOf(false) }
    LaunchedEffect(path, playWhenReady) {
        if (playWhenReady && path != null) {
            playWhenReady = false
            onPlay(media.fileId, path)
        }
    }
    val current = audio?.takeIf { it.fileId == media.fileId }
    val progress = if (current != null && current.durationMs > 0) current.positionMs.toFloat() / current.durationMs else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (outgoing) Color(0xFFFBF1E7) else Otm.Haze)
                .combinedClickable(onLongClick = onLongClick) {
                    when {
                        path != null -> onPlay(media.fileId, path)
                        else -> {
                            playWhenReady = !voice.downloading
                            voice.toggle()
                        }
                    }
                }
                .testTag("playVoice"),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (outgoing) Color(0xFF8A5A3C) else Otm.SlateDeep
            when {
                voice.downloading -> {
                    CircularProgressIndicator(
                        progress = { voice.progress },
                        modifier = Modifier.matchParentSize().padding(2.dp),
                        color = tint,
                        trackColor = tint.copy(alpha = 0.18f),
                        strokeWidth = 2.5.dp,
                    )
                    Icon(OtmIcons.Close, stringResource(R.string.download_stop), tint = tint, modifier = Modifier.size(14.dp))
                }
                current?.isPlaying == true -> Icon(OtmIcons.Pause, stringResource(R.string.pause), tint = tint, modifier = Modifier.size(16.dp))
                else -> Icon(OtmIcons.Play, stringResource(R.string.play_voice), tint = tint, modifier = Modifier.size(16.dp))
            }
        }
        Column {
            Waveform(
                media.waveform,
                progress,
                played = if (outgoing) Color(0xFF8A5A3C) else Otm.Slate,
                rest = if (outgoing) Otm.ApricotDeep else Otm.RidgeLight,
                modifier = Modifier.width(140.dp).height(24.dp),
            )
            Text(
                formatDuration(if (current != null) (current.positionMs / 1000).toInt() else media.duration),
                style = OtmType.Time,
                color = Color(0xFF56626B),
            )
        }
    }
}

@Composable
fun Waveform(levels: List<Int>, progress: Float, played: Color, rest: Color, modifier: Modifier, bars: Int = WAVEFORM_BARS) {
    val samples = remember(levels, bars) { resample(levels.ifEmpty { listOf(0) }, bars) }
    Canvas(modifier) {
        val step = size.width / samples.size
        val barWidth = step * 0.6f
        samples.forEachIndexed { i, level ->
            val height = size.height * (0.15f + 0.85f * level / 31f)
            drawRoundRect(
                color = if ((i + 0.5f) / samples.size <= progress) played else rest,
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
    var progress by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .size(VIDEO_NOTE_SIZE)
            .drawWithContent {
                drawContent()
                // While playing with sound, a ring shows how much has played.
                if (withSound) {
                    val stroke = 3.dp.toPx()
                    drawCircle(Otm.Haze, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                    drawArc(
                        Otm.Slate,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            // Room for the ring around the video.
            .padding(6.dp)
            .clip(CircleShape)
            .combinedClickable(enabled = path != null, onLongClick = onLongClick) {
                withSound = !withSound
                if (withSound) onSound()
            }
            .testTag("videoNote"),
    ) {
        if (path != null) {
            LoopingVideo(
                path,
                Modifier.matchParentSize(),
                muted = !withSound,
                onEnded = { withSound = false },
                onProgress = { progress = it },
            )
        } else {
            TdImage(media.thumbnailFileId, media.minithumbnail, Modifier.matchParentSize())
        }
        Label(formatDuration(media.duration), Modifier.align(Alignment.BottomCenter))
        if (!withSound) {
            Icon(
                OtmIcons.Muted, stringResource(R.string.sound_off),
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).size(26.dp).background(Otm.Ink.copy(alpha = 0.45f), CircleShape).padding(5.dp),
            )
        }
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
        style = OtmType.Time,
        modifier = modifier
            .padding(8.dp)
            .background(Otm.Ink.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
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
private const val MAP_HEIGHT_DP = 140
private val VIDEO_NOTE_SIZE = 190.dp
private val STICKER_SIZE = 140.dp
private const val WAVEFORM_BARS = 32
