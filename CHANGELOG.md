# Changelog

What changed in each release of Otmgram, newest first. Versions are
MAJOR.MINOR.PATCH; while the first number is 0, anything may still change.

## Unreleased

### Added
- Who reacted: up to three show by face on the reaction, and the message's menu lists everyone who reacted, with their reaction

### Changed
- A tap on a message opens its menu, and a long press selects it

## 0.4.1 - 2026-10-27

### Changed
- Search on the chat list finds messages in all the folder's chats, rather than chats by name; a result opens its chat at the message
- A reply to a photo, video, GIF or round video shows a small picture of it, and so does the bar while you write one

## 0.4.0 - 2026-10-27

### Added
- A chat opens where you left it, when you come back to it from the chat list
- In groups, each sender's picture shows beside their messages
- A round video grows while it plays with sound
- Switch between the front and back camera while recording a round video
- Scheduled messages: long-press send to send later (in an hour, this evening, tomorrow morning or a date and time you pick); a bar above the input lists them, to send now, change the time, edit or delete
- Profiles: tap a chat's title, or a sender's picture in a group, for their photo (tap it to page through all of them), status, phone, username, bio and birthday, mute or unmute, and their chat if it's in the folder; a group's shows its description and members
- Mute and unmute a chat with a long press, below Pin; as in Telegram, it applies to all your apps

### Fixed
- Replying in a group to someone's second or later message in a row said "Reply to" the group instead of them
- Group events say who: "Anna added Lea and Tom", "Anna left the group", rather than only "Joined the group"

## 0.3.0 - 2026-10-27

### Changed
- Instant notifications no longer need ntfy: the app stays connected to Telegram in the background, shown by a silent notification, so messages arrive as they're sent. Telegram skipped pushes for minutes after you used the app, so some messages came up to an hour late. Settings ask to let the app run in the background; ntfy's Otmgram topics can be removed there

## 0.2.0 - 2026-10-26

### Added
- Albums show as one bubble, their photos and videos side by side; forwarding, selecting or deleting one takes the whole album

### Changed
- Send photos and videos in SD (smallest), HD (as before) or Original, chosen for each message; the default is in Settings
- Screens change faster: a chat or settings page slides in from the right and back slides it away, and photos, videos and players open over the chat

### Fixed
- Videos sent as they are no longer show blank until opened
- Portrait videos no longer show sideways in Telegram for iPhone

## 0.1.0 - 2026-10-26

The first release. Otmgram shows the chats of one Telegram folder, and nothing else.

### Chats
- Log in with your phone number, the code and your two-step verification password
- Pick the folder after logging in, and change it in Settings
- The folder's private chats and groups, with search by name; pin and unpin chats with a long press
- You show as online while Otmgram is open, as in Telegram's apps

### Messages
- Send, reply, forward (to the folder's chats), edit, delete, react, pin, copy and select several
- Find in a chat, and jump to replied-to, pinned and found messages, however far back
- Drafts are kept, and shared with your other Telegram apps

### Media
- Photos, videos, GIFs, voice messages, round videos, stickers and files, to view and to send
- Photos and videos are made smaller before sending, unless you turn it off for a message
- YouTube, Instagram and TikTok links play inside the app
- Send your location, or share it live for 15 minutes, 1 hour or 8 hours; locations show on a map
- Choose how much downloaded media to keep, or clear it

### Notifications
- Only for the folder's chats, one notification per chat
- Instant through a push app such as ntfy (UnifiedPush), or a check every 15 minutes to 3 hours
- Message text is left out unless you turn it on, and never shows on the lock screen

### Privacy
- The local database is encrypted with a key kept in Android's Keystore
- No analytics, no crash reporting: logs stay on the phone unless you share them
