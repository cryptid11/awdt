#!/usr/bin/env bash
# Builds the desktop tools for this machine: awdt (receives files from the
# Android app) and wdt (WDT's command line tool), statically linked against
# their dependencies.
#
# Usage: desktop/build.sh
# Requires a C++17 compiler, cmake >= 3.22, ninja, git, curl, make, perl.
# Output: _desktop/install/bin/{awdt,wdt}
# Env: WDT_DESKTOP_OUT  output root (default ./_desktop)
#      WDT_DEPS_SRC     where dependency sources are cloned (default <out>/src)
#      WDT_DESKTOP_TESTS=1 also builds WDT's unit tests
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
WDT_SRC=$(cd "$HERE/.." && pwd)
OUT=${WDT_DESKTOP_OUT:-$WDT_SRC/_desktop}
SRC=${WDT_DEPS_SRC:-$OUT/src}
PREFIX=$OUT/host/prefix

WDT_ANDROID_OUT=$OUT WDT_DEPS_SRC=$SRC WDT_ANDROID_TESTS=${WDT_DESKTOP_TESTS:-} \
  "$WDT_SRC/android/build-deps.sh" host

LINK_FLAGS=
if [ "$(uname -s)" = Linux ]; then
  # runs on other distributions: only glibc is shared
  LINK_FLAGS="-static-libstdc++ -static-libgcc"
fi

cmake -S "$WDT_SRC" -B "$OUT/host/build/wdt" -G Ninja -Wno-dev -Wno-deprecated \
  -DCMAKE_PREFIX_PATH="$PREFIX" \
  -DCMAKE_INSTALL_PREFIX="$OUT/install" \
  -DBUILD_SHARED_LIBS=OFF \
  -DOPENSSL_USE_STATIC_LIBS=ON \
  -DOPENSSL_ROOT_DIR="$PREFIX" \
  -DGFLAGS_NOTHREADS=OFF \
  -DFOLLY_SOURCE_DIR="$SRC/folly" \
  -DCMAKE_EXE_LINKER_FLAGS="$LINK_FLAGS" \
  -DBUILD_TESTING="$([ -n "${WDT_DESKTOP_TESTS:-}" ] && echo ON || echo OFF)"
cmake --build "$OUT/host/build/wdt" -j "${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu)}"
cmake --install "$OUT/host/build/wdt" --strip >/dev/null
echo "awdt and wdt installed in $OUT/install/bin"
