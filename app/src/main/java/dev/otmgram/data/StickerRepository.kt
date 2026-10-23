package dev.otmgram.data

import dev.otmgram.core.td.TdClient
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** A tab of the sticker picker: recently used stickers, or an installed set. */
data class StickerTab(val setId: Long?, val title: String)

@Singleton
class StickerRepository @Inject constructor(
    private val td: TdClient,
) {
    /** "Recent" followed by the installed sticker sets. */
    suspend fun tabs(): List<StickerTab> {
        val sets = td.send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular())).sets
        return listOf(StickerTab(null, "Recent")) + sets.map { StickerTab(it.id, it.title) }
    }

    suspend fun stickers(tab: StickerTab): List<TdApi.Sticker> =
        if (tab.setId == null) {
            td.send(TdApi.GetRecentStickers(false)).stickers.toList()
        } else {
            td.send(TdApi.GetStickerSet(tab.setId)).stickers.toList()
        }
}
