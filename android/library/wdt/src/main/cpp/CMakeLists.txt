# JNI library (libwdtjni.so) for the Kotlin API. Links the static WDT and
# dependency libraries built by android/build.sh (for ANDROID_ABI) from
# WDT_ANDROID_OUT.
cmake_minimum_required(VERSION 3.22.1)
project(wdtjni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED on)

set(WDT_ANDROID_OUT "" CACHE PATH "Output directory of android/build.sh")
set(WDT_INSTALL "${WDT_ANDROID_OUT}/${ANDROID_ABI}/install")
set(WDT_PREFIX "${WDT_ANDROID_OUT}/${ANDROID_ABI}/prefix")
if (NOT EXISTS "${WDT_INSTALL}/lib/libwdt_min.a")
  message(FATAL_ERROR "WDT for ${ANDROID_ABI} not found in ${WDT_INSTALL}: "
    "run android/build.sh ${ANDROID_ABI} (the buildWdtNative Gradle task)")
endif()

# The NDK toolchain only searches its sysroot, add the dependencies' prefix
list(APPEND CMAKE_FIND_ROOT_PATH "${WDT_PREFIX}")
list(APPEND CMAKE_PREFIX_PATH "${WDT_PREFIX}")
set(GFLAGS_SHARED OFF)
set(GFLAGS_NOTHREADS OFF)
find_package(glog CONFIG REQUIRED)
find_package(fmt CONFIG REQUIRED)

add_library(wdtjni SHARED wdt_jni.cpp)
set_target_properties(wdtjni PROPERTIES CXX_VISIBILITY_PRESET hidden)
target_include_directories(wdtjni PRIVATE
  "${WDT_INSTALL}/include"
  "${WDT_PREFIX}/include"
)
target_link_libraries(wdtjni PRIVATE
  "${WDT_INSTALL}/lib/libwdt_min.a"
  "${WDT_INSTALL}/lib/libfolly4wdt.a"
  glog::glog
  fmt::fmt
  "${WDT_PREFIX}/lib/libdouble-conversion.a"
  "${WDT_PREFIX}/lib/libcrypto.a"
  log
)
# Only the JNI functions are exported: keep the statically linked libraries'
# symbols (and libc++'s) private to this library.
target_link_options(wdtjni PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
