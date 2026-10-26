package dev.otmgram.ui.settings

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.otmgram.core.launchLogged
import dev.otmgram.core.log.AppLog
import dev.otmgram.core.log.LogArchive
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.data.AppSettings
import dev.otmgram.data.AuthRepository
import dev.otmgram.data.CacheLimit
import dev.otmgram.data.CheckInterval
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.MAX_HEADER_NAME
import dev.otmgram.data.NotificationMode
import dev.otmgram.data.StorageRepository
import dev.otmgram.data.StorageUsage
import dev.otmgram.data.fullName
import dev.otmgram.data.limitTo
import dev.otmgram.sync.PushRegistration
import dev.otmgram.sync.PushState
import dev.otmgram.sync.PushStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject

/** The logged-in account, for the card on top. */
data class Account(val id: Long, val name: String, val phone: String?, val photoFileId: Int?, val minithumbnail: ByteArray?)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TelegramClient,
    private val auth: AuthRepository,
    private val folders: FolderRepository,
    private val settings: AppSettings,
    private val storage: StorageRepository,
    private val push: PushRegistration,
    pushState: PushState,
) : ViewModel() {

    private val _me = MutableStateFlow<Account?>(null)
    val me: StateFlow<Account?> = _me.asStateFlow()

    /** The picked folder's own name in Telegram. */
    val folderName: StateFlow<String?> = combine(folders.folders, folders.selectedFolderId) { all, id ->
        all?.find { it.id == id }?.name
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val checkInterval = settings.checkInterval.stateIn(viewModelScope, SharingStarted.Eagerly, CheckInterval.Minutes15)
    val notificationMode = settings.notificationMode.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationMode.Interval)

    /** Bumped to read the installed push apps again. */
    private val pushApps = MutableStateFlow(0)

    val pushLine: StateFlow<PushLine?> = combine(pushState.status, pushApps) { status, _ -> pushLine(status) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val compressByDefault = settings.compressByDefault.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val showMessageText = settings.showMessageText.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val cacheLimit = settings.cacheLimit.stateIn(viewModelScope, SharingStarted.Eagerly, CacheLimit.Gb2)

    /** The name on top as being edited; null until loaded. */
    var header by mutableStateOf<String?>(null)
        private set

    var usage by mutableStateOf<StorageUsage?>(null)
        private set

    var clearing by mutableStateOf(false)
        private set

    var sharingLogs by mutableStateOf(false)
        private set

    init {
        viewModelScope.launchLogged(TAG) {
            val user = td.send(TdApi.GetMe())
            _me.value = Account(
                id = user.id,
                name = user.fullName(),
                phone = user.phoneNumber.takeIf { it.isNotEmpty() }?.let { "+$it" },
                photoFileId = user.profilePhoto?.small?.id,
                minithumbnail = user.profilePhoto?.minithumbnail?.data,
            )
        }
        viewModelScope.launch { header = folders.displayName.first() }
        loadUsage()
    }

    fun editHeader(name: String) {
        val limited = name.limitTo(MAX_HEADER_NAME)
        header = limited
        viewModelScope.launch { folders.setHeaderName(limited) }
    }

    fun resetHeader() {
        header = folderName.value.orEmpty()
        viewModelScope.launch { folders.setHeaderName(null) }
    }

    fun setNotificationMode(mode: NotificationMode) {
        viewModelScope.launch { settings.setNotificationMode(mode) }
    }

    /** Reads the push apps again, and asks the push app again: it may just have been installed or set up. */
    fun refreshPush() {
        pushApps.value++
        if (notificationMode.value == NotificationMode.Push) push.register()
    }

    fun pickPushApp(activity: Activity) = push.pick(activity)

    fun retryPush() {
        push.register()
    }

    private fun pushLine(status: PushStatus): PushLine {
        val apps = push.distributors()
        if (apps.isEmpty()) return PushLine.GetApp
        val app = push.savedDistributor()?.let(::appName) ?: return if (apps.size == 1) PushLine.Connecting(appName(apps.single())) else PushLine.Pick
        return when {
            status.failed != null -> PushLine.Failed(app)
            !status.hasEndpoint || status.registeredAt == null -> PushLine.Connecting(app)
            else -> PushLine.Working(app, status.lastPushAt)
        }
    }

    private fun appName(packageName: String): String = try {
        context.packageManager.getApplicationInfo(packageName, 0).loadLabel(context.packageManager).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    fun setCheckInterval(interval: CheckInterval) {
        viewModelScope.launch { settings.setCheckInterval(interval) }
    }

    fun setShowMessageText(show: Boolean) {
        viewModelScope.launch { settings.setShowMessageText(show) }
    }

    fun setCompressByDefault(compress: Boolean) {
        viewModelScope.launch { settings.setCompressByDefault(compress) }
    }

    fun setCacheLimit(limit: CacheLimit) {
        viewModelScope.launch {
            settings.setCacheLimit(limit)
            quietly { storage.applyLimit() }
            loadUsage()
        }
    }

    fun clearMedia() {
        clearing = true
        viewModelScope.launch {
            quietly { storage.clear() }
            clearing = false
            loadUsage()
        }
    }

    /** Zips the logs, then hands the file to [share]. */
    fun shareLogs(withTdlib: Boolean, share: (File) -> Unit) {
        if (sharingLogs) return
        sharingLogs = true
        viewModelScope.launchLogged(TAG) {
            try {
                share(LogArchive.create(context, withTdlib))
            } finally {
                sharingLogs = false
            }
        }
    }

    fun logOut() {
        viewModelScope.launch { quietly { auth.logOut() } }
    }

    private fun loadUsage() {
        viewModelScope.launch { quietly { usage = storage.usage() } }
    }

    /** TDLib errors here aren't worth more than a log line. */
    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: TdException) {
            AppLog.w(TAG, "Settings action failed", e)
        }
    }
}

private const val TAG = "SettingsViewModel"
