#!/usr/bin/env bash
# Checks before committing: formatting (ktlint), Android lint (with Compose rules) and the unit
# tests. No device needed. Formatting is fixed with ./gradlew spotlessApply.
#   scripts/check.sh            formatting, lint and unit tests
#   scripts/check.sh --release  also builds the signed, shrunk release APK
# UI tests need a device: scripts/ui-tests.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# The film looks' native library: quick once built.
scripts/build-film.sh

# Compiling the UI tests too catches them breaking without a device.
tasks=(spotlessCheck :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin)
[ "${1:-}" = "--release" ] && tasks+=(:app:assembleRelease)

if ! ./gradlew -q "${tasks[@]}"; then
    echo "Failed. Formatting: ./gradlew spotlessApply. Reports: app/build/reports/lint-results-debug.html, app/build/reports/tests/testDebugUnitTest/index.html" >&2
    exit 1
fi
echo "All checks passed."
