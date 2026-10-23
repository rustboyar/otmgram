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
