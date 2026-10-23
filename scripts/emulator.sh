#!/usr/bin/env bash
# Creates the "otmgram" emulator on first use and starts it.
# Usage: scripts/emulator.sh [--headless]
set -euo pipefail

AVD=otmgram
IMAGE="system-images;android-37.0;google_apis;x86_64"
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
EMULATOR=$ANDROID_HOME/emulator/emulator

if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
  echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd \
    --name "$AVD" --package "$IMAGE" --device pixel_8
fi

# Fake cameras: circles need a front camera, and testing shouldn't film the room.
# Use "-camera-front webcam0" for a real webcam.
args=(-avd "$AVD" -no-boot-anim -no-snapshot-save -camera-front emulated -camera-back emulated)
if [ "${1:-}" = --headless ]; then
  args+=(-no-window -no-audio -gpu swiftshader_indirect)
fi
# The emulator UI doesn't run natively on Wayland; use XWayland.
QT_QPA_PLATFORM=xcb exec "$EMULATOR" "${args[@]}"
