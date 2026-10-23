package dev.otmgram.ui.chat

import dev.otmgram.core.media.Waveform
import dev.otmgram.data.Sticker
import dev.otmgram.data.StickerKind
import dev.otmgram.data.toSticker
import org.drinkless.tdlib.TdApi

/** Media of a message that Otmgram can show. Sizes are in pixels, durations in seconds. */
sealed interface MessageMedia {
    data class Photo(
        val fileId: Int,
        val width: Int,
        val height: Int,
        val minithumbnail: ByteArray?,
    ) : MessageMedia

    data class Video(
        val fileId: Int,
        val thumbnailFileId: Int?,
        val width: Int,
        val height: Int,
        val duration: Int,
        val minithumbnail: ByteArray?,
    ) : MessageMedia

    /** GIF-like silent looping video. */
    data class Animation(
        val fileId: Int,
        val thumbnailFileId: Int?,
        val width: Int,
        val height: Int,
        val minithumbnail: ByteArray?,
    ) : MessageMedia

    data class VoiceNote(
        val fileId: Int,
        val duration: Int,
        /** Volume levels 0..31 over the message. */
        val waveform: List<Int>,
    ) : MessageMedia

    /** Round video message. */
    data class VideoNote(
        val fileId: Int,
        val thumbnailFileId: Int?,
        val duration: Int,
        val minithumbnail: ByteArray?,
    ) : MessageMedia

    data class Sticker(
        val fileId: Int,
        val kind: StickerKind,
        val width: Int,
        val height: Int,
        val thumbnailFileId: Int?,
    ) : MessageMedia

    /** A file of any type (audio files too); downloaded only on request. */
    data class Document(val fileId: Int, val name: String, val size: Long, val mimeType: String) : MessageMedia

    /** A point; [live] is set for live locations. */
    data class Location(val latitude: Double, val longitude: Double, val live: LiveInfo?) : MessageMedia

    /** Unix times; [expiresAt] is [Int.MAX_VALUE] for locations shared until stopped. */
    data class LiveInfo(val updatedAt: Int, val expiresAt: Int)
}


/** Media of a message; live locations need the message's dates. */
fun TdApi.Message.toMedia(): MessageMedia? = when (val content = content) {
    is TdApi.MessageLiveLocation -> MessageMedia.Location(
        latitude = content.location.location.latitude,
        longitude = content.location.location.longitude,
        live = MessageMedia.LiveInfo(
            updatedAt = editDate.takeIf { it > 0 } ?: date,
            expiresAt = when {
                content.expiresIn == 0 -> 0 // Stopped or expired.
                content.location.livePeriod == LIVE_FOREVER -> Int.MAX_VALUE
                else -> date + content.location.livePeriod
            },
        ),
    )
    else -> content.toMedia()
}

fun TdApi.MessageContent.toMedia(): MessageMedia? = when (this) {
    is TdApi.MessagePhoto -> {
        // The largest size up to ~1280 px is plenty for a bubble.
        val size = photo.sizes.filter { maxOf(it.width, it.height) <= BUBBLE_PHOTO_MAX }.maxByOrNull { it.width }
            ?: photo.sizes.minByOrNull { it.width }
        size?.let { MessageMedia.Photo(it.photo.id, it.width, it.height, photo.minithumbnail?.data) }
    }
    is TdApi.MessageVideo -> MessageMedia.Video(
        fileId = video.video.id,
        thumbnailFileId = video.thumbnail?.file?.id,
        width = video.width,
        height = video.height,
        duration = video.duration,
        minithumbnail = video.minithumbnail?.data,
    )
    is TdApi.MessageAnimation -> MessageMedia.Animation(
        animation.animation.id, animation.thumbnail?.file?.id, animation.width, animation.height, animation.minithumbnail?.data,
    )
    is TdApi.MessageVoiceNote -> MessageMedia.VoiceNote(
        voiceNote.voice.id, voiceNote.duration, Waveform.decode(voiceNote.waveform),
    )
    is TdApi.MessageVideoNote -> MessageMedia.VideoNote(
        videoNote.video.id, videoNote.thumbnail?.file?.id, videoNote.duration, videoNote.minithumbnail?.data,
    )
    is TdApi.MessageSticker -> sticker.toSticker().toMedia()
    is TdApi.MessageLocation -> MessageMedia.Location(location.latitude, location.longitude, live = null)
    is TdApi.MessageDocument -> MessageMedia.Document(
        document.document.id, document.fileName.ifEmpty { "File" }, document.document.size, document.mimeType,
    )
    is TdApi.MessageAudio -> MessageMedia.Document(
        fileId = audio.audio.id,
        name = audio.fileName.ifEmpty { listOf(audio.performer, audio.title).filter { it.isNotEmpty() }.joinToString(" – ") }
            .ifEmpty { "Audio" },
        size = audio.audio.size,
        mimeType = audio.mimeType,
    )
    else -> null
}

fun Sticker.toMedia() = MessageMedia.Sticker(fileId, kind, width, height, thumbnailFileId)

/** Caption of media messages, empty if none. */
fun TdApi.MessageContent.caption(): String = when (this) {
    is TdApi.MessagePhoto -> caption.text
    is TdApi.MessageVideo -> caption.text
    is TdApi.MessageAnimation -> caption.text
    is TdApi.MessageVoiceNote -> caption.text
    is TdApi.MessageDocument -> caption.text
    is TdApi.MessageAudio -> caption.text
    else -> ""
}

private const val BUBBLE_PHOTO_MAX = 1280

/** TDLib's live_period for locations shared until stopped. */
const val LIVE_FOREVER = 0x7FFFFFFF
