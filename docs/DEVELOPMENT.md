# Developing Otmgram

Building, testing and releasing. How the app works is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Setup

Builds on Linux (the scripts use the NDK's `linux-x86_64` tools and GNU tools).

Requirements: JDK 17+, Android SDK platform 37 and NDK `29.0.14206865`
(`sdkmanager "platforms;android-37.0" "ndk;29.0.14206865"`), `cmake`, `ninja`, `gperf`,
`perl`, `make`, `git`, `curl`, `sha256sum`. Building TDLib needs about 2 GB of disk
(`.tdlib-build/`) and plenty of RAM: lower `JOBS` (default 8) if the build runs out.

1. Build TDLib (takes a while; the outputs go to `tdlib/src/main` and aren't committed):

   ```sh
   ./scripts/build-tdlib.sh
   ```

2. Get an `api_id` and `api_hash` for your build at https://my.telegram.org ("API
   development tools"; Telegram's terms ask every app for its own) and put them in
   `local.properties`:

   ```properties
   sdk.dir=/home/you/Android/Sdk
   telegram.apiId=12345
   telegram.apiHash=0123456789abcdef0123456789abcdef
   ```

3. Build and install:

   ```sh
   ./gradlew installDebug
   ```

## Checks

```sh
scripts/check.sh             # formatting, lint, unit tests, and that the UI tests compile
scripts/check.sh --release   # and the release APK
scripts/ui-tests.sh          # UI tests on a device; keeps the app's data (unlike connectedAndroidTest)
./gradlew spotlessApply      # fixes the formatting
```

- Formatting: Spotless with ktlint; the style is in `.editorconfig`
- Android lint with Slack's compose-lints, failing on any issue; Kotlin warnings fail the build too
- Unit tests (`./gradlew :app:testDebugUnitTest`): message mapping, list rows, media mapping, links, wording and formats, log redaction, the mapping of login states and storage kinds, opening the database with its key, the chat's holders (composer, location, pinned, search, selection, stickers, outgoing), and against a fake TDLib (`testing/FakeTelegram`, behind the `TelegramClient` interface): the open chat (jumps included), the folder list, names, file states and what gets notified. Settings and notification marks use DataStore files of the test's own (they're injected)
- UI tests on a device: bubble states and their accessibility actions, the delete dialog. Not `connectedAndroidTest`, which uninstalls the app and so logs it out
- No CI: building TDLib takes too long for it

## Debug builds and the emulator

Debug builds connect to Telegram's production servers, so use an account you can spare.
`telegram.testDc=true` in `local.properties` switches them to the test servers, but their
test accounts (`99966XYYYY` numbers) stopped working in 2025: logins fail with
`PHONE_CODE_INVALID`.

Debug APKs include x86_64 for the emulator, release APKs are arm64 only.

```sh
./scripts/emulator.sh            # needs: sdkmanager "emulator" "system-images;android-37.0;google_apis;x86_64"
./scripts/emulator.sh --headless
```

Logging in needs a human for the code, so keep the debug app's session around. Backups
contain the account's auth keys and live in `~/.local/share/otmgram/sessions`; they stop
working once the account logs out.

```sh
./scripts/session.sh backup [name]
./scripts/session.sh restore [name]
```

`adb shell am broadcast -n dev.otmgram/.debug.RunCheckReceiver` runs a background check
now (debug builds only).

## Conventions

- Texts are string resources, one file per area (`res/values/strings_*.xml`)
- Never log message text, names, phone numbers, file names or the push endpoint
- User-facing changes get a line under "Unreleased" in [CHANGELOG.md](../CHANGELOG.md), in the words a user would use

## Releasing

Versions are `MAJOR.MINOR.PATCH`, set as `otmgram.version` in `gradle.properties`. The
version code follows from it (`MAJOR * 10000 + MINOR * 100 + PATCH`), so it grows with
every release, as Android needs to install an update.

Release builds are signed with Otmgram's key, kept outside the repo; a release build stops
if any of this is missing from `local.properties`:

```properties
release.storeFile=/home/you/.local/share/otmgram/release.jks
release.storePassword=…
release.keyAlias=otmgram
release.keyPassword=…
```

Back up the keystore and its passwords: Android installs updates only with the same key, so
losing it means everyone reinstalls and logs in again.

To release, with the UI tests passed on a device:

```sh
scripts/release.sh 0.2.0
```

It checks that `main` is clean and up to date and that the changelog has notes, runs
`scripts/check.sh`, sets the version, dates the changelog's section, builds the APK and
checks its signing certificate and version, then commits "Release 0.2.0" and tags `v0.2.0`.
It asks before pushing and publishing the GitHub release, with
`otmgram-0.2.0.apk`, its SHA-256 and the changelog's section as notes. Obtainium picks up the
release from there.
