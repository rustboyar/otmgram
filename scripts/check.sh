#!/usr/bin/env bash
# Checks before committing: Android lint and the unit tests. No device needed.
#   scripts/check.sh            lint and unit tests
#   scripts/check.sh --release  also builds the signed, shrunk release APK
# UI tests need a device: scripts/ui-tests.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

tasks=(:app:lintDebug :app:testDebugUnitTest)
[ "${1:-}" = "--release" ] && tasks+=(:app:assembleRelease)

if ! ./gradlew -q "${tasks[@]}"; then
    echo "Failed. Reports: app/build/reports/lint-results-debug.html, app/build/reports/tests/testDebugUnitTest/index.html" >&2
    exit 1
fi
echo "All checks passed."
