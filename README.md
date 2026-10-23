# Otmgram

A minimal Android Telegram client that only shows chats from one chat folder. See [PLAN.md](PLAN.md).

## Setup

Requirements: JDK 17+, Android SDK with NDK `29.0.14206865`, `cmake`, `ninja`, `gperf`, `perl`.

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
