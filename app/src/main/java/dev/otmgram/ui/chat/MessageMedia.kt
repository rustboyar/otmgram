package dev.otmgram.ui.chat

import org.drinkless.tdlib.TdApi

/** Media of a message that Otmgram can show. Sizes are in pixels, durations in seconds. */
sealed interface MessageMedia {
    data class Photo(
        val fileId: Int,
        val width: Int,
        val height: Int,
        val minithumbnail: TdApi.Minithumbnail?,
    ) : MessageMedia

    data class Video(
        val fileId: Int,
        val thumbnailFileId: Int?,
        val width: Int,
        val height: Int,
        val duration: Int,
        val minithumbnail: TdApi.Minithumbnail?,
    ) : MessageMedia

    /** GIF-like silent looping video. */
    data class Animation(
        val fileId: Int,
        val width: Int,
        val height: Int,
        val minithumbnail: TdApi.Minithumbnail?,
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
        val minithumbnail: TdApi.Minithumbnail?,
    ) : MessageMedia

    data class Sticker(
        val fileId: Int,
        val kind: StickerKind,
        val width: Int,
        val height: Int,
        val thumbnailFileId: Int?,
    ) : MessageMedia
}

enum class StickerKind { Image, Lottie, Video }

fun TdApi.MessageContent.toMedia(): MessageMedia? = when (this) {
    is TdApi.MessagePhoto -> {
        // The largest size up to ~1280 px is plenty for a bubble.
        val size = photo.sizes.filter { maxOf(it.width, it.height) <= BUBBLE_PHOTO_MAX }.maxByOrNull { it.width }
            ?: photo.sizes.minByOrNull { it.width }
        size?.let { MessageMedia.Photo(it.photo.id, it.width, it.height, photo.minithumbnail) }
    }
    is TdApi.MessageVideo -> MessageMedia.Video(
        fileId = video.video.id,
        thumbnailFileId = video.thumbnail?.file?.id,
        width = video.width,
        height = video.height,
        duration = video.duration,
        minithumbnail = video.minithumbnail,
    )
    is TdApi.MessageAnimation -> MessageMedia.Animation(
        animation.animation.id, animation.width, animation.height, animation.minithumbnail,
    )
    is TdApi.MessageVoiceNote -> MessageMedia.VoiceNote(
        voiceNote.voice.id, voiceNote.duration, decodeWaveform(voiceNote.waveform),
    )
    is TdApi.MessageVideoNote -> MessageMedia.VideoNote(
        videoNote.video.id, videoNote.thumbnail?.file?.id, videoNote.duration, videoNote.minithumbnail,
    )
    is TdApi.MessageSticker -> MessageMedia.Sticker(
        fileId = sticker.sticker.id,
        kind = when (sticker.format) {
            is TdApi.StickerFormatTgs -> StickerKind.Lottie
            is TdApi.StickerFormatWebm -> StickerKind.Video
            else -> StickerKind.Image
        },
        width = sticker.width,
        height = sticker.height,
        thumbnailFileId = sticker.thumbnail?.file?.id,
    )
    else -> null
}

/** Caption of media messages, empty if none. */
fun TdApi.MessageContent.caption(): String = when (this) {
    is TdApi.MessagePhoto -> caption.text
    is TdApi.MessageVideo -> caption.text
    is TdApi.MessageAnimation -> caption.text
    is TdApi.MessageVoiceNote -> caption.text
    else -> ""
}

/** TDLib packs waveforms as 5-bit values. */
private fun decodeWaveform(data: ByteArray?): List<Int> {
    if (data == null) return emptyList()
    val count = data.size * 8 / 5
    return List(count) { i ->
        val bit = i * 5
        val low = data[bit / 8].toInt() and 0xFF
        val high = (data.getOrNull(bit / 8 + 1)?.toInt() ?: 0) and 0xFF
        ((low or (high shl 8)) ushr (bit % 8)) and 0x1F
    }
}

private const val BUBBLE_PHOTO_MAX = 1280
