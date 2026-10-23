package dev.otmgram.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.td.TdClient
import dev.otmgram.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

data class Folder(val id: Int, val name: String)

private val Context.settings by preferencesDataStore("settings")
private val SELECTED_FOLDER_ID = intPreferencesKey("selected_folder_id")

/** The user's chat folders and the one folder Otmgram shows, picked once after login. */
@Singleton
class FolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    td: TdClient,
    @ApplicationScope scope: CoroutineScope,
) {
    /** All chat folders, or null until TDLib sends them. */
    val folders: Flow<List<Folder>?> = td.chatFolders.map { folders ->
        folders?.map { Folder(it.id, it.name.text.text) }
    }

    val selectedFolderId: Flow<Int?> = context.settings.data.map { it[SELECTED_FOLDER_ID] }

    init {
        // The choice belongs to the logged-in account: forget it once logged out,
        // including logouts from another device.
        scope.launch {
            td.authorizationState.collect {
                if (it is TdApi.AuthorizationStateWaitPhoneNumber) {
                    context.settings.edit { prefs -> prefs.remove(SELECTED_FOLDER_ID) }
                }
            }
        }
    }

    suspend fun selectFolder(id: Int) {
        context.settings.edit { it[SELECTED_FOLDER_ID] = id }
    }
}
