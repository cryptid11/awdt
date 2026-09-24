# WDT on Android

* A Kotlin/Java library (AAR) for using WDT from Android apps: see
  [Kotlin API](#kotlin-api) below.
* Scripts to cross-compile WDT for Android with the NDK. Each build gives you:
  * `bin/wdt`: the command line tool, a single PIE executable. It only needs
    system libraries (`libc`, `libm`, `libdl`, `liblog`).
  * `lib/libwdt_min.a`, `lib/libwdt.a`, `lib/libfolly4wdt.a` and `include/`
    (WDT's and folly's headers): for linking WDT into your own native code.
    The dependencies' static libraries and headers are in
    `_android/<abi>/prefix`.

## Requirements

* Android NDK (tested with r27d), with `ANDROID_NDK_HOME` pointing at it
* CMake >= 3.10 and Ninja on `PATH`
* git, curl, make, perl (for OpenSSL)

## Build

```sh
export ANDROID_NDK_HOME=/path/to/android-ndk-r27d
android/build.sh arm64-v8a      # or armeabi-v7a, x86_64, x86, all
android/build.sh arm64-v8a 29   # optional 2nd arg: minSdk (default 24)
```

Output goes to `_android/<abi>/install`. On first use, `build-deps.sh` clones
and builds pinned versions of double-conversion, fmt, gflags, glog, OpenSSL
(libcrypto), fast_float and folly. It also downloads the Boost headers, which
folly uses header-only. Later runs reuse what is already built.

## Run on a device

```sh
adb push _android/arm64-v8a/install/bin/wdt /data/local/tmp/
# receiver (prints the connection URL):
adb shell /data/local/tmp/wdt -directory /data/local/tmp/in
# sender, give it the receiver's URL:
adb shell /data/local/tmp/wdt -directory /data/local/tmp/out -connection_url 'wdt://...'
```

Between a device and a computer on the same network, use the device's IP
address in the URL (`-hostname`), or with `adb forward` forward the ports the
receiver prints (default 22356-22363).

Inside an app, `/data/local/tmp` isn't accessible. Use the app's files
directory, and the app needs the `INTERNET` permission.

## Tests

```sh
WDT_ANDROID_TESTS=1 android/build.sh x86_64
# gtest binaries end up in _android/x86_64/build/wdt/_bin/wdt/
adb push <test binary> /data/local/tmp/tests/
adb shell 'cd /data/local/tmp/tests && TMPDIR=$PWD ./protocol_test'
```

Android has no `/tmp`, so the tests use `$TMPDIR` when it is set.

`e2e-test.sh` runs a loopback transfer on the device itself (a 200 MB file, 300
small files, an empty file and nested directories) and compares md5 sums.
Extra arguments are passed to both wdt processes:

```sh
adb push _android/x86_64/install/bin/wdt android/e2e-test.sh /data/local/tmp/
adb shell sh /data/local/tmp/e2e-test.sh -enable_checksum=true
```

## Kotlin API

The Gradle project in `library/` builds `com.facebook.wdt`, an AAR with a
Kotlin API on top of WDT's `Sender` and `Receiver`, through JNI
(`libwdtjni.so`, which links WDT statically). It works from Java too
(`JavaApiTest` covers that).

```kotlin
// Receiving device
val receiver = WdtReceiver(File(filesDir, "incoming"), hostName = deviceIp)
val url = receiver.start()          // share this with the sender (QR code, ...)
val report = receiver.awaitFinish() // blocking: call it off the main thread
receiver.close()

// Sending device (or a computer running `wdt -connection_url "$url" ...`)
WdtSender(url, File(filesDir, "outgoing")).use { sender ->
    val report = sender.transfer { progress -> Log.i("app", "${progress.percent}%") }
    check(report.isSuccess) { report }
}
```

* `WdtOptions` has properties for the common options. `set(name, value)` sets
  any other WDT option by its command line flag name. Invalid names or values
  throw `IllegalArgumentException`.
* `WdtSender(..., files = listOf(...))` sends only the given files. Use
  `SourceFile.fromFileDescriptor(name, pfd)` to send content you can only open
  through a `ContentResolver`, such as SAF documents or media. WDT reads the
  descriptor but doesn't close it.
* `abort()` stops a transfer from any thread, including from a
  `ProgressListener`. `close()` aborts and releases the native resources, and
  waits for a thread blocked in the transfer to return.
* By default the receiver's URL contains `localhost`. For transfers between
  devices, pass the device's IP address as `hostName`.
* WDT's logs go to logcat with the tag `WDT`, warnings and errors only by
  default: `Wdt.setLogLevel(Wdt.LogLevel.INFO)` shows everything.
* The URL contains the transfer's encryption secret. Share it over a channel
  you trust.

### Build the AAR

Requires a JDK 17+ and the Android SDK (`sdk.dir` in `library/local.properties`
or `ANDROID_HOME`), with the NDK `27.3.13750724` and CMake `3.22.1` packages.

```sh
cd android/library
./gradlew :wdt:assembleRelease   # -> wdt/build/outputs/aar/wdt-release.aar
./gradlew -Pwdt.abis=arm64-v8a,x86_64 :wdt:assembleRelease   # fewer ABIs
```

The `buildWdtNative` task runs `android/build.sh` for each ABI before CMake
builds the JNI library. The first build of each ABI takes a few minutes, later
builds are incremental.

### Tests

```sh
./gradlew :wdt:testDebugUnitTest             # JVM tests
./gradlew :wdt:connectedDebugAndroidTest     # on a device/emulator
```

The instrumented tests (`WdtTransferTest`) run real transfers on the device:
directory trees, every encryption mode with checksums, file descriptors, file
lists, aborts (including from a progress listener), `close()` while waiting,
and error cases. `JavaApiTest` runs a transfer through the API from Java.

### CI

`.github/workflows/android.yml` builds the AAR, the sample APKs (one per ABI
plus a universal one) and the `wdt` binaries on every push and pull request,
and uploads them as the `wdt-android` artifact. Pushes to `main` also update
the `latest` pre-release, and `v*` tags get a release. The native dependencies
are cached until `build-deps.sh` changes.

The sample is signed with `sample/debug.keystore`, a public debug key, so that
new builds install over older ones from any machine or CI run.

### The sample app

`sample/` is a small app built on the library (the APKs attached to the
releases):

* **Receive files** shows the device's address and a `wdt://` URL to give to
  the sender (Copy / Share). Received files are saved in `Download/WDT`.
* **Send**: paste the receiver's URL (or open a `wdt://` link, or share the
  URL to the app as text), then **Choose files and send**. Files shared to the
  app from other apps (gallery, file manager...) can be sent the same way.
* Between two phones: install it on both, on the same Wi-Fi network. With a
  computer: run `wdt -directory <folder> -connection_url '<URL>'` there to
  send to the phone, or start `wdt -directory <folder> -hostname <computer IP>`
  there and paste the URL it prints in the phone to send to the computer.
* **Run self-test** sends 20 MB to the device itself.

Its `minified` build type runs R8, which checks the library's ProGuard rules:
`./gradlew :sample:installMinified`.

## Changes to WDT made for this port

* `CMakeLists.txt`: SSE4.2 flags are only used when targeting x86_64, and are
  appended instead of replacing the toolchain's flags. No compiled Boost
  libraries are needed. Uses the fmt and glog CMake packages. Skips folly
  sources that are missing in the folly version being built, and adds the ones
  newer folly needs. An installed GTest works for the tests.
* `util/Stats.h`: uses a local `noncopyable` instead of `boost::noncopyable`.
* Socket code includes `<netinet/in.h>` directly. glibc pulls it in through
  `<netdb.h>`, bionic doesn't.
* `Wdt.cpp` / `wdtCmdLine.cpp`: the `Wdt` instance registry is never destroyed,
  and the CLI releases its instance before returning. Before, `Wdt` instances
  were destroyed during static destruction, after glog's statics, and then
  logged. glibc tolerates this silently, but bionic aborts with
  `FORTIFY: pthread_mutex_lock called on a destroyed mutex`, so every
  successful transfer exited with SIGABRT.
* Tests: `std::filesystem` instead of `boost::filesystem`, and `$TMPDIR`
  support.
* `CMakeLists.txt` also installs `WdtConfig.h`, and when building with the
  bundled folly, folly's headers and `folly-config.h`. The installed WDT headers
  need them.
