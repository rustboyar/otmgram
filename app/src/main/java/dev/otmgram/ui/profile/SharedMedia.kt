package dev.otmgram.ui.profile

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.otmgram.R
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.data.senderName
import dev.otmgram.ui.chat.MessageMedia
import dev.otmgram.ui.chat.SpanKind
import dev.otmgram.ui.chat.formattedText
import dev.otmgram.ui.chat.picture
import dev.otmgram.ui.chat.spans
import dev.otmgram.ui.chat.toMedia
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.drinkless.tdlib.TdApi
import java.net.URI

/** What a chat has shared, a tab each on the profile, as in Telegram's apps. */
enum class SharedKind(@StringRes val label: Int) {
    Media(R.string.shared_media),
    Files(R.string.shared_files),
    Links(R.string.shared_links),

    /** Voice messages and round videos. */
    Voice(R.string.shared_voice),
    ;

    internal fun filter(): TdApi.SearchMessagesFilter = when (this) {
        Media -> TdApi.SearchMessagesFilterPhotoAndVideo()
        Files -> TdApi.SearchMessagesFilterDocument()
        Links -> TdApi.SearchMessagesFilterUrl()
        Voice -> TdApi.SearchMessagesFilterVoiceAndVideoNote()
    }
}

/** One message of a [SharedKind]: what its row or square shows. */
@Immutable
sealed interface SharedItem {
    val messageId: Long

    /** Unix time it was sent. */
    val date: Int

    /** A photo or video in the grid: a small picture of it; [duration] only for videos. */
    data class Visual(
        override val messageId: Long,
        override val date: Int,
        val thumbnailFileId: Int?,
        val minithumbnail: ByteArray?,
        val duration: Int?,
    ) : SharedItem

    data class File(override val messageId: Long, override val date: Int, val document: MessageMedia.Document) : SharedItem

    /** [title]: the preview's, or the site's address; [text]: the message, for what it's about. */
    data class Link(
        override val messageId: Long,
        override val date: Int,
        val url: String,
        val title: String,
        val text: String,
        val thumbnailFileId: Int?,
        val minithumbnail: ByteArray?,
    ) : SharedItem

    data class Voice(override val messageId: Long, override val date: Int, val sender: String?, val voice: MessageMedia.VoiceNote) : SharedItem

    data class Round(override val messageId: Long, override val date: Int, val sender: String?, val round: MessageMedia.VideoNote) : SharedItem
}

/**
 * A chat's shared media, files, links and voice, newest first, a page at a time. Only the
 * tabs with something in them show.
 */
class SharedMedia(private val td: TelegramClient, private val chatId: Long) {
    /** How many of each kind, for the tabs; kinds the chat has none of are left out. */
    var counts by mutableStateOf<Map<SharedKind, Int>>(emptyMap())
        private set

    var kind by mutableStateOf<SharedKind?>(null)
        private set

    var items by mutableStateOf<List<SharedItem>>(emptyList())
        private set

    /** Everything of [kind] is loaded. */
    var complete by mutableStateOf(false)
        private set

    private var next = 0L

    suspend fun loadCounts() {
        counts = coroutineScope {
            SharedKind.entries.map { kind -> async { kind to td.send(TdApi.GetChatMessageCount(chatId, null, kind.filter(), false)).count } }.awaitAll()
        }.filter { it.second > 0 }.toMap()
    }

    /** Shows [kind] from its newest; cancel a page still loading for the last one first. */
    suspend fun select(kind: SharedKind) {
        this.kind = kind
        items = emptyList()
        complete = false
        next = 0
        loadMore()
    }

    /** The next page of [kind], when the list nears its end. */
    suspend fun loadMore() {
        val kind = kind ?: return
        if (complete) return
        val found = td.send(TdApi.SearchChatMessages(chatId, null, "", null, next, 0, PAGE, kind.filter()))
        val names = mutableMapOf<Long, String?>()
        val page = found.messages.mapNotNull { message ->
            val sender = if (kind == SharedKind.Voice) names.getOrPut(message.senderId.id()) { name(message.senderId) } else null
            message.toShared(kind, sender)
        }
        items = items + page.filter { item -> items.none { it.messageId == item.messageId } }
        next = found.nextFromMessageId
        complete = next == 0L || found.messages.isEmpty()
    }

    /** Who sent a voice message; unknown leaves it out rather than the page. */
    private suspend fun name(sender: TdApi.MessageSender): String? = try {
        td.senderName(sender)
    } catch (e: TdException) {
        null
    }

    private fun TdApi.MessageSender.id(): Long = when (this) {
        is TdApi.MessageSenderUser -> userId
        is TdApi.MessageSenderChat -> chatId
        else -> 0
    }
}

/** What [kind]'s tab shows of a message; null if it has nothing for it. */
internal fun TdApi.Message.toShared(kind: SharedKind, sender: String? = null): SharedItem? = when (kind) {
    SharedKind.Links -> sharedLink()
    else -> when (val content = content) {
        is TdApi.MessagePhoto -> {
            // Small, as the grid's squares are: sharp, and quick to download.
            val size = content.photo.sizes.filter { minOf(it.width, it.height) >= GRID_PHOTO_MIN }.minByOrNull { it.width }
                ?: content.photo.sizes.maxByOrNull { it.width }
            SharedItem.Visual(id, date, size?.photo?.id, content.photo.minithumbnail?.data, duration = null)
        }
        is TdApi.MessageVideo -> SharedItem.Visual(id, date, content.video.thumbnail?.file?.id, content.video.minithumbnail?.data, content.video.duration)
        is TdApi.MessageDocument, is TdApi.MessageAudio -> (content.toMedia() as? MessageMedia.Document)?.let { SharedItem.File(id, date, it) }
        is TdApi.MessageVoiceNote -> (content.toMedia() as? MessageMedia.VoiceNote)?.let { SharedItem.Voice(id, date, sender, it) }
        is TdApi.MessageVideoNote -> (content.toMedia() as? MessageMedia.VideoNote)?.let { SharedItem.Round(id, date, sender, it) }
        else -> null
    }
}

/** The message's first link (in its text or caption), with Telegram's preview when it's of that link. */
private fun TdApi.Message.sharedLink(): SharedItem.Link? {
    val text = content.formattedText() ?: return null
    val links = text.spans().mapNotNull { (it.kind as? SpanKind.Link)?.url }
    val preview = (content as? TdApi.MessageText)?.linkPreview
    val url = links.firstOrNull() ?: preview?.url ?: return null
    val described = preview?.takeIf { it.url == url || links.isEmpty() }
    val photo = described?.type?.picture()
    val size = photo?.sizes?.filter { minOf(it.width, it.height) >= LINK_PHOTO_MIN }?.minByOrNull { it.width } ?: photo?.sizes?.maxByOrNull { it.width }
    return SharedItem.Link(
        messageId = id,
        date = date,
        url = url,
        title = described?.title?.takeIf { it.isNotBlank() } ?: described?.siteName?.takeIf { it.isNotBlank() } ?: host(url),
        text = text.text,
        thumbnailFileId = size?.photo?.id,
        minithumbnail = photo?.minithumbnail?.data,
    )
}

/** "example.com" of a link, without "www."; the link itself if it can't be read. */
private fun host(url: String): String = try {
    URI(if ("://" in url) url else "https://$url").host?.removePrefix("www.")
} catch (e: IllegalArgumentException) {
    null
} ?: url

private const val PAGE = 60

/** Photo sizes at least this many px on their short side fill a grid square sharply. */
private const val GRID_PHOTO_MIN = 240
private const val LINK_PHOTO_MIN = 90
