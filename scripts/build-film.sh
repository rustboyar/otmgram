#!/usr/bin/env bash
# Builds the film looks (film/, Rust: tungsten's core behind a small JNI bridge) for Android
# and installs them as app/src/main/jniLibs/<abi>/libotmgram_film.so (not committed).
# Quick once built: cargo only rebuilds what changed. Run by scripts/check.sh.
#
# Needs Rust (rustup) with the Android targets:
#   rustup target add aarch64-linux-android x86_64-linux-android
# Env overrides: ANDROID_HOME, ABIS (space-separated; x86_64 is for the emulator).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK_VERSION=29.0.14206865
API_LEVEL=29
NDK_BIN=$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin
read -ra ABIS <<< "${ABIS:-arm64-v8a x86_64}"
CARGO=$(command -v cargo || echo "$HOME/.cargo/bin/cargo")
[ -x "$CARGO" ] || { echo "build-film: needs Rust: https://rustup.rs, then the targets above" >&2; exit 1; }
[ -d "$NDK_BIN" ] || { echo "build-film: no NDK $NDK_VERSION in $ANDROID_HOME" >&2; exit 1; }

for abi in "${ABIS[@]}"; do
    case $abi in
        arm64-v8a) target=aarch64-linux-android ;;
        x86_64) target=x86_64-linux-android ;;
        *) echo "build-film: unknown ABI $abi" >&2; exit 1 ;;
    esac
    linker_var=CARGO_TARGET_$(echo "$target" | tr 'a-z-' 'A-Z_')_LINKER
    env "$linker_var=$NDK_BIN/$target$API_LEVEL-clang" \
        "$CARGO" build --quiet --release --locked --manifest-path "$ROOT/film/Cargo.toml" --target "$target"
    mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
    cp "$ROOT/film/target/$target/release/libotmgram_film.so" "$ROOT/app/src/main/jniLibs/$abi/"
done
