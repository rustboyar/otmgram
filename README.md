# Otmgram

A minimal Android Telegram client that only shows chats from one chat folder. See [PLAN.md](PLAN.md).

## Setup

Builds on Linux (the scripts use the NDK's `linux-x86_64` tools and GNU tools).

Requirements: JDK 17+, Android SDK platform 37 and NDK `29.0.14206865`
(`sdkmanager "platforms;android-37.0" "ndk;29.0.14206865"`), `cmake`, `ninja`, `gperf`,
`perl`, `make`, `git`, `curl`, `sha256sum`. Building TDLib needs about 2 GB of disk
(`.tdlib-build/`) and plenty of RAM: lower `JOBS` (default 8) if the build runs out.

1. Build TDLib (takes a while; outputs go to `tdlib/src/main`, not committed):

   ```sh
   ./scripts/build-tdlib.sh
   ```

2. Add your Telegram API credentials from https://my.telegram.org to `local.properties`:

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
scripts/check.sh             # lint (fails on any issue), unit tests, UI tests compile
scripts/check.sh --release   # and the release APK
scripts/ui-tests.sh          # UI tests on the device; keeps the app's data (unlike connectedAndroidTest)
```

## Release builds

Signed with your own key, kept outside the repo; a release build stops if any of this is
missing from `local.properties`:

```properties
release.storeFile=/home/you/.local/share/otmgram/release.jks
release.storePassword=…
release.keyAlias=otmgram
release.keyPassword=…
```

Back up the keystore and its passwords: Android installs updates only with the same key.

## Debug builds and the emulator

Debug builds connect to Telegram's production servers. `telegram.testDc=true` in
`local.properties` switches them to the test servers, but their test accounts
(`99966XYYYY` numbers) stopped working in 2025: logins fail with `PHONE_CODE_INVALID`.

Debug APKs include x86_64 for the emulator, release APKs are arm64 only.

```sh
./scripts/emulator.sh            # needs: sdkmanager "emulator" "system-images;android-37.0;google_apis;x86_64"
./scripts/emulator.sh --headless
```

Logging in needs a human for the code, so keep the session of the debug app around.
Backups contain the account's auth keys and live in `~/.local/share/otmgram/sessions`;
they stop working once the account logs out.

```sh
./scripts/session.sh backup [name]
./scripts/session.sh restore [name]
```
