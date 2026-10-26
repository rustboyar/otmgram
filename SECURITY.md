# Security

## Reporting a problem

Please report security problems privately, through GitHub's
[private vulnerability reporting](https://github.com/rustboyar/otmgram/security/advisories/new)
(the repository's Security tab → "Report a vulnerability"), not in a public issue.

Say what the problem is, how to reproduce it, and what it lets someone do. You'll get an
answer within a week. Once it's fixed and released, the advisory is published, crediting
you if you like.

Fixes go into the latest release only.

## What counts

Anything that lets someone else read or act on a user's account or data through Otmgram,
for example:

- reading the login, database or downloaded media from outside the app
- message text, names or phone numbers ending up in logs, notifications on the lock screen,
  or anywhere outside the app the user didn't send them
- another app opening chats, sending messages or reading data through Otmgram
- a release signed with another key than the one in the README

Problems in Telegram itself or in TDLib belong to
[Telegram](https://telegram.org) or
[TDLib](https://github.com/tdlib/td).

How Otmgram protects data is described in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#security).
