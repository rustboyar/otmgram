package dev.otmgram.ui

import kotlinx.serialization.Serializable

// The app's screens and their arguments, for navigation. As classes, a missing or renamed
// argument is a compile error rather than a crash; view models read theirs with toRoute().

@Serializable
data object ChatsRoute

@Serializable
data object SettingsRoute

@Serializable
data object FolderRoute

@Serializable
data class ChatRoute(
    val chatId: Long,
    /** A message to open the chat at, e.g. one found; 0 for none. */
    val messageId: Long = 0,
)

@Serializable
data class MediaRoute(val chatId: Long, val messageId: Long)

@Serializable
data class MapRoute(val chatId: Long, val messageId: Long)

@Serializable
data class VideoNoteRoute(val chatId: Long)

/** A person's profile ([userId]) or a group's ([chatId]): one of the two is set. */
@Serializable
data class ProfileRoute(val userId: Long = 0, val chatId: Long = 0)

/** A YouTube, Instagram or TikTok link, played in the app. */
@Serializable
data class EmbedRoute(val url: String)
