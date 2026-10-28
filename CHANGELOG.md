# Changelog

What changed in each release of Otmgram, newest first. Versions are
MAJOR.MINOR.PATCH; while the first number is 0, anything may still change.

## Unreleased

### Changed
- Smoother long chats: a new message, reaction or edit no longer reworks every loaded message, and a playing voice message, the floating date or zooming a photo redraw only what changes
- Round videos and GIFs playing muted no longer decode their sound, and stickers in the picker stay still until you long-press one: less battery, less stutter
- Photos and round videos scrolled past before they finished downloading stop downloading

## 0.6.2 - 2026-10-28

### Changed
- Less battery and data with Instant notifications: an open chat and the chat list rest while the app is in the background, the connection kept open isn't needlessly reopened, and the minute's wake-up is skipped while the app is on screen or there's no network

### Fixed
- Switching between picked photos in the send preview and tapping a film right away could close the app
- The film preview no longer leaves memory behind when it's closed while a photo is loading, and makes fewer copies of the photo
- The minute's wake-up could go on after Instant notifications were turned off, if Android had closed the app before

## 0.6.1 - 2026-10-28

### Changed
- The send preview's film strip fills in faster: the looks are developed side by side, now that tungsten allows it

## 0.6.0 - 2026-10-28

### Added
- Film looks for photos you send, from tungsten (github.com/ian-te/tungsten): pick None, 800T, 50D, Portra, Ektar, Superia or Tri-X from a strip of your photo in each look, and hold the photo to see it without; the last film you used comes first

## 0.5.1 - 2026-10-28

### Added
- Voice messages: tap or drag on the waveform to jump to that point, and play them at 1×, 1.5× or 2× (the speed is kept for the next ones)

### Changed
- Contacts are shared from a person's profile (Share, then a chat of the folder) instead of from 📎; someone whose number you can't see is shared as their t.me link

### Fixed
- A voice message that had played to its end couldn't be played again

## 0.5.0 - 2026-10-28

### Added
- Who reacted: up to three show by face on the reaction, and the message's menu lists everyone who reacted, with their reaction
- While scrolling a chat, the day of the messages at the top floats above them
- Share a contact: 📎 → Contact lists your Telegram contacts to pick from; a contact received shows as a card, opening their profile

### Changed
- A tap on a message opens its menu, and a long press selects it
- Smoother chat: jumping to a message or to the newest glides there; the message jumped to gets a ring and a little bounce; new messages fade in and push the others up; selecting slides the messages aside; the menu grows in from the message, and opens sooner

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
