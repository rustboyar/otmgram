@file:OptIn(ExperimentalFoundationApi::class)

package dev.otmgram.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.otmgram.R
import dev.otmgram.core.media.AudioState
import dev.otmgram.ui.format.formatDuration
import dev.otmgram.ui.format.formatSize
import dev.otmgram.ui.media.LoopingVideo
import dev.otmgram.ui.media.OnDemandFile
import dev.otmgram.ui.media.TdImage
import dev.otmgram.ui.media.rememberDownloadedPath
import dev.otmgram.ui.media.rememberFileState
import dev.otmgram.ui.media.rememberOnDemandFile
import dev.otmgram.ui.theme.ChatAvatar
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import kotlinx.coroutines.delay

/** Stickers and video messages are shown without a bubble around them. */
val MessageMedia.isBare: Boolean
    get() = this is MessageMedia.Sticker || this is MessageMedia.VideoNote

/** Pictures fill the bubble up to a thin margin. */
val MessageMedia.isFramed: Boolean
    get() = this is MessageMedia.Photo || this is MessageMedia.Video || this is MessageMedia.Animation || this is MessageMedia.Location

@Composable
fun MediaContent(
    media: MessageMedia,
    /** The voice message playing, read only by a voice message's own controls, which change 10 times a second. */
    audio: () -> AudioState?,
    outgoing: Boolean,
    onOpen: () -> Unit,
    /** The message menu: media handles taps, so it must pass long presses on. */
    onLongClick: () -> Unit,
    onPlayVoice: VoiceActions,
    onVideoNoteSound: () -> Unit,
    modifier: Modifier = Modifier,
    /** Set while the message is being sent: stops the upload. */
    onCancelUpload: (() -> Unit)? = null,
) {
    // Said by screen readers on the media itself, the part that takes taps: "Photo", "Location".
    val description = media.label()
    Box(modifier) {
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
                        .semantics { contentDescription = description }
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
            is MessageMedia.Sticker -> StickerView(media, Modifier.size(STICKER_SIZE).semantics { contentDescription = description }.testTag("sticker-${media.kind}"))
            is MessageMedia.Location -> LocationPreview(media, outgoing, onOpen, onLongClick)
            is MessageMedia.Document -> DocumentContent(media, onLongClick, onCancelUpload)
            is MessageMedia.Contact -> ContactCard(media, outgoing, onOpen, onLongClick)
        }
    }
}

/** A contact: their face, name and phone; a tap opens their profile if they're on Telegram. */
@Composable
private fun ContactCard(media: MessageMedia.Contact, outgoing: Boolean, onOpen: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .widthIn(min = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (outgoing) Otm.ApricotQuote else Otm.Paper)
            .combinedClickable(enabled = true, onClick = { if (media.userId != 0L) onOpen() }, onLongClick = onLongClick)
            .testTag("contact")
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChatAvatar(media.userId, media.name, media.photoFileId, media.minithumbnail, size = 44.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(media.name.ifEmpty { stringResource(R.string.summary_contact) }, style = OtmType.Name, color = Otm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (media.phone.isNotEmpty()) Text(media.phone, style = OtmType.Secondary, color = Otm.InkSoft)
        }
    }
}

/** A video downloads when played: tapping opens the viewer, which shows the progress. */
@Composable
private fun VideoPreview(media: MessageMedia.Video, onOpen: () -> Unit, onLongClick: () -> Unit, uploading: Boolean) {
    val file = rememberFileState(media.fileId)
    val description = media.label()
    Box(
        modifier = Modifier
            .width(MEDIA_WIDTH)
            .aspectRatio(aspect(media.width, media.height))
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .semantics { contentDescription = description }
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
    val description = media.label()
    val path = gif.path
    Box(
        modifier = Modifier
            .width(MEDIA_WIDTH)
            .aspectRatio(aspect(media.width, media.height))
            .clip(RoundedCornerShape(13.dp))
            .combinedClickable(onClick = gif.toggle, onLongClick = onLongClick)
            .semantics { contentDescription = description }
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
private fun VideoNote(media: MessageMedia.VideoNote, onSound: () -> Unit, onLongClick: () -> Unit) {
    val path = rememberDownloadedPath(media.fileId)
    val description = media.label()
    var withSound by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    // Grows while playing with sound, to be watched; back to its place in the chat after.
    val side by animateDpAsState(if (withSound) VIDEO_NOTE_PLAYING_SIZE else VIDEO_NOTE_SIZE, tween(VIDEO_NOTE_GROW_MS), label = "videoNoteSize")
    // Grown, all of it on screen.
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(withSound) {
        if (!withSound) return@LaunchedEffect
        delay(VIDEO_NOTE_GROW_MS.toLong())
        inView.bringIntoView()
    }
    Box(
        modifier = Modifier
            .bringIntoViewRequester(inView)
            .size(side)
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
            .semantics { contentDescription = description }
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
        // Next to the duration rather than in a corner, which the circle cuts off.
        Label(formatDuration(media.duration), Modifier.align(Alignment.BottomCenter)) {
            if (!withSound) {
                Icon(OtmIcons.Muted, stringResource(R.string.sound_off), tint = Color.White, modifier = Modifier.padding(start = 5.dp).size(13.dp))
            }
        }
    }
}

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .padding(8.dp)
            .background(Otm.Ink.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, style = OtmType.Time)
        trailing()
    }
}

/** Width / height, kept within a range that looks reasonable in a bubble. */
internal fun aspect(width: Int, height: Int): Float =
    if (width <= 0 || height <= 0) 1f else (width.toFloat() / height).coerceIn(0.5f, 2f)

internal val MEDIA_WIDTH = 260.dp
private val VIDEO_NOTE_SIZE = 190.dp
private val VIDEO_NOTE_PLAYING_SIZE = 280.dp
private const val VIDEO_NOTE_GROW_MS = 250
private val STICKER_SIZE = 140.dp
