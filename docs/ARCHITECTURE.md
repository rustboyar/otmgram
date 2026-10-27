# How Otmgram works

What the app does in detail, how the code is laid out, and why some things are the way
they are. For building and testing, see [DEVELOPMENT.md](DEVELOPMENT.md).

## Stack

- Kotlin and Jetpack Compose, minSdk 29 (Android 10); arm64-v8a (debug builds add x86_64 for the emulator)
- TDLib, built from source at a pinned version (`scripts/build-tdlib.sh`)
- Coroutines and Flow, Hilt, Navigation Compose with type-safe routes, DataStore, WorkManager
- Coil (images), Media3 ExoPlayer and Transformer (video and audio, cropping round videos), CameraX (recording round videos), lottie-android (animated stickers), MapLibre (maps)
- `api_id` and `api_hash` come from `local.properties` into `BuildConfig`, never into git

## Structure

- `core` (TDLib client, media, location, logging), `data` (repositories, and how messages and senders read: `MessageSummary`, `People`), `sync` (notifications, the background connection and checks), `ui` (screens and their view models). Dependencies point from `ui` to `sync`/`data` to `core`, never back; `SessionCleanup`, which ties logout across them, sits next to `OtmgramApp`
- Everything takes `TelegramClient` (requests, updates, the states the app watches), not `TdClient`, which only app start uses. Collectors on the main thread take `TelegramClient.updates(keep)`, filtered on TDLib's thread
- Background work runs in the app's scope, whose handler logs what nothing else caught instead of crashing; long-lived loops (notifications, the scheduler) catch everything but cancellation
- Navigation by type-safe routes (`ui/Routes.kt`, kotlinx.serialization); view models read their arguments with `toRoute()`. Screens above the chat list go through `screen<T>()` in `AppRoot`, which makes the device's back act as the screen's own
- Composables take app types only (`FileState`, `Sticker`, `Account`, thumbnails as JPEG bytes); view models are where TDLib types turn into those
- TDLib's message objects are mutable and can't be copied; the open chat changes them in place and `ChatHistory.version` marks each change, before they become immutable `MessageItem`s
- The chat screen's state is split into holders that take what they need as functions, each tested: `DraftComposer` (input bar, drafts), `ChatLocation`, `PinnedMessages`, `ChatSearch`, `ChatSelection`, `StickerPanel`, `OutgoingQueue`, wired together by `ChatViewModel`; its types are in `ChatModels.kt`, `MessageItem` marked `@Immutable` so unchanged bubbles skip recomposition
- How times, dates, durations and sizes read: `ui/format/Formats.kt`, with "today" and the time zone as parameters
- Texts are resources, one file per area (`res/values/strings_chat.xml`, `strings_settings.xml`, …; shared words in `strings.xml`). Composables use `stringResource`; other code gets `Strings` injected, and unit tests use `TestStrings`, which reads the same files. Units (KB, MB) and date patterns stay in code

## Scope

- Chat types: private chats and groups. No channels, no forum topics (forum groups show as one history)
- Links, @mentions and "forwarded from" headers don't lead to other chats: web links open in the browser, everything else is plain text
- Not planned: calls, stories, secret chats, several accounts, premium custom emoji (they show as plain emoji)

## Telegram connection

- `TdClient` is the one TDLib instance. It sends TDLib its parameters, and makes a fresh instance after logout closes the old one
- Online status: the account is online while the app is on screen and offline otherwise (TDLib's `online` option, following the app's lifecycle), as in Telegram's apps and as Telegram's API terms require. Background checks and the background connection leave it offline
- TDLib is told about network changes, so it reconnects at once rather than on a timeout, and again after the app was away for a while
- Otherwise TDLib disconnects 5 s after the app leaves the screen and after each background check (`TelegramClient.disconnectInBackground`), unless a live location is being shared or Instant notifications keep it connected; Android would freeze the process with its connections open

## Login and the folder

- TDLib's login steps: phone number, code, two-step verification password. Registration, QR login and the other steps aren't supported: the screen says to use the official app
- Logout: `logOut`, then TDLib's database, the chosen folder and the media cache go (see Security)
- After login, the folder is picked from the account's folders and stored in DataStore; Settings → Folder → Change picks another
- The chat list: `getChats(chatListFolder(id))`, reloaded shortly after any update that affects it. Search filters the folder's chats by title, on the phone
- A long press pins a chat to the top of the folder or unpins it (`toggleChatIsPinned` on the folder's list, so Telegram's apps show it too); pinned chats without unread messages show a pushpin. It also mutes a chat for good or unmutes it (`setChatNotificationSettings`, overriding the default for its kind, so it applies in all the user's apps); not offered for Saved Messages

## Chats

- Paged history; reply, forward (to the folder's chats only), edit (text, or the caption of photos, videos, GIFs and voice messages), delete (for me or for everyone), reactions, copy, select several
- A chat opens where it was left, while the app runs (`ChatPositions`: the message at the bottom of the screen and its offset; loaded with the newest pages if it's within 4, else around it, as a jump). Left at the newest, or for the first time, it opens at the first unread message, else at the newest
- Messages are marked as read as they show on screen (`viewMessages`). Groups show the sender's name above the first of their messages in a row and their picture beside the last (a row ends at a day's line)
- Find in chat (`searchChatMessages`), stepping through highlighted matches
- Jumping to a message that isn't loaded (a search result, a pinned or replied-to one) loads the messages around it in its place, in one request, as Telegram does; scrolling loads more either way, and ↓ goes back to the newest. Meanwhile new messages aren't added (they'd leave a gap); sending something brings the newest back. The message jumped to is highlighted for 2.5 s once on screen
- Pinned messages: the bar under the title shows the newest; a tap goes to it and moves the bar on to the next older one ("Pinned message 2 of 3"), and with several, a button lists them all. Pin and unpin from the message menu where `canBePinned` allows, for everyone, without notifying
- Scheduled messages: a long press on send offers in an hour, this evening (20:00, while over an hour away), tomorrow (09:00) or a picked date and time (`MessageSchedulingStateSendAtDate`); text only. TDLib sends scheduled messages as updates too: `ChatSession` keeps them out of the history, in their own list (`getChatScheduledMessages`, asked for again after updates about them). A bar above the input lists them: send now or change the time (`editMessageSchedulingState`), edit in the input bar, delete
- Profiles (`ui/profile`, `ProfileRoute` with a user or a chat id), from a chat's title or a group sender's picture: a person's picture (their photos paged through, `getUserProfilePhotos`), name, status, phone, username, bio, birthday (`getUserFullInfo`); a group's picture, title, members count, description and members (`getBasicGroupFullInfo`, or `getSupergroupMembers`, recent, up to 200; owner and admins first, with their titles). Mute or unmute; "Open chat" only for a chat in the folder. Details copy on a tap
- Drafts are saved to Telegram on leaving the chat, so other apps see them, and come back from there

## Media

- Albums (`mediaAlbumId`) show in one bubble: photos and videos in rows of up to three, each row as tall as its pictures' shapes allow, the caption below. The menu, reactions and time belong to the captioned message (or the first); forwarding, selecting and deleting take the whole album. An album with several captions, or one that failed to send, shows as separate messages
- Photos full screen with zoom; swiping through an album
- Videos: thumbnail in the bubble, played full screen with ExoPlayer once downloaded (no streaming); hardware decoders first, software ones when those fail (as the emulator's do)
- Voice messages: waveform with progress, one shared player (no seeking)
- Round videos: play muted in place, clipped to a circle; a tap turns the sound on and grows it (190 → 280 dp), scrolled into view
- GIFs loop muted in place
- Stickers: WEBP, TGS (Lottie, merge paths on); WEBM shows its still thumbnail (Android's decoders don't support its transparency)
- Downloads: photos, round videos, stickers, avatars and video thumbnails once their message is on screen; videos, voice messages and GIFs on tap (voice then plays, a GIF then loops; a tap stops the download); files on tap. A size limit in Settings (`optimizeStorage`, at start and after each check)
- Received files: name, size, progress; open in another app (FileProvider); "Save to Downloads" in the message menu; audio files show as files
- Links to YouTube (videos, Shorts, youtu.be), Instagram (posts, reels) and TikTok (videos, short links) get a card under the text, with the title and picture of Telegram's preview when there is one. A tap plays it full screen in the site's own embed player in a WebView: youtube-nocookie, instagram.com/p/…/embed, TikTok's player/v1 (short links followed first). No login. Nothing reaches the site before the tap; its cookies and storage are dropped on leaving; navigating away from the player opens the site's app or the browser, as does "Open in app". YouTube plays only inside a page of the app's (the embedder it requires). Rotating keeps it playing (the activity handles rotation itself)

## Sending media

- Photos and videos through the system photo picker, copied for TDLib, previewed with a caption and the estimated size; several go as an album (caption on the first). Videos go with their first frame as the thumbnail, as Telegram's apps send them
- Quality, chosen per send (default in Settings, HD unless changed): SD scales photos to 1280 px on the long side as JPEG and re-encodes videos to 480p H.264 (short side), about 1.2 Mbps; HD to 2560 px and 720p, about 2.5 Mbps; Original sends them as they are. AAC audio; frames stored upright, without a rotation flag. Media already that small stays as it is (Telegram recompresses photos to at most 2560 px, but never videos)
- Files: any type, several at once, with a caption; sent as they are
- Uploads show progress and can be cancelled. Copies of sent files are removed after 3 days
- Voice: recorded as OGG/Opus (MediaRecorder) with its level sampled for the waveform; under 0.7 s is dropped
- Round videos: their own screen, front camera (mirrored) or back with CameraX, switchable while recording (one `VideoCapture` for the screen and a persistent recording, rebound to the other camera), cropped to 480×480 by Media3 Transformer, at most 60 s
- Sticker picker: recent stickers and installed sets

## Location

- Maps: MapLibre with OpenFreeMap vector tiles (no API key, no Google services). Bubbles show static snapshots; the full map is interactive and shows the distance to someone else's location
- Privacy: previews are city-level (zoom 10, tiles of about 25 km), so the tile server learns only the rough area; the pin is drawn on the phone; detailed tiles load only when the full map is opened
- Received locations, static or live: live ones move with each update and say when they were last updated
- Sending the current position: Android's `LocationManager` (no Play services), fine location permission
- Sharing live for 15 min, 1 h or 8 h (`inputMessageLocation` with `live_period`):
  - a foreground service of type `location`, with a notification and a stop action; started while the app is open, so "while using the app" permission is enough (no background location)
  - `editMessageLiveLocation` when the position changes noticeably, at most every 30 s or so; stops at the end, on "Stop", or on logout
  - the end is an alarm (fires while the phone sleeps); logout ends it in the chat first (TDLib can't once logged out); a message Telegram hasn't confirmed when sharing fails, stops or is replaced is withdrawn, so it isn't sent later
  - a share whose process was killed is ended at the next start, from TDLib's list of the app's active live locations
- Not done: proximity alerts, heading, resuming after the service is killed

## Notifications

- TDLib's notification API decides what to notify (`sync/NotificationCenter`): it applies mute settings and mentions in muted groups, waits a moment so a message read on another device never alerts, and removes notifications when messages are read anywhere or deleted. `MessagesNotification` shows them, behind `NotificationDisplay`
- Shown: only the folder's chats; nothing while the app is open (opening it clears them); nothing that was there when the app was last open (message ids per chat, recorded on start and stop)
- TDLib keeps at most 25 groups, counting chats outside the folder, which this app never reads: a folder chat's notification pushed out that way stays until it's read
- One notification per chat, bundled when there are several: the sender and text of each message, or with text off only the chat and "N new messages". Each new message alerts once (the newest alerted notification id is stored, as TDLib's ids persist); edits and removals are quiet. A tap opens the chat. No reply or mark-as-read actions, on purpose
- Message text is off by default, so nothing readable reaches the lock screen whatever Android's settings. With it on, the lock screen gets a version without text or names; a chat whose previews are off in Telegram never shows text
- Delivery: notifications come while the process runs. In the background Android soon blocks the app's network, so a check (`CheckWorker`, needs network) reconnects TDLib and waits for its pending notifications
- Three modes in Settings: Instant, Periodic (the default), Off
- Instant (`sync/ConnectionService`): TDLib stays connected in the background, in a foreground service (type "special use") with a silent, minimized notification, so messages come in as they're sent. A backup check runs every hour. The service starts with the app, after the phone starts and after an update; Android allows starting it only then, so if it's stopped otherwise it's back the next time the app opens
- While the phone sleeps nothing runs, and Telegram closes a connection that hasn't pinged for about 2 minutes, so an alarm wakes the phone every minute (inexact, allowed while idle) to send one tiny request, which TDLib sends with its ping; a closed connection is reopened then. In Doze Android lets it run less often, about every 2 minutes, so a message can be that late
- Settings ask to turn off battery optimization for the app while it's on (Android's own dialog), since it can close the connection
- Earlier versions used Telegram's push through UnifiedPush (ntfy). Telegram skipped pushes for minutes after a client was connected or online, so messages came up to an hour late (the backup check); the app now tells Telegram to stop pushing once, on update
- Periodic: every 15 min, 30 min, 1 h or 3 h. Android's Doze may delay checks while the phone is idle

## Design

- "Misty sunrise": warm paper with a faint grain, a sky band with slate ridges over the chat list, apricot bubbles for your messages, slate as the one accent, soft unread pills. Fraunces only for the folder name, Inter for everything else (both OFL, bundled; Inter covers Latin and Cyrillic, Fraunces only Latin)
- Light only, no colour or font settings
- Built by hand in Compose: `ui/theme` holds the palette, type, line icons, and components (sky band, buttons, pills, fields, generated pattern avatars for chats without a photo, sheets, dialogs)
- Things to tap that are drawn smaller than 48 dp keep 48 dp of room around them

## Security

- TDLib's database and login are encrypted with a random 32-byte key (without one, TDLib uses a built-in key, i.e. none). The key is stored encrypted by an Android Keystore key, which never leaves the device (`core/td/DatabaseKey`)
- Older unencrypted databases are encrypted once logged in. Opening (`core/td/DatabaseOpener`): if the database doesn't take its key (TDLib's error 401; stopped mid-switch), it opens without, and only then is the key file dropped and a new key applied; if it's encrypted and the key can never be read again, the local database is deleted and the app asks to log in again. Anything else (a busy Keystore, a full disk) is retried with backoff, never treated as a lost key
- Downloaded and sent media are plain files (TDLib encrypts only its database)
- After logout, from this phone or elsewhere (`SessionCleanup`): TDLib deletes its database and downloads; the app removes sent copies, shared log archives, TDLib's log (emptied, as the next instance already writes to it), map tiles, its notification marks and shown notifications. Its own log stays (no message text, names, or file paths: exception messages are redacted), as do device settings
- A chat opens from the launch intent only if it's in the selected folder: the launcher activity is exported, and opening a chat marks what it shows as read
- Session backups (`scripts/session.sh`) include the key file, so encrypted ones only restore into the same installation
- Releases are signed with one key, kept outside git; Android installs updates only with the same key

## Logging

- No remote crash reporting (it would send data to a third party); logs stay on the phone
- `AppLog`: logcat plus `files/logs/app.log` (512 KB, rotated once): app start with version and device, login and connection changes, each background check and its outcome, notifications, live location, every caught failure with its stack trace, and crashes
- TDLib's warnings and errors go to `files/logs/tdlib.log` (2 MB). TDLib prints whole messages and users in some of them, so this log can contain message text, names and phone numbers
- Settings → Share logs: a zip with the app's log and the device info, through the share sheet; TDLib's log only if chosen in the dialog, which says what it may contain
- Never logged: message text, names, phone numbers, file names (exceptions that carry a path aren't logged with it)

## Ideas, not built

- Search across all the folder's chats at once
- Forwarding from the media viewer, a "my location" button on the map
- Streaming videos, seeking in voice messages
