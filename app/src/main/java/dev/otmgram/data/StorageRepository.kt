package dev.otmgram.data

import dev.otmgram.core.td.TdClient
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Kinds of downloaded files, for the storage bar in settings. */
enum class StorageKind(val label: String) { Videos("Videos"), Photos("Photos"), Voice("Voice"), Files("Files"), Other("Other") }

data class StorageUsage(val total: Long, val byKind: Map<StorageKind, Long>)

/** Downloaded files: how much space they take, and keeping them within the limit. */
@Singleton
class StorageRepository @Inject constructor(
    private val td: TdClient,
    private val settings: AppSettings,
) {
    suspend fun usage(): StorageUsage {
        // chatLimit 0: one entry for all chats together.
        val stats = td.send(TdApi.GetStorageStatistics(0))
        val byKind = mutableMapOf<StorageKind, Long>()
        stats.byChat.flatMap { it.byFileType.toList() }.forEach {
            val kind = kindOf(it.fileType)
            byKind[kind] = (byKind[kind] ?: 0) + it.size
        }
        return StorageUsage(stats.size, byKind)
    }

    /** Deletes the least recently used files beyond the chosen limit, if there is one. */
    suspend fun applyLimit() {
        val bytes = settings.cacheLimit.first().bytes ?: return
        // Size only: no limit on age or count; recently opened files are kept.
        td.send(TdApi.OptimizeStorage(bytes, Int.MAX_VALUE, Int.MAX_VALUE, -1, null, null, null, false, 0))
    }

    /** Deletes all downloaded files; they download again when needed. */
    suspend fun clear() {
        td.send(TdApi.OptimizeStorage(0, 0, 0, 0, null, null, null, false, 0))
    }

    private fun kindOf(type: TdApi.FileType): StorageKind = when (type) {
        is TdApi.FileTypeVideo, is TdApi.FileTypeVideoNote, is TdApi.FileTypeAnimation -> StorageKind.Videos
        is TdApi.FileTypePhoto, is TdApi.FileTypeThumbnail, is TdApi.FileTypeProfilePhoto -> StorageKind.Photos
        is TdApi.FileTypeVoiceNote, is TdApi.FileTypeAudio -> StorageKind.Voice
        is TdApi.FileTypeDocument -> StorageKind.Files
        else -> StorageKind.Other
    }
}
