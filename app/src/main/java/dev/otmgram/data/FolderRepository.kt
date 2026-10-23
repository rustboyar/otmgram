package dev.otmgram.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.td.TdClient
import dev.otmgram.core.td.TdException
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

data class Folder(val id: Int, val name: String)

private val SELECTED_FOLDER_ID = intPreferencesKey("selected_folder_id")
private val HEADER_NAME = stringPreferencesKey("header_name")

/**
 * Longest name on top, in characters: typical names of this length still show in full at
 * about 20 sp on a narrow phone; the header shrinks the font to fit.
 */
const val MAX_HEADER_NAME = 20

/** The user's chat folders and the one folder Otmgram shows, picked once after login. */
@Singleton
class FolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    @ApplicationScope scope: CoroutineScope,
) {
    /** All chat folders, or null until TDLib sends them. */
    val folders: Flow<List<Folder>?> = td.chatFolders.map { folders ->
        folders?.map { Folder(it.id, it.name.text.text) }
    }

    val selectedFolderId: Flow<Int?> = context.settings.data.map { it[SELECTED_FOLDER_ID] }

    /**
     * What the chat list shows on top instead of the folder's name; empty shows nothing,
     * null the folder's name. Only for display: the folder in Telegram keeps its name.
     */
    val headerName: Flow<String?> = context.settings.data.map { it[HEADER_NAME] }

    /** The name shown on top: [headerName], or the picked folder's name. */
    val displayName: Flow<String> = combine(folders, selectedFolderId, headerName) { all, id, header ->
        header ?: all?.find { it.id == id }?.name.orEmpty()
    }

    init {
        // The choice belongs to the logged-in account: forget it once logged out,
        // including logouts from another device.
        scope.launch {
            td.authorizationState.collect {
                if (it is TdApi.AuthorizationStateWaitPhoneNumber) {
                    context.settings.edit { prefs ->
                        prefs.remove(SELECTED_FOLDER_ID)
                        prefs.remove(HEADER_NAME)
                    }
                }
            }
        }
    }

    /** How many chats the folder has, channels included; null if TDLib can't tell. */
    suspend fun chatCount(id: Int): Int? = try {
        td.send(TdApi.GetChatFolderChatCount(td.send(TdApi.GetChatFolder(id)))).count
    } catch (e: TdException) {
        null
    }

    /** Null goes back to the folder's name; longer names are cut to [MAX_HEADER_NAME]. */
    suspend fun setHeaderName(name: String?) {
        context.settings.edit { if (name == null) it.remove(HEADER_NAME) else it[HEADER_NAME] = name.limitTo(MAX_HEADER_NAME) }
    }

    suspend fun selectFolder(id: Int) {
        context.settings.edit { it[SELECTED_FOLDER_ID] = id }
    }
}

/** At most [max] characters, never splitting an emoji or other surrogate pair. */
fun String.limitTo(max: Int): String =
    if (codePointCount(0, length) <= max) this else substring(0, offsetByCodePoints(0, max))
