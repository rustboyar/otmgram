package dev.otmgram.ui.profile

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.otmgram.R
import dev.otmgram.core.Strings
import dev.otmgram.core.launchLogged
import dev.otmgram.core.td.TdException
import dev.otmgram.core.td.TelegramClient
import dev.otmgram.core.userMessage
import dev.otmgram.data.ChatRepository
import dev.otmgram.data.FolderRepository
import dev.otmgram.data.fullName
import dev.otmgram.ui.ProfileRoute
import dev.otmgram.ui.format.lastSeen
import org.drinkless.tdlib.TdApi
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** What the profile shows on top: a person's or a group's. */
@Immutable
data class Profile(
    /** The person's user id, or the group's chat id: for the generated picture. */
    val id: Long,
    val isGroup: Boolean,
    val name: String,
    /** "online", "last seen …", "bot"; for a group, "12 members". */
    val subtitle: String?,
    val photoFileId: Int?,
    val minithumbnail: ByteArray?,
    val details: List<ProfileDetail>,
    /** The chat with them, or the group; null if there's none to mute or open. */
    val chatId: Long?,
    val isMuted: Boolean,
    /** Their chat is in the folder, so it can be opened. */
    val canOpenChat: Boolean,
)

/** "Phone", "+49 151 …"; tapped, copied. */
data class ProfileDetail(@StringRes val label: Int, val value: String)

/** One of a group's members. */
@Immutable
data class Member(
    val userId: Long,
    val name: String,
    val status: String?,
    /** "owner", "admin", or their title in the group; null for others. */
    val role: String?,
    val photoFileId: Int?,
    val minithumbnail: ByteArray?,
)

/** One of the profile's photos, for the viewer. */
@Immutable
data class ProfilePhoto(val id: Long, val fileId: Int, val minithumbnail: ByteArray?)

/** A person's or a group's profile: who they are, and for a group, who's in it. */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val td: TelegramClient,
    private val chats: ChatRepository,
    private val folders: FolderRepository,
    private val strings: Strings,
) : ViewModel() {

    private val route = savedState.toRoute<ProfileRoute>()

    var profile by mutableStateOf<Profile?>(null)
        private set

    var members by mutableStateOf<List<Member>>(emptyList())
        private set

    /** The photos being paged through, or null. */
    var photos by mutableStateOf<List<ProfilePhoto>?>(null)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launchLogged(TAG, onError = { notice = it.userMessage(strings) }) {
            if (route.userId != 0L) loadUser(route.userId) else loadGroup(route.chatId)
        }
    }

    private suspend fun loadUser(userId: Long) {
        val user = td.send(TdApi.GetUser(userId))
        val full = try {
            td.send(TdApi.GetUserFullInfo(userId))
        } catch (e: TdException) {
            null
        }
        // The private chat: known to TDLib once there's been one, and made locally otherwise.
        val chat = try {
            td.send(TdApi.CreatePrivateChat(userId, false))
        } catch (e: TdException) {
            null
        }
        val isBot = user.type is TdApi.UserTypeBot
        profile = Profile(
            id = user.id,
            isGroup = false,
            name = user.fullName().ifEmpty { strings.get(R.string.deleted_account) },
            subtitle = if (isBot) strings.get(R.string.status_bot) else lastSeen(user.status, strings),
            photoFileId = user.profilePhoto?.big?.id,
            minithumbnail = user.profilePhoto?.minithumbnail?.data,
            details = listOfNotNull(
                user.phoneNumber.takeIf { it.isNotEmpty() }?.let { ProfileDetail(R.string.profile_phone, "+$it") },
                user.usernames?.activeUsernames?.firstOrNull()?.let { ProfileDetail(R.string.profile_username, "@$it") },
                full?.bio?.text?.takeIf { it.isNotBlank() }?.let { ProfileDetail(R.string.profile_bio, it) },
                full?.birthdate?.let { ProfileDetail(R.string.profile_birthday, birthday(it)) },
            ),
            chatId = chat?.id,
            isMuted = chat?.let { chats.isMuted(it.id) } ?: false,
            canOpenChat = chat != null && folders.isInFolder(chat.id),
        )
    }

    private suspend fun loadGroup(chatId: Long) {
        val chat = td.send(TdApi.GetChat(chatId))
        val (description, count, memberList) = when (val type = chat.type) {
            is TdApi.ChatTypeBasicGroup -> {
                val full = td.send(TdApi.GetBasicGroupFullInfo(type.basicGroupId))
                Triple(full.description, full.members.size, full.members.toList())
            }
            is TdApi.ChatTypeSupergroup -> {
                val full = td.send(TdApi.GetSupergroupFullInfo(type.supergroupId))
                val found = try {
                    td.send(TdApi.GetSupergroupMembers(type.supergroupId, TdApi.SupergroupMembersFilterRecent(), 0, MAX_MEMBERS)).members.toList()
                } catch (e: TdException) {
                    emptyList() // Hidden by the group's admins.
                }
                Triple(full.description, full.memberCount, found)
            }
            else -> Triple("", 0, emptyList())
        }
        profile = Profile(
            id = chat.id,
            isGroup = true,
            name = chat.title,
            subtitle = count.takeIf { it > 0 }?.let { strings.plural(R.plurals.members, it, it) },
            photoFileId = chat.photo?.big?.id,
            minithumbnail = chat.photo?.minithumbnail?.data,
            details = listOfNotNull(description.takeIf { it.isNotBlank() }?.let { ProfileDetail(R.string.profile_description, it) }),
            chatId = chat.id,
            isMuted = chats.isMuted(chat.id),
            canOpenChat = false,
        )
        members = memberList.mapNotNull { member(it) }.sortedBy { if (it.role == null) 1 else 0 }
    }

    private suspend fun member(member: TdApi.ChatMember): Member? {
        val userId = (member.memberId as? TdApi.MessageSenderUser)?.userId ?: return null
        val user = try {
            td.send(TdApi.GetUser(userId))
        } catch (e: TdException) {
            return null
        }
        // Their title in the group, if it gave them one.
        val title = member.tag.orEmpty()
        val role = when (member.status) {
            is TdApi.ChatMemberStatusCreator -> title.ifEmpty { strings.get(R.string.role_owner) }
            is TdApi.ChatMemberStatusAdministrator -> title.ifEmpty { strings.get(R.string.role_admin) }
            is TdApi.ChatMemberStatusLeft, is TdApi.ChatMemberStatusBanned -> return null
            else -> null
        }
        return Member(
            userId = userId,
            name = user.fullName().ifEmpty { strings.get(R.string.deleted_account) },
            status = if (user.type is TdApi.UserTypeBot) strings.get(R.string.status_bot) else lastSeen(user.status, strings),
            role = role,
            photoFileId = user.profilePhoto?.small?.id,
            minithumbnail = user.profilePhoto?.minithumbnail?.data,
        )
    }

    fun toggleMuted() {
        val shown = profile ?: return
        val chatId = shown.chatId ?: return
        viewModelScope.launchLogged(TAG, onError = { notice = it.userMessage(strings) }) {
            chats.setMuted(chatId, !shown.isMuted)
            profile = profile?.copy(isMuted = chats.isMuted(chatId))
        }
    }

    /** A person's photos, newest first, to page through; a group's current one. */
    fun openPhotos() {
        val shown = profile ?: return
        viewModelScope.launchLogged(TAG, onError = { notice = it.userMessage(strings) }) {
            val listed = if (shown.isGroup) {
                emptyList()
            } else {
                try {
                    td.send(TdApi.GetUserProfilePhotos(shown.id, 0, MAX_PHOTOS)).photos.toList()
                } catch (e: TdException) {
                    emptyList()
                }
            }.mapNotNull { photo ->
                photo.sizes.maxByOrNull { it.width }?.let { ProfilePhoto(photo.id, it.photo.id, photo.minithumbnail?.data) }
            }
            photos = listed.ifEmpty { listOfNotNull(shown.photoFileId?.let { ProfilePhoto(0, it, shown.minithumbnail) }) }.ifEmpty { null }
        }
    }

    fun closePhotos() {
        photos = null
    }

    fun showNotice(text: String) {
        notice = text
    }

    fun noticeShown() {
        notice = null
    }

    private fun birthday(date: TdApi.Birthdate): String {
        val locale = Locale.getDefault()
        return if (date.year > 0) {
            LocalDate.of(date.year, date.month, date.day).format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
        } else {
            MonthDay.of(date.month, date.day).format(DateTimeFormatter.ofPattern("d MMMM", locale))
        }
    }

    private companion object {
        const val TAG = "Profile"
        const val MAX_MEMBERS = 200
        const val MAX_PHOTOS = 100
    }
}
