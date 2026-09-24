#!/usr/bin/env bash
# Cross-compiles WDT's third-party dependencies (static libs) for Android.
#
# Usage: android/build-deps.sh [abi] [api]
#   abi: arm64-v8a (default) | armeabi-v7a | x86_64 | x86
#   api: minimum Android API level (default 24)
#
# Env: ANDROID_NDK_HOME  - NDK root (required)
#      WDT_ANDROID_TESTS - if set, also builds googletest (for WDT's tests)
#      WDT_DEPS_SRC      - where dependency sources are cloned (default ./_android/src)
#      WDT_ANDROID_OUT   - output root (default ./_android)
set -euo pipefail

ABI=${1:-arm64-v8a}
API=${2:-24}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${WDT_ANDROID_OUT:-$HERE/../_android}
SRC=${WDT_DEPS_SRC:-$OUT/src}
PREFIX=$OUT/$ABI/prefix
BUILD=$OUT/$ABI/build
JOBS=${JOBS:-$(nproc 2>/dev/null || sysctl -n hw.ncpu)}
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to your NDK root}"
TOOLCHAIN=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake

mkdir -p "$SRC" "$PREFIX" "$BUILD"

clone() { # repo dir ref
  [ -d "$SRC/$2" ] || git clone -q --depth 1 ${3:+-b $3} "https://github.com/$1.git" "$SRC/$2"
}
clone google/double-conversion double-conversion v3.3.1
clone fmtlib/fmt fmt 11.2.0
clone gflags/gflags gflags v2.2.2
clone google/glog glog v0.7.1
clone openssl/openssl openssl openssl-3.0.17
# Pinned: folly HEAD requires C++20 and no longer has some of the files WDT
# compiles; this release builds as C++17 with WDT's folly source list
clone facebook/folly folly "${FOLLY_REF:-v2025.09.29.00}"
clone fastfloat/fast_float fast_float v8.0.2
# folly uses a few header-only Boost libraries (preprocessor, crc, ...)
BOOST_VER=1.88.0
if [ ! -d "$SRC/boost/boost" ]; then
  mkdir -p "$SRC/boost"
  curl -sSL "https://archives.boost.io/release/$BOOST_VER/source/boost_${BOOST_VER//./_}.tar.bz2" |
    tar xj -C "$SRC/boost" --strip-components=1 "boost_${BOOST_VER//./_}/boost"
fi

CMAKE_COMMON=(
  -G Ninja
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
  -DANDROID_ABI="$ABI"
  -DANDROID_PLATFORM="android-$API"
  -DANDROID_STL=c++_static
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
  -DCMAKE_PREFIX_PATH="$PREFIX"
  -DCMAKE_FIND_ROOT_PATH="$PREFIX"
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DBUILD_SHARED_LIBS=OFF
  -DBUILD_TESTING=OFF
  -Wno-dev -Wno-deprecated
)

cmake_dep() { # name [extra cmake args...]
  local name=$1; shift
  [ -f "$BUILD/$name.done" ] && return
  echo "==> $name ($ABI)"
  cmake -S "$SRC/$name" -B "$BUILD/$name" "${CMAKE_COMMON[@]}" "$@" >"$BUILD/$name.log"
  cmake --build "$BUILD/$name" -j "$JOBS" >>"$BUILD/$name.log"
  cmake --install "$BUILD/$name" >>"$BUILD/$name.log"
  touch "$BUILD/$name.done"
}

mkdir -p "$PREFIX/include"
[ -e "$PREFIX/include/boost" ] || ln -s "$SRC/boost/boost" "$PREFIX/include/boost"
cp -r "$SRC/fast_float/include/fast_float" "$PREFIX/include/"

cmake_dep double-conversion
cmake_dep fmt -DFMT_TEST=OFF -DFMT_DOC=OFF
cmake_dep gflags -DGFLAGS_NAMESPACE=google -DBUILD_gflags_nothreads_LIB=OFF
if [ -n "${WDT_ANDROID_TESTS:-}" ]; then
  clone google/googletest googletest v1.17.0
  cmake_dep googletest -DINSTALL_GTEST=ON -DBUILD_GMOCK=ON
fi
cmake_dep glog -DGFLAGS_NOTHREADS=OFF -DWITH_GFLAGS=ON -DWITH_GTEST=OFF -DWITH_UNWIND=OFF

# OpenSSL: only libcrypto is used by WDT
if [ ! -f "$BUILD/openssl.done" ]; then
  echo "==> openssl ($ABI)"
  case $ABI in
    arm64-v8a) OSSL_TARGET=android-arm64 TRIPLE=aarch64-linux-android ;;
    armeabi-v7a) OSSL_TARGET=android-arm TRIPLE=armv7a-linux-androideabi ;;
    x86_64) OSSL_TARGET=android-x86_64 TRIPLE=x86_64-linux-android ;;
    x86) OSSL_TARGET=android-x86 TRIPLE=i686-linux-android ;;
    *) echo "unknown ABI $ABI" >&2; exit 1 ;;
  esac
  rm -rf "$BUILD/openssl" && mkdir -p "$BUILD/openssl"
  (
    cd "$BUILD/openssl"
    export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME
    NDK_BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
    export PATH=$NDK_BIN:$PATH
    # The API-suffixed clang driver sets the min SDK level
    CC=$TRIPLE$API-clang "$SRC/openssl/Configure" "$OSSL_TARGET" \
      --prefix="$PREFIX" --libdir=lib no-shared no-tests \
      no-engine no-dso no-ui-console >"$BUILD/openssl.log"
    make -j "$JOBS" build_libs >>"$BUILD/openssl.log"
    make install_dev >>"$BUILD/openssl.log"
  )
  touch "$BUILD/openssl.done"
fi

echo "Dependencies for $ABI installed in $PREFIX"
