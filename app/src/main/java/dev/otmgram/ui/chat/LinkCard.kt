package dev.otmgram.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.otmgram.R
import dev.otmgram.ui.embed.MediaLink
import dev.otmgram.ui.media.TdImage
import dev.otmgram.ui.theme.Otm
import dev.otmgram.ui.theme.OtmIcons
import dev.otmgram.ui.theme.OtmType
import org.drinkless.tdlib.TdApi

/**
 * A video or post linked in a message, played in the app; with the title and picture of
 * Telegram's preview, when the message has one (senders' apps often leave it out).
 */
data class LinkCard(
    val link: MediaLink,
    val title: String?,
    val thumbnailFileId: Int?,
    val minithumbnail: ByteArray?,
    val width: Int,
    val height: Int,
)

/** The first media link of a text message, if it has one. */
internal fun TdApi.MessageText.linkCard(): LinkCard? {
    val preview = linkPreview
    val urls = listOfNotNull(preview?.url) + text.spans().mapNotNull { (it.kind as? SpanKind.Link)?.url }
    val link = urls.firstNotNullOfOrNull(MediaLink::parse) ?: return null
    // The preview may be of another link of the message.
    val described = preview?.takeIf { MediaLink.parse(it.url)?.let { p -> p.site == link.site && p.id == link.id } == true }
    val photo = described?.type?.picture()
    val size = photo?.sizes?.filter { maxOf(it.width, it.height) <= CARD_PHOTO_MAX }?.maxByOrNull { it.width }
        ?: photo?.sizes?.minByOrNull { it.width }
    return LinkCard(
        link = link,
        title = described?.title?.takeIf { it.isNotBlank() },
        thumbnailFileId = size?.photo?.id,
        minithumbnail = photo?.minithumbnail?.data,
        width = size?.width ?: 0,
        height = size?.height ?: 0,
    )
}

private fun TdApi.LinkPreviewType.picture(): TdApi.Photo? = when (this) {
    is TdApi.LinkPreviewTypeEmbeddedVideoPlayer -> thumbnail
    is TdApi.LinkPreviewTypeVideo -> cover
    is TdApi.LinkPreviewTypePhoto -> photo
    is TdApi.LinkPreviewTypeArticle -> photo
    is TdApi.LinkPreviewTypeAlbum -> media.firstNotNullOfOrNull { (it as? TdApi.LinkPreviewAlbumMediaPhoto)?.photo }
    else -> null
}

private const val CARD_PHOTO_MAX = 800

/** The card under the message's text: the picture with a play button, or a plain row without one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LinkCardView(card: LinkCard, onOpen: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val site = card.link.site.name
    Column(
        modifier = modifier
            .width(CARD_WIDTH)
            .clip(RoundedCornerShape(12.dp))
            .background(Otm.Ink.copy(alpha = 0.06f))
            .combinedClickable(onClick = onOpen, onLongClick = onLongClick, onClickLabel = stringResource(R.string.link_play_here))
            .testTag("linkCard"),
    ) {
        if (card.thumbnailFileId != null || card.minithumbnail != null) {
            val ratio = when {
                card.link.upright -> 0.8f
                card.width > 0 && card.height > 0 -> (card.width.toFloat() / card.height).coerceIn(0.75f, 1.9f)
                else -> 16f / 9f
            }
            Box(Modifier.fillMaxWidth().aspectRatio(ratio), contentAlignment = Alignment.Center) {
                TdImage(card.thumbnailFileId, card.minithumbnail, Modifier.matchParentSize())
                PlayButton()
                Text(
                    site,
                    color = Color.White,
                    style = OtmType.Time,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Otm.Ink.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            card.title?.let {
                Text(
                    it,
                    style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold),
                    color = Otm.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        } else {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Otm.Slate), contentAlignment = Alignment.Center) {
                    Icon(OtmIcons.Play, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(card.title ?: site, style = OtmType.Secondary.copy(fontWeight = FontWeight.SemiBold), color = Otm.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.link_play_here), style = OtmType.Time, color = Otm.InkSoft)
                }
            }
        }
    }
}

@Composable
private fun PlayButton() {
    Box(Modifier.size(48.dp).background(Otm.Sheet.copy(alpha = 0.88f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(OtmIcons.Play, stringResource(R.string.play_video), tint = Otm.Ink, modifier = Modifier.size(18.dp))
    }
}

private val CARD_WIDTH = 240.dp
