# Otmgram — plan

A minimal Android Telegram client that only shows chats from one chat folder, to reduce time spent online.

## Scope

- Login / logout
- Chat list = chats of one folder, picked once after login; changing it requires logout
- Search only within the folder's chats (no global search, no contacts, no "new chat")
- Chat types: private 1:1 chats and groups (no channels, no forum topics — forum groups are shown as a flat history)
- Messages: send/receive text, reply, forward (targets limited to folder chats), edit, delete, reactions
- Media, view + send: photos, videos, voice messages, video circles, stickers, files
- Sending compresses photos and videos by default (toggle per send; files keep the original)
- Location: view received (static and live) locations, send current location, share live location
- Media auto-download: photos, stickers, circles, avatars and thumbnails when shown; videos, voice messages, GIFs and files on tap (bounded by a cache size limit)
- Notifications for folder chats only, via a periodic background check (no push)
- Always appear offline: the `online` option is never set to true
- Personal sideload APK, own `api_id` / `api_hash`

Out of scope for v1: channels, calls, stories, secret chats, multiple accounts, premium custom emoji (plain emoji fallback).

Links, @mentions and "forwarded from" headers do not navigate to chats: web links open in the browser, everything else is plain text.

## Stack

- Kotlin + Jetpack Compose, minSdk 29; arm64-v8a (debug builds add x86_64 for the emulator)
- TDLib built from source (pinned version)
- Coroutines/Flow, Hilt, Navigation Compose, DataStore
- Coil (images), Media3 ExoPlayer + Transformer (video/audio, circle cropping), CameraX (circle recording), lottie-android (TGS stickers)
- `api_id` / `api_hash` in `local.properties` → `BuildConfig`, never committed

## Milestones

### M0 — Foundation
- Gradle project skeleton
- Scripted TDLib Android build (arm64)
- TdClient wrapper

### M1 — Auth
- TDLib auth states: phone → code → 2FA password; no registration or QR login (other steps: use the official app)
- Logout: `logOut`, wipe TDLib database, stored folder, media cache

### M2 — Folder + chat list
- One-time folder picker after login, stored in DataStore
- `getChats(chatListFolder(id))`, reloaded shortly after any update that affects the list
- Search: filter the folder's chats by title, locally

### M3 — Messages
- Paged history, send text
- Reply, forward (folder chats only), edit (text, or the caption of photos, videos, GIFs and voice messages), delete (for me / for everyone)
- Reactions: display + add
- Mark as read on screen (`viewMessages`)
- Groups: sender names + avatars
- In-chat message search (`searchChatMessages`)

### M4 — Media viewing
- Photos: fullscreen + zoom
- Videos: thumbnail in the bubble, fullscreen ExoPlayer once downloaded (no streaming yet); hardware decoders with software fallback
- Voice: waveform with playback progress, one shared player (no seeking yet)
- Circles: inline muted autoplay clipped to a circle, tap for sound
- GIFs: inline muted loop
- Inline videos use hardware decoders first and fall back to software ones when one fails (as the emulator's does)
- Stickers: WEBP, TGS (Lottie, merge paths enabled); WEBM shows its still thumbnail (alpha video unsupported by Android decoders)
- Chat photos as avatars in the chat list
- Downloads: photos, video circles, stickers, avatars and video thumbnails automatically once their message is on screen; videos, voice messages and GIFs on tap (voice then plays, a GIF then loops in place; tap again to stop the download); files on tap. Cache limit in settings (`optimizeStorage`)

### M5 — Media sending
- Photos/videos via the system photo picker, copied to the cache for TDLib; preview with a caption before sending; several picks go as an album (caption on the first item)
- Voice: tap 🎤 to record OGG/Opus (MediaRecorder), level sampled for the waveform, cancel or send; < 0.7 s discarded
- Circles: separate recording screen, CameraX front camera (mirrored) → Media3 Transformer crops to 480×480, max 60 s; decoders fall back to software
- Sticker picker: recent stickers + installed sets

### M6 — Location
- Map: MapLibre with OpenFreeMap vector tiles (no API key, no Google services); bubbles use static snapshots, the full map is interactive
- View: map preview in the bubble (static or live, live ones move with each update and show "updated X min ago"); tap opens a full map
- Privacy: previews are city-level (zoom 10, tiles of ~25 km), so the tile server learns only the rough area; the pin is drawn on the phone; detailed tiles load only when the full map is opened
- Send current location: one-time pin from Android's `LocationManager` (no Play services), fine location permission
- Share live location for 15 min / 1 h / 8 h (`inputMessageLocation` with `live_period`):
  - foreground service of type `location` with a permanent notification and a stop action; started while the app is open, so "while using the app" location permission is enough (no background location); notification permission for the notification
  - sends `editMessageLiveLocation` when the position changes noticeably, at most every ~30 s; stops on expiry, on "stop sharing" or on logout
  - expiry is an alarm (fires while the phone sleeps); logout ends it in the chat first (TDLib can't once logged out); a message Telegram hasn't confirmed when sharing fails, stops or is replaced is withdrawn, so it isn't sent later
  - a share the process was killed during is ended at the next start, from TDLib's list of this app's active live locations
  - must not set the account online (see "always offline")
- Not done: proximity alerts and heading. If the service is killed, sharing is ended in the chat (no resume)

### M7 — Notifications (periodic check)
- `PeriodicWorkRequest` every 15 minutes (WorkManager minimum), requires network; scheduled while logged in with a folder picked, cancelled on logout
- The worker uses the in-process TdClient (started with the process), makes it reconnect (a frozen process still says "ready" from before) and waits for it to catch up; the account stays offline. The app also tells TDLib about network changes
- New = unread incoming messages in unmuted folder chats newer than what was in the folder when the app was last open. Kept per chat as message ids, not times: ids only grow within a chat, so late arrivals and the phone's clock don't matter. The first check after install only records what's there
- One notification: a single message shows chat and message; several are grouped ("4 new messages") with the messages of one chat, or a line per chat for several chats; updated on each check, alerting only when something is newer than the last notification, removed when nothing is unread anymore
- Tap opens the chat when it's about one, otherwise the chat list (a fresh task either way, back leads to the list); opening the app clears it
- Debug builds: `adb shell am broadcast -n dev.otmgram/.debug.RunCheckReceiver [--ez renotify true]` runs the check now
- Android Doze may delay checks beyond 15 min while the phone is idle — acceptable for this app
- Interval setting: M10
- Message text in notifications is off by default: they say only who wrote ("Anna · 2 new messages"), so nothing readable reaches the lock screen whatever Android's settings. With it on, the lock screen gets a text-free version when Android hides sensitive content

### M8 — Compression and files
- Photos: scaled to 2560 px on the long side, JPEG, orientation applied (Telegram's servers recompress photos anyway; this saves the upload)
- Videos: re-encoded to 720p H.264 (short side), ~2.5 Mbps, AAC; skipped if already that small; Telegram doesn't recompress videos
- "Compress" toggle in the send preview, on by default
- Files: 📎 → File, any type, several at once (album of files), caption; the original is sent
- Received files: bubble with name, size, progress; download only when tapped; open in another app (FileProvider); "Save to Downloads" in the message menu; audio files are shown as files

### M9 — Design
- Design artifact in Claude ("Otmgram design", page "Otmgram · final"): every screen, plus colour, type, shape tokens and the icon
- Direction "misty sunrise": warm paper with a faint grain, a sky band with slate ridges over the chat list, apricot bubbles for your messages, slate as the one accent, soft unread pills; Fraunces only for the folder name, Inter for everything else (both OFL, bundled, Latin + Cyrillic for Inter; Fraunces has no Cyrillic)
- Light only, no colour or font customization
- Built by hand in Compose: `ui/theme` (palette, type, line icons from the design's SVG paths, components: sky band, hero, buttons, pills, fields, generated pattern avatars for chats without a photo, sheets, dialogs), every screen restyled
- Came with the design: Settings screen (account, log out; ⋮ opens it), offline/connecting line, mute icon, folder chat counts, code boxes with resend timer, show-password, chat subtitle (members, last seen), "N new since you left", postmark read marks, failed messages with Retry/Delete, floating message menu with Copy text and Select, multi-select with Forward/Delete, find-in-chat stepping through highlighted matches, attach sheet (voice moved there; the round button records circles), location sheet, full-screen send preview, viewer with sender, time, caption and Save, circle camera with progress ring and camera switch, map card with "Open in another app", sunrise launcher icon

### M10 — Polish
- Settings (done): name on top of the chat list (display only, up to 20 characters, shrinks to fit one line, empty allowed, back to the folder's name; forgotten on logout), how often to check (15 min / 30 min / 1 h / 3 h / never, as the periodic work's interval), Compress on by default or not, storage usage by kind of file, cache limit (500 MB / 1 GB / 2 GB / none; `optimizeStorage` by size at start and after each check), clear downloaded media
- From the design (done): estimated size after compression in the send preview, swiping through an album in the viewer ("1 of 3"), distance on the map to someone else's location, a map of where you are in the location sheet, playback ring on video circles, upload progress with cancel on photos, videos and files being sent
- From the design, not built: forwarding from the viewer, a "my location" button on the map
- Error states beyond offline

## Testing

- Unit tests (`./gradlew :app:testDebugUnitTest`): message mapping, list rows, media mapping, wording, the mapping of login states and storage kinds, opening the database with its key, and against a fake TDLib (`testing/FakeTelegram`, behind the `TelegramClient` interface): the open chat, the folder list, file states and what gets notified. Settings and notification marks use DataStore files of the test's own (they're injected)
- UI tests on a device (`scripts/ui-tests.sh`): bubble states, delete dialog. Not `connectedAndroidTest`, which uninstalls the app and so logs it out
- Before committing: `scripts/check.sh` (lint, failing on any issue, and unit tests; `--release` also builds the release APK). No CI: building TDLib takes too long for it

## Structure

- `core` (TDLib client, media, location, logging), `data` (repositories), `sync` (background checks), `ui` (screens and their view models). Everything takes `TelegramClient` (requests, updates, the states the app watches), not `TdClient`, which only app start uses
- The notifier decides (`NewMessagesNotifier`); `MessagesNotification` shows it, behind `NotificationDisplay`
- Composables take app types only (`FileState`, `Sticker`, `Account`, thumbnails as JPEG bytes); view models are where TDLib types turn into those
- TDLib's message objects are mutable and can't be copied; the open chat changes them in place and `ChatHistory.version` marks each change, before they become immutable `MessageItem`s
- The chat screen's state is split: `ChatSearch`, `ChatSelection`, `StickerPanel`, `OutgoingQueue`, wired together by `ChatViewModel`
- Texts are resources, one file per area (`res/values/strings_chat.xml`, `strings_settings.xml`, …; shared words in `strings.xml`). Composables use `stringResource`; other code gets `Strings` injected, and unit tests use `TestStrings`, which reads the same files. Units (KB, MB) and date patterns stay in code

## Security

- TDLib's database and login are encrypted with a random 32-byte key (without one, TDLib uses a built-in key, i.e. none). The key is stored encrypted by an Android Keystore key, which never leaves the device (`core/td/DatabaseKey`)
- Existing databases are encrypted once logged in. Opening (`core/td/DatabaseOpener`): if the database doesn't take its key (TDLib's error 401; stopped mid-switch), it opens without, and only then is the key file dropped and a new key applied; if it's encrypted and the key can never be read again, the local database is deleted and the app asks to log in again. Anything else (a busy Keystore, a full disk) is retried with backoff, never treated as a lost key
- Downloaded and sent media are plain files (TDLib encrypts only its database). Copies of sent files (`cache/outgoing`) are removed after 3 days
- After logout, from this phone or elsewhere (`data/SessionCleanup`): TDLib deletes its database and downloads; the app removes sent copies, shared log archives, map tiles and its notification marks. Its own log stays (no message text or names), as do device settings
- Session backups (`scripts/session.sh`) include the key file, so encrypted ones only restore into the same installation
- Release builds are signed with a dedicated key (`~/.local/share/otmgram/release.jks`, password in `local.properties`, both outside git): back both up. Android installs updates only with the same key, so losing it means reinstalling and logging in again

## Logging

- No remote crash reporting (it would send data to a third party); logs stay on the phone
- `AppLog`: logcat plus `files/logs/app.log` (512 KB, rotated once): app start with version and device, authorization and connection changes, each background check and its outcome, notifications, live location, every caught failure with its stack trace, and crashes
- TDLib's warnings and errors to `files/logs/tdlib.log` (2 MB). TDLib prints whole messages and users in some of them, so this log can contain message text, names and phone numbers
- Settings → Share logs: a zip with the app's log and the device info, via the share sheet; TDLib's log only if chosen in the dialog, which says what it may contain
- Never log message text, names, phone numbers or file names (exceptions that carry a path aren't logged with it)

## Open questions

- Search across all folder chats' messages at once (fan-out `searchChatMessages`) — decide later
