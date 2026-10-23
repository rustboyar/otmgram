# Otmgram — plan

A minimal Android Telegram client that only shows chats from one chat folder, to reduce time spent online.

## Scope

- Login / logout
- Chat list = chats of one folder, picked once after login; changing it requires logout
- Search only within the folder's chats (no global search, no contacts, no "new chat")
- Chat types: private 1:1 chats and groups (no channels, no forum topics — forum groups are shown as a flat history)
- Messages: send/receive text, reply, forward (targets limited to folder chats), edit, delete, reactions
- Media, view + send: photos, videos, voice messages, video circles, stickers
- Location: view received (static and live) locations, send current location, share live location
- Media auto-download: always (bounded by a cache size limit)
- Notifications for folder chats only, via a periodic background check (no push)
- Always appear offline: the `online` option is never set to true
- Personal sideload APK, own `api_id` / `api_hash`

Out of scope for v1: channels, calls, stories, secret chats, multiple accounts, premium custom emoji (plain emoji fallback).

Links, @mentions and "forwarded from" headers do not navigate to chats: web links open in the browser, everything else is plain text.

## Stack

- Kotlin + Jetpack Compose, minSdk 29, arm64-v8a only
- TDLib built from source (pinned version)
- Coroutines/Flow, Hilt, Navigation Compose, DataStore
- Coil (images), Media3 ExoPlayer + Transformer (video/audio, circle cropping), CameraX (circle recording), lottie-android (TGS stickers)
- `api_id` / `api_hash` in `local.properties` → `BuildConfig`, never committed

## Structure

```
app/
  core/td     TdClient: TDLib JNI wrapper → suspend requests + update Flows
  data/       repositories: Auth, Folder, Chats, Messages, Files, Stickers
  ui/         auth, folder picker, chat list, chat, media viewer, settings
  sync/       periodic WorkManager check + notifications
```

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
- Reply, forward (folder chats only), edit, delete (for me / for everyone)
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
- Inline videos use software decoders first (few hardware decoder instances; the emulator's fails)
- Stickers: WEBP, TGS (Lottie, merge paths enabled); WEBM shows its still thumbnail (alpha video unsupported by Android decoders)
- Chat photos as avatars in the chat list
- Auto-download everything, with progress; cache limit is M8 (`optimizeStorage`)

### M5 — Media sending
- Photos/videos via the system photo picker, copied to the cache for TDLib; several picks go as an album (no captions yet)
- Voice: tap 🎤 to record OGG/Opus (MediaRecorder), level sampled for the waveform, cancel or send; < 0.7 s discarded
- Circles: separate recording screen, CameraX front camera (mirrored) → Media3 Transformer crops to 480×480, max 60 s; decoders fall back to software
- Sticker picker: recent stickers + installed sets

### M6 — Location
- Map: MapLibre with OpenFreeMap vector tiles (no API key, no Google services); bubbles use static snapshots, the full map is interactive
- View: map preview in the bubble (static or live, live ones move with each update and show "updated X min ago"); tap opens a full map
- Send current location: one-time pin from Android's `LocationManager` (no Play services), fine location permission
- Share live location for 15 min / 1 h / 8 h (`inputMessageLocation` with `live_period`):
  - foreground service of type `location` with a permanent notification and a stop action; started while the app is open, so "while using the app" location permission is enough (no background location); notification permission for the notification
  - sends `editMessageLiveLocation` when the position changes noticeably, at most every ~30 s; stops on expiry, on "stop sharing" or on logout
  - must not set the account online (see "always offline")
- Not done: proximity alerts and heading. If the service is killed, sharing is ended in the chat (no resume)

### M7 — Notifications (periodic check)
- `PeriodicWorkRequest` every X minutes (setting, minimum 15 — WorkManager limit), requires network
- Worker uses the shared in-process TdClient (TDLib can't open the same database twice), keeps `online` option false so the check doesn't show you as online
- Waits for TDLib to finish syncing, then for each folder chat compares the last message with the last one already notified (stored per chat) and notifies about new incoming ones
- One notification per chat (MessagingStyle), tap opens the chat; skip muted chats and the chat currently open
- Android Doze may delay checks beyond X while the phone is idle — acceptable for this app

### M8 — Polish
- Settings: storage usage, cache limit, logout
- Error and offline states

## Open questions

- Search across all folder chats' messages at once (fan-out `searchChatMessages`) — decide later
