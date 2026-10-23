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
- Inline videos use software decoders first (few hardware decoder instances; the emulator's fails)
- Stickers: WEBP, TGS (Lottie, merge paths enabled); WEBM shows its still thumbnail (alpha video unsupported by Android decoders)
- Chat photos as avatars in the chat list
- Auto-download everything, with progress; cache limit is M10 (`optimizeStorage`)

### M5 — Media sending
- Photos/videos via the system photo picker, copied to the cache for TDLib; preview with a caption before sending; several picks go as an album (caption on the first item)
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
- `PeriodicWorkRequest` every 15 minutes (WorkManager minimum), requires network; scheduled while logged in with a folder picked, cancelled on logout
- The worker uses the in-process TdClient (started with the process), waits for TDLib to be connected and caught up; the account stays offline
- New = unread incoming messages in unmuted folder chats that arrived since the app was last open; checks while the app is open just record that
- One notification: a single message shows chat and message; several are grouped ("4 new messages") with the messages of one chat, or a line per chat for several chats; updated on each check, alerting only when something is newer than the last notification, removed when nothing is unread anymore
- Tap opens the chat list (fresh task); opening the app clears it
- Debug builds: `adb shell am broadcast -n dev.otmgram/.debug.RunCheckReceiver [--ez renotify true]` runs the check now
- Android Doze may delay checks beyond 15 min while the phone is idle — acceptable for this app
- Interval setting: M10

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
- Settings (done): name on top of the chat list (display only, empty allowed, back to the folder's name; forgotten on logout), how often to check (15 min / 30 min / 1 h / 3 h / never, as the periodic work's interval), Compress on by default or not, storage usage by kind of file, cache limit (500 MB / 1 GB / 2 GB / none; `optimizeStorage` by size at start and after each check), clear downloaded media
- From the design, not built yet: estimated size after compression in the send preview, swiping through an album in the viewer and forwarding from it, distance and "my location" on the map, a map preview in the location sheet, playback ring on video circles, upload progress with cancel on photos being sent
- Error states beyond offline

## Open questions

- Search across all folder chats' messages at once (fan-out `searchChatMessages`) — decide later
