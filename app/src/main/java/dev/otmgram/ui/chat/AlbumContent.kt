@file:OptIn(ExperimentalFoundationApi::class)

package dev.otmgram.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.otmgram.R
import dev.otmgram.ui.format.formatDuration
import dev.otmgram.ui.media.TdImage
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons

/**
 * An album's photos and videos in rows, each row as tall as its pictures' shapes allow;
 * pictures that don't fit are cropped. A tap opens one in the viewer, which pages through
 * the album.
 */
@Composable
fun AlbumGrid(
    items: List<MessageItem>,
    onOpen: (messageId: Long) -> Unit,
    onLongClick: () -> Unit,
    /** Set unless selecting: stops sending one that's still uploading. */
    onCancelUpload: ((messageId: Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val aspects = items.map { it.media.aspect() }
    Column(modifier.width(MEDIA_WIDTH).clip(RoundedCornerShape(13.dp)), verticalArrangement = Arrangement.spacedBy(ALBUM_GAP)) {
        albumRows(aspects).forEach { row ->
            val height = ((MEDIA_WIDTH - ALBUM_GAP * (row.count() - 1)) / row.sumOf { aspects[it].toDouble() }.toFloat())
                .coerceIn(ALBUM_MIN_ROW, ALBUM_MAX_ROW)
            Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(ALBUM_GAP)) {
                row.forEach { i ->
                    val item = items[i]
                    AlbumCell(
                        item,
                        onOpen = { onOpen(item.id) },
                        onLongClick = onLongClick,
                        onCancelUpload = onCancelUpload?.takeIf { item.status == MessageStatus.Sending }?.let { cancel -> { cancel(item.id) } },
                        modifier = Modifier.weight(aspects[i]).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumCell(item: MessageItem, onOpen: () -> Unit, onLongClick: () -> Unit, onCancelUpload: (() -> Unit)?, modifier: Modifier = Modifier) {
    val media = item.media
    val description = media?.label().orEmpty()
    Box(
        modifier = modifier
            .background(Otm.Haze)
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick)
            .semantics { contentDescription = description }
            .testTag("albumItem-${item.id}"),
        contentAlignment = Alignment.Center,
    ) {
        when (media) {
            is MessageMedia.Photo -> {
                TdImage(media.fileId, media.minithumbnail, Modifier.matchParentSize())
                onCancelUpload?.let { UploadRing(media.fileId, it) }
            }
            is MessageMedia.Video -> {
                TdImage(media.thumbnailFileId, media.minithumbnail, Modifier.matchParentSize())
                if (onCancelUpload == null) {
                    Box(Modifier.size(36.dp).background(Otm.Sheet.copy(alpha = 0.88f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(OtmIcons.Play, stringResource(R.string.play_video), tint = Otm.Ink, modifier = Modifier.size(14.dp))
                    }
                } else {
                    UploadRing(media.fileId, onCancelUpload)
                }
                Label(formatDuration(media.duration), Modifier.align(Alignment.TopStart))
            }
            else -> Unit
        }
    }
}

/**
 * Which of an album's pictures (by their width / height) go in each row, top to bottom: two
 * side by side (or one above the other if both are wide), then one large above the rest,
 * then rows of two and three, the fuller ones last.
 */
internal fun albumRows(aspects: List<Float>): List<IntRange> {
    val n = aspects.size
    fun wide(i: Int) = aspects[i] >= WIDE
    val sizes = when {
        n <= 1 -> listOf(n)
        n == 2 -> if (wide(0) && wide(1)) listOf(1, 1) else listOf(2)
        n == 3 -> listOf(1, 2)
        n == 4 -> if (wide(0)) listOf(1, 3) else listOf(2, 2)
        else -> {
            val rows = (n + 2) / 3
            List(rows) { row -> n / rows + if (row >= rows - n % rows) 1 else 0 }
        }
    }
    return sizes.runningFold(0) { start, size -> start + size }.zipWithNext { start, end -> start until end }
}

private fun MessageMedia?.aspect(): Float = when (this) {
    is MessageMedia.Photo -> aspect(width, height)
    is MessageMedia.Video -> aspect(width, height)
    else -> 1f
}

private const val WIDE = 1.2f
private val ALBUM_GAP = 2.dp
private val ALBUM_MIN_ROW = 64.dp
private val ALBUM_MAX_ROW = 300.dp
