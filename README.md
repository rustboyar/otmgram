# Otmgram

An Android app that shows the chats of **one Telegram folder**, and nothing else: no other
chats, no channels, no stories. Put the people who matter in a folder, and spend less time
on everything else.

Otmgram is an unofficial app that uses the Telegram API. It isn't made or endorsed by
Telegram.

## What it does

- Chats and groups from the folder you pick, with search through all their messages; pin chats in the folder, mute and unmute them
- Text, replies, forwards (within the folder), edits, deletions, reactions, pinned messages, find in chat
- Photos, videos, GIFs, voice messages, round videos, stickers and files, to view and to send; photos and videos go in SD, HD or as they are
- YouTube, Instagram and TikTok links play inside the app
- Send your location or share it live, and see others' on a map
- Notifications only for the folder's chats: instant, over a connection kept open in the
  background (no Google services needed), or a regular check
- Message text in notifications is off unless you turn it on, and never on the lock screen

Not there, on purpose: other chats, channels, calls, stories, secret chats, several
accounts, contacts, starting new chats. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## Install

Android 10 or newer, on a 64-bit ARM phone (nearly all phones from the last years).

- **[Obtainium](https://obtainium.imranr.net)**: add `https://github.com/rustboyar/otmgram`,
  and it installs each new release as it comes out.
- **By hand**: download `otmgram-X.Y.Z.apk` from
  [Releases](https://github.com/rustboyar/otmgram/releases) and open it.

Releases are signed with this certificate (SHA-256), which tools such as
[AppVerifier](https://github.com/soupslurpr/AppVerifier) can check:

```
25:A0:1A:D6:BB:F2:C8:80:19:55:A9:07:48:76:5D:D8:EF:C6:FF:8D:52:0E:98:AF:7F:FB:92:B2:16:E0:C5:3D
```

Log in with your Telegram phone number, then pick the folder. Folders are made in
Telegram's own apps (Settings → Chat Folders). For instant notifications, choose Instant in
Otmgram's settings, and allow it to run in the background when it asks.

## Privacy

Otmgram has no analytics, no ads and no crash reporting. It connects to:

- **Telegram**, for everything to do with your account
- **[OpenFreeMap](https://openfreemap.org)**, for map tiles when a location is shown; message
  previews load only city-level tiles, so the server learns just the rough area
- **YouTube, Instagram or TikTok**, only when you tap a link to play it

Its database is encrypted with a key kept in Android's Keystore. Logs stay on the phone,
without message text or names, until you share them from Settings. More in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#security).

## Build it yourself

You need Linux, the Android SDK and NDK, and your own Telegram API id from
[my.telegram.org](https://my.telegram.org). See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Contributing

Bug reports and ideas are welcome as issues. Otmgram is meant to stay small, so before
building a feature, open an issue to talk about it. Security problems: see
[SECURITY.md](SECURITY.md).

## License

Otmgram is free software under the [GNU General Public License v3.0 or later](LICENSE).
It bundles the Fraunces and Inter fonts, under the [SIL Open Font License](licenses/), and
builds on [TDLib](https://github.com/tdlib/td) (Boost Software License).
