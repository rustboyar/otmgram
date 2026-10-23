package dev.otmgram.data

import dev.otmgram.core.td.TdClient
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

enum class StickerKind { Image, Lottie, Video }

/** A sticker: enough to show it and to send it. */
data class Sticker(
    val fileId: Int,
    val kind: StickerKind,
    val width: Int,
    val height: Int,
    /** Still image for kinds that can't be animated here (video stickers). */
    val thumbnailFileId: Int?,
    val emoji: String,
)

fun TdApi.Sticker.toSticker() = Sticker(
    fileId = sticker.id,
    kind = when (format) {
        is TdApi.StickerFormatTgs -> StickerKind.Lottie
        is TdApi.StickerFormatWebm -> StickerKind.Video
        else -> StickerKind.Image
    },
    width = width,
    height = height,
    thumbnailFileId = thumbnail?.file?.id,
    emoji = emoji,
)

/** A tab of the sticker picker: recently used stickers, or an installed set with its [cover]. */
data class StickerTab(val setId: Long?, val title: String, val cover: Sticker? = null)

@Singleton
class StickerRepository @Inject constructor(
    private val td: TdClient,
) {
    /** "Recent" followed by the installed sticker sets. */
    suspend fun tabs(): List<StickerTab> {
        val sets = td.send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular())).sets
        return listOf(StickerTab(null, "Recent")) + sets.map { StickerTab(it.id, it.title, it.covers.firstOrNull()?.toSticker()) }
    }

    suspend fun stickers(tab: StickerTab): List<Sticker> =
        if (tab.setId == null) {
            td.send(TdApi.GetRecentStickers(false)).stickers.map { it.toSticker() }
        } else {
            td.send(TdApi.GetStickerSet(tab.setId)).stickers.map { it.toSticker() }
        }
}
