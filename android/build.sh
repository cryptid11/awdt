#!/usr/bin/env bash
# Builds WDT (the `wdt` command line tool and libwdt) for Android.
#
# Usage: android/build.sh [abi] [api]
#   abi: arm64-v8a (default) | armeabi-v7a | x86_64 | x86 | all
#   api: minimum Android API level (default 24)
#
# Requires ANDROID_NDK_HOME, cmake >= 3.10 and ninja on PATH.
# Set WDT_ANDROID_TESTS=1 to also build WDT's unit tests.
# Output: _android/<abi>/install/{bin,lib,include}
set -euo pipefail

ABI=${1:-arm64-v8a}
API=${2:-24}
HERE=$(cd "$(dirname "$0")" && pwd)
WDT_SRC=$(cd "$HERE/.." && pwd)
OUT=${WDT_ANDROID_OUT:-$WDT_SRC/_android}
SRC=${WDT_DEPS_SRC:-$OUT/src}
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your NDK root}"

if [ "$ABI" = all ]; then
  for a in arm64-v8a armeabi-v7a x86_64 x86; do "$0" "$a" "$API"; done
  exit
fi

"$HERE/build-deps.sh" "$ABI" "$API"

PREFIX=$OUT/$ABI/prefix
cmake -S "$WDT_SRC" -B "$OUT/$ABI/build/wdt" -G Ninja -Wno-dev -Wno-deprecated \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$API" \
  -DANDROID_STL=c++_static \
  -DCMAKE_PREFIX_PATH="$PREFIX" \
  -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
  -DCMAKE_INSTALL_PREFIX="$OUT/$ABI/install" \
  -DBUILD_SHARED_LIBS=OFF \
  -DOPENSSL_USE_STATIC_LIBS=ON \
  -DGFLAGS_NOTHREADS=OFF \
  -DOPENSSL_ROOT_DIR="$PREFIX" \
  -DFOLLY_SOURCE_DIR="$SRC/folly" \
  -DBUILD_TESTING="$([ -n "${WDT_ANDROID_TESTS:-}" ] && echo ON || echo OFF)"
cmake --build "$OUT/$ABI/build/wdt" -j "${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu)}"
cmake --install "$OUT/$ABI/build/wdt" --strip >/dev/null
echo "WDT for $ABI (API $API) installed in $OUT/$ABI/install"
