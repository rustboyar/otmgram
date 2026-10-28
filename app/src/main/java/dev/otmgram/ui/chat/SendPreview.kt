package dev.otmgram.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
import dev.otmgram.core.media.FilmLook
import dev.otmgram.data.FilmPreview
import dev.otmgram.data.FilmPreviews
import dev.otmgram.data.SendQuality
import dev.otmgram.data.SizeEstimate
import dev.otmgram.ui.format.formatSize
import dev.otmgram.ui.rememberStrings
import dev.otmgram.ui.theme.Chip
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Picked photos and videos, large, with a caption field and the quality to send them in. */
@Composable
fun MediaPreviewScreen(
    uris: List<Uri>,
    chatTitle: String,
    defaultQuality: SendQuality,
    sizes: SizeEstimate?,
    onSend: (caption: String, quality: SendQuality, film: FilmLook.Stock?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Develops the photos in films; null shows no film choice. */
    films: FilmPreviews? = null,
    /** The film used last, offered right after None. */
    lastFilm: FilmLook.Stock? = null,
) {
    var quality by rememberSaveable { mutableStateOf(defaultQuality) }
    var shown by rememberSaveable { mutableIntStateOf(0) }
    // None to start with, every time: no photo gets a look by accident.
    var film by rememberSaveable { mutableStateOf<FilmLook.Stock?>(null) }
    val shownUri = uris.getOrNull(shown)
    val photos = remember(uris) { films?.let { f -> uris.filter(f::canDevelop) }.orEmpty() }
    // The one the films are shown on: the photo shown, or the first photo while a video is.
    val filmUri = shownUri?.takeIf { it in photos } ?: photos.firstOrNull()
    val preview by produceState<FilmPreview?>(null, filmUri) {
        // Not cancelled midway: a load cancelled at the end would lose its glow (native
        // memory) unclosed. Closed here instead, if this was left meanwhile.
        val loaded = filmUri?.let { uri -> withContext(NonCancellable) { films?.load(uri) } }
        if (!isActive) {
            loaded?.close()
            return@produceState
        }
        value = loaded
        // Off the screen before it's closed: the next photo takes a moment to load.
        awaitDispose {
            value = null
            loaded?.close()
        }
    }
    SendPreview(
        title = pluralStringResource(R.plurals.media_items, uris.size, uris.size),
        chatTitle = chatTitle,
        captionTag = "caption",
        sendTag = "sendMedia",
        onSend = { onSend(it, quality, film) },
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        val developing = preview?.takeIf { shownUri == filmUri }
        if (developing == null) {
            AsyncImage(
                model = shownUri,
                contentDescription = stringResource(R.string.media_selected),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Otm.Haze),
            )
        } else {
            FilmPhoto(developing, film, Modifier.fillMaxWidth().weight(1f))
        }
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
        preview?.let { ready ->
            FilmStrip(ready, film, lastFilm, onPick = { film = it }, videoShown = shownUri != filmUri)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.quality), style = OtmType.Body.copy(fontWeight = FontWeight.SemiBold), color = Otm.Ink, modifier = Modifier.weight(1f))
                SendQuality.entries.forEach { option ->
                    Chip(stringResource(option.label), option == quality, { quality = option }, Modifier.testTag("quality-${option.name}"))
                }
            }
            // A photo with a film goes at most HD's size, Original too.
            val sizeQuality = if (film != null && quality == SendQuality.Original) SendQuality.HD else quality
            Text(sizeLine(sizes, sizeQuality, rememberStrings()), style = OtmType.Secondary, color = Otm.InkSoft, modifier = Modifier.testTag("sizeEstimate"))
        }
    }
}

/** The photo in [film]; pressed and held, as it is, to compare. */
@Composable
private fun FilmPhoto(preview: FilmPreview, film: FilmLook.Stock?, modifier: Modifier = Modifier) {
    var holding by remember { mutableStateOf(false) }
    // The last one stays until the next is developed, rather than flashing empty.
    var shown by remember(preview) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(preview, film, holding) {
        shown = preview.developed(if (holding) null else film).asImageBitmap()
    }
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Otm.Haze)
            .pointerInput(preview) {
                detectTapGestures(onPress = {
                    holding = true
                    tryAwaitRelease()
                    holding = false
                })
            }
            .testTag("filmPhoto"),
    ) {
        shown?.let { Image(it, stringResource(R.string.media_selected), Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        if (film != null) {
            Text(
                stringResource(if (holding) R.string.film_without else R.string.film_hold),
                style = OtmType.Time,
                color = Color.White,
                modifier = Modifier
                    .align(if (holding) Alignment.TopStart else Alignment.BottomCenter)
                    .padding(10.dp)
                    .background(Otm.Ink.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * The photo in each film, to pick one by seeing it: None first, then the one used last, then
 * the others in tungsten's order. With the credit, as the looks are tungsten's.
 */
@Composable
private fun FilmStrip(preview: FilmPreview, film: FilmLook.Stock?, lastFilm: FilmLook.Stock?, onPick: (FilmLook.Stock?) -> Unit, videoShown: Boolean) {
    val order = remember(lastFilm) { listOf<FilmLook.Stock?>(null) + listOfNotNull(lastFilm) + FilmLook.Stock.entries.filter { it != lastFilm } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("filmStrip")) {
            items(order, key = { it?.name ?: "none" }) { stock ->
                val thumbnail by produceState<ImageBitmap?>(null, preview, stock) { value = preview.thumbnail(stock).asImageBitmap() }
                val selected = stock == film
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(selected = selected, role = Role.RadioButton) { onPick(stock) }
                        .testTag("film-${stock?.name ?: "None"}"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .then(if (selected) Modifier.border(2.dp, Otm.Slate, RoundedCornerShape(12.dp)).padding(3.dp) else Modifier)
                            .clip(RoundedCornerShape(if (selected) 9.dp else 12.dp))
                            .background(Otm.Haze),
                    ) {
                        thumbnail?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                    }
                    Text(
                        stringResource(filmName(stock)),
                        style = OtmType.Time.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (selected) Otm.SlateDeep else Otm.InkSoft,
                    )
                }
            }
        }
        val uriHandler = LocalUriHandler.current
        Text(
            if (videoShown) stringResource(R.string.film_no_videos) else stringResource(R.string.film_credit),
            style = OtmType.Time,
            color = Otm.InkFaint,
            modifier = Modifier
                .padding(start = 2.dp)
                .then(if (videoShown) Modifier else Modifier.clickable(role = Role.Button) { uriHandler.openUri(TUNGSTEN_URL) })
                .testTag("filmCredit"),
        )
    }
}

@StringRes
private fun filmName(stock: FilmLook.Stock?): Int = when (stock) {
    null -> R.string.film_none
    FilmLook.Stock.Cinestill800T -> R.string.film_800t
    FilmLook.Stock.Cinestill50D -> R.string.film_50d
    FilmLook.Stock.Portra400 -> R.string.film_portra
    FilmLook.Stock.Ektar100 -> R.string.film_ektar
    FilmLook.Stock.Superia400 -> R.string.film_superia
    FilmLook.Stock.TriX400 -> R.string.film_trix
}

private const val TUNGSTEN_URL = "https://github.com/ian-te/tungsten"

/** "About 2.4 MB instead of 31 MB", or the size as it goes. */
internal fun sizeLine(sizes: SizeEstimate?, quality: SendQuality, strings: Strings): String = when {
    sizes == null -> strings.get(
        when (quality) {
            SendQuality.SD -> R.string.size_sd_unknown
            SendQuality.HD -> R.string.size_hd_unknown
            SendQuality.Original -> R.string.size_full_unknown
        },
    )
    quality == SendQuality.Original -> strings.get(R.string.size_full, formatSize(sizes.original))
    sizes.of(quality) < sizes.original * 9 / 10 -> strings.get(R.string.size_compressed, formatSize(sizes.of(quality)), formatSize(sizes.original))
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
