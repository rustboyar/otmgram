#!/usr/bin/env bash
# Builds TDLib with the Java (JNI) interface for Android and installs the
# results into the :tdlib module:
#   tdlib/src/main/jniLibs/<abi>/libtdjni.so
#   tdlib/src/main/java/org/drinkless/tdlib/{Client,TdApi}.java
#
# Follows td/example/android/build-tdlib.sh, but without PHP: TdApi.java is
# generated without Javadoc and @Nullable annotations.
#
# Env overrides: ANDROID_HOME, TDLIB_WORK_DIR, JOBS.
set -euo pipefail

TD_COMMIT=ea3f69ce11aef458bfbb09869b809ede4447486f # TDLib 1.8.69
OPENSSL_VERSION=1.1.1w
OPENSSL_SHA256=cf3098950cb4d853ad95c0841f1f9c6d3dc102dccfcacd521d93925208b76ac8
NDK_VERSION=29.0.14206865
API_LEVEL=29
ABIS=(arm64-v8a)
# Some TDLib translation units need several GB of RAM each.
JOBS=${JOBS:-8}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=${TDLIB_WORK_DIR:-$ROOT/.tdlib-build}
ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK=$ANDROID_HOME/ndk/$NDK_VERSION
NDK_BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
OUT=$ROOT/tdlib/src/main

for tool in cmake ninja gperf java perl make git curl sha256sum; do
  command -v "$tool" >/dev/null || { echo "error: $tool is not installed" >&2; exit 1; }
done
[ -d "$NDK" ] || { echo "error: NDK $NDK_VERSION not found in $ANDROID_HOME" >&2; exit 1; }

mkdir -p "$WORK"
TD=$WORK/td

echo "==> TDLib source @ $TD_COMMIT"
if [ ! -d "$TD/.git" ]; then
  git clone https://github.com/tdlib/td.git "$TD"
fi
if [ "$(git -C "$TD" rev-parse HEAD)" != "$TD_COMMIT" ]; then
  git -C "$TD" fetch origin
  git -C "$TD" checkout --detach "$TD_COMMIT"
fi

build_openssl() {
  local abi=$1 target
  case $abi in
    arm64-v8a) target=android-arm64 ;;
    x86_64) target=android-x86_64 ;;
    *) echo "error: unsupported ABI $abi" >&2; exit 1 ;;
  esac
  local prefix=$WORK/openssl/$abi
  [ -f "$prefix/lib/libssl.a" ] && return

  echo "==> OpenSSL $OPENSSL_VERSION for $abi"
  local tarball=$WORK/openssl-$OPENSSL_VERSION.tar.gz
  if [ ! -f "$tarball" ]; then
    curl -fL -o "$tarball.part" "https://www.openssl.org/source/openssl-$OPENSSL_VERSION.tar.gz"
    mv "$tarball.part" "$tarball"
  fi
  echo "$OPENSSL_SHA256  $tarball" | sha256sum -c -

  local src=$WORK/openssl-src
  rm -rf "$src" && mkdir -p "$src"
  tar xzf "$tarball" -C "$src" --strip-components=1
  (
    cd "$src"
    export ANDROID_NDK_HOME=$NDK PATH=$NDK_BIN:$PATH
    LDFLAGS=-Wl,-z,max-page-size=16384 ./Configure "$target" no-shared \
      -U__ANDROID_API__ -D__ANDROID_API__=$API_LEVEL
    sed -i 's/-O3/-O3 -ffunction-sections -fdata-sections/g' Makefile
    make depend -s
    make -j"$JOBS" -s build_libs
    mkdir -p "$prefix/lib"
    cp libcrypto.a libssl.a "$prefix/lib/"
    cp -r include "$prefix/"
  )
  rm -rf "$src"
}

echo "==> Generating TDLib sources and TdApi.java"
NATIVE=$WORK/build-native
cmake -S "$TD/example/android" -B "$NATIVE" -G Ninja -DTD_GENERATE_SOURCE_FILES=ON
cmake --build "$NATIVE" -j "$JOBS"
cmake --build "$NATIVE" --target tl_generate_java -j "$JOBS"

for abi in "${ABIS[@]}"; do
  build_openssl "$abi"

  echo "==> TDLib for $abi (API $API_LEVEL, NDK $NDK_VERSION)"
  build=$WORK/build-$abi
  cmake -S "$TD/example/android" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DANDROID_STL=c++_static \
    -DOPENSSL_ROOT_DIR="$WORK/openssl/$abi" \
    -DTD_ENABLE_PCH=ON
  cmake --build "$build" --target tdjni -j "$JOBS"

  mkdir -p "$OUT/jniLibs/$abi"
  cp "$build/libtdjni.so" "$OUT/jniLibs/$abi/"
done

echo "==> Installing Java sources"
JAVA_OUT=$OUT/java/org/drinkless/tdlib
mkdir -p "$JAVA_OUT"
cp "$TD/example/java/org/drinkless/tdlib/Client.java" "$JAVA_OUT/"
mv "$TD/example/android/org/drinkless/tdlib/TdApi.java" "$JAVA_OUT/"

echo "==> Done: TDLib $(git -C "$TD" describe --tags --always) installed into $OUT"
