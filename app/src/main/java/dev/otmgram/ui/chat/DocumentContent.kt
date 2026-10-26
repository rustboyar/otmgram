package dev.otmgram.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.otmgram.R
import dev.otmgram.core.launchLogged
import dev.otmgram.data.FileState
import dev.otmgram.ui.format.formatSize
import dev.otmgram.ui.media.LocalFileRepository
import dev.otmgram.ui.media.openFile
import dev.otmgram.ui.media.rememberFileState
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import java.io.File

/** A file: tap downloads it, tap again (once downloaded) opens it in another app. */
@Composable
internal fun DocumentContent(media: MessageMedia.Document, onLongClick: () -> Unit, onCancelUpload: (() -> Unit)?) {
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
                    downloading -> scope.launchLogged("DocumentContent") { files.cancelDownload(media.fileId) }
                    else -> scope.launchLogged("DocumentContent") { files.startDownload(media.fileId) }
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
internal fun UploadRing(fileId: Int, onCancel: () -> Unit) {
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
