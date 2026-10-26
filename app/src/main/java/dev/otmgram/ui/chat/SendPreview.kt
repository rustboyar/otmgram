package dev.otmgram.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.data.SizeEstimate
import dev.otmgram.ui.format.formatSize
import dev.otmgram.ui.rememberStrings
import dev.otmgram.ui.theme.DialogTags
import dev.otmgram.ui.theme.FullScreenDialog
import dev.otmgram.ui.theme.IconBtn
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmSwitch
import dev.otmgram.ui.theme.OtmType
import dev.otmgram.ui.theme.PillField
import dev.otmgram.ui.theme.paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picked photos and videos, large, with a caption field and Compress, before sending. */
@Composable
fun MediaPreviewScreen(
    uris: List<Uri>,
    chatTitle: String,
    compressByDefault: Boolean,
    sizes: SizeEstimate?,
    onSend: (caption: String, compress: Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var compress by rememberSaveable { mutableStateOf(compressByDefault) }
    var shown by rememberSaveable { mutableIntStateOf(0) }
    SendPreview(
        title = pluralStringResource(R.plurals.media_items, uris.size, uris.size),
        chatTitle = chatTitle,
        captionTag = "caption",
        sendTag = "sendMedia",
        onSend = { onSend(it, compress) },
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        AsyncImage(
            model = uris.getOrNull(shown),
            contentDescription = stringResource(R.string.media_selected),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Otm.Haze),
        )
        if (uris.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                itemsIndexed(uris) { index, uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = stringResource(R.string.media_item, index + 1),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .then(if (index == shown) Modifier.border(2.dp, Otm.Slate, RoundedCornerShape(12.dp)).padding(3.dp) else Modifier)
                            .clip(RoundedCornerShape(if (index == shown) 9.dp else 12.dp))
                            .selectable(selected = index == shown, role = Role.Tab) { shown = index },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(compress, role = Role.Switch) { compress = it }.testTag("compress").padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.compress), style = OtmType.Body.copy(fontWeight = FontWeight.SemiBold), color = Otm.Ink)
                Text(sizeLine(sizes, compress, rememberStrings()), style = OtmType.Secondary, color = Otm.InkSoft, modifier = Modifier.testTag("sizeEstimate"))
            }
            OtmSwitch(compress, onCheckedChange = null)
        }
    }
}

/** "About 2.4 MB instead of 31 MB", or the size as it goes. */
internal fun sizeLine(sizes: SizeEstimate?, compress: Boolean, strings: Strings): String = when {
    sizes == null -> strings.get(if (compress) R.string.size_compress_unknown else R.string.size_full_unknown)
    !compress -> strings.get(R.string.size_full, formatSize(sizes.original))
    sizes.compressed < sizes.original * 9 / 10 -> strings.get(R.string.size_compressed, formatSize(sizes.compressed), formatSize(sizes.original))
    else -> strings.get(R.string.size_already_small, formatSize(sizes.original))
}

/** Picked files (sent as they are) with a caption field, before sending. */
@Composable
fun FilesPreviewScreen(uris: List<Uri>, chatTitle: String, onSend: (caption: String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Names and sizes come from the app that has the files, which may be slow to answer.
    val files by produceState(emptyList(), uris) {
        value = withContext(Dispatchers.IO) {
            uris.map { uri ->
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) to formatSize(it.getLong(1)) else null }
                }.getOrNull() ?: (uri.lastPathSegment.orEmpty() to "")
            }
        }
    }
    SendPreview(
        title = pluralStringResource(R.plurals.files_count, uris.size, uris.size),
        chatTitle = chatTitle,
        captionTag = "fileCaption",
        sendTag = "sendFiles",
        onSend = onSend,
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files) { (name, size) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Otm.Card).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Otm.Haze), contentAlignment = Alignment.Center) {
                        Icon(OtmIcons.File, null, tint = Otm.SlateDeep, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(name, style = OtmType.Body.copy(fontWeight = FontWeight.Medium), color = Otm.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (size.isNotEmpty()) Text(size, style = OtmType.Secondary, color = Otm.InkSoft)
                    }
                }
            }
        }
        Text(stringResource(R.string.files_hint), style = OtmType.Secondary, color = Otm.InkSoft, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun SendPreview(
    title: String,
    chatTitle: String,
    captionTag: String,
    sendTag: String,
    onSend: (caption: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    var caption by rememberSaveable { mutableStateOf("") }
    FullScreenDialog(onDismiss) {
        Column(modifier.fillMaxSize().paper().systemBarsPadding().imePadding().then(DialogTags)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(OtmIcons.Close, stringResource(R.string.cancel), onDismiss, tag = "cancelSend")
                Column(Modifier.padding(start = 6.dp)) {
                    Text(title, style = OtmType.BarTitle, color = Otm.Ink)
                    Text(stringResource(R.string.send_to, chatTitle), style = OtmType.Secondary, color = Otm.InkSoft)
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PillField(
                    value = caption,
                    onValueChange = { caption = it },
                    placeholder = stringResource(R.string.caption),
                    maxLines = 4,
                    bordered = true,
                    modifier = Modifier.weight(1f).testTag(captionTag),
                )
                IconBtn(OtmIcons.Send, stringResource(R.string.send), { onSend(caption) }, tint = Color.White, background = Otm.Slate, tag = sendTag)
            }
        }
    }
}
