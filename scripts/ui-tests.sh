#!/usr/bin/env bash
# Runs the UI tests (app/src/androidTest) on the connected device or emulator, keeping the
# app's data: Gradle's connectedAndroidTest uninstalls the app afterwards, which logs it out.
#   scripts/ui-tests.sh                 all UI tests
#   scripts/ui-tests.sh ui.chat.DeleteDialogTest   one class (relative to dev.otmgram)
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"

./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest
# -r keeps data; -t allows test packages.
"$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

args=()
[ $# -gt 0 ] && args=(-e class "dev.otmgram.$1")
# am instrument exits 0 even when tests fail; its output says.
output=$("$ADB" shell am instrument -w "${args[@]}" dev.otmgram.test/androidx.test.runner.AndroidJUnitRunner)
echo "$output"
grep -q '^OK (' <<< "$output"
