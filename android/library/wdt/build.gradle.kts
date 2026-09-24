plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val wdtMinSdk = 24
// The WDT source tree (this is <wdt>/android/library)
val wdtSourceDir: File = rootDir.parentFile.parentFile
// Where android/build.sh puts WDT and its dependencies, per ABI
val wdtAndroidOut: String = providers.gradleProperty("wdt.androidOut")
    .getOrElse(File(wdtSourceDir, "_android").path)
val wdtAbis: List<String> = providers.gradleProperty("wdt.abis")
    .map { it.split(",").map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
val wdtCmakeVersion = "3.22.1"

android {
    namespace = "com.facebook.wdt"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = wdtMinSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += wdtAbis
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DWDT_ANDROID_OUT=$wdtAndroidOut",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = wdtCmakeVersion
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}

// Builds WDT and its dependencies (static libraries) with android/build.sh,
// for each ABI, before the JNI library is configured. Incremental: only the
// first run is slow.
val buildWdtNative = tasks.register("buildWdtNative") {
    group = "build"
    description = "Builds WDT and its dependencies for $wdtAbis (android/build.sh)"
    val ndkDir = android.ndkDirectory
    val sdkCmakeBin = File(android.sdkDirectory, "cmake/$wdtCmakeVersion/bin")
    val script = File(wdtSourceDir, "android/build.sh")
    doLast {
        for (abi in wdtAbis) {
            val pb = ProcessBuilder("bash", script.path, abi, wdtMinSdk.toString())
                .inheritIO()
            val env = pb.environment()
            env["ANDROID_NDK_HOME"] = ndkDir.path
            env["WDT_ANDROID_OUT"] = wdtAndroidOut
            if (sdkCmakeBin.isDirectory) {
                // the SDK's cmake package also has ninja
                env["PATH"] = sdkCmakeBin.path + File.pathSeparator + env["PATH"]
            }
            val exit = pb.start().waitFor()
            if (exit != 0) {
                throw GradleException(
                    "android/build.sh $abi failed ($exit). It needs cmake >= 3.22 and " +
                        "ninja on PATH (or the SDK's cmake;$wdtCmakeVersion package)",
                )
            }
        }
    }
}
tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
    dependsOn(buildWdtNative)
}
