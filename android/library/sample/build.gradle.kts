plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Same property as the library (see ../wdt/build.gradle.kts)
val wdtAbis: List<String> = providers.gradleProperty("wdt.abis")
    .map { it.split(",").map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))

android {
    namespace = "com.facebook.wdt.sample"
    compileSdk = 35
    // Same NDK as the library: used to strip libwdtjni.so when packaging
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.facebook.wdt.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // A fixed debug key (it's public: only for this sample), so builds
        // from any machine or CI run install over each other
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // One APK per ABI (smaller downloads) plus a universal one
    splits {
        abi {
            isEnable = true
            reset()
            include(*wdtAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    buildTypes {
        // Minified like a real release (also checks the library's consumer
        // ProGuard rules), but debug-signed so it installs without a key
        create("minified") {
            initWith(getByName("debug"))
            isMinifyEnabled = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            matchingFallbacks += listOf("release")
        }
        // After "minified" (initWith copies): only debug builds get the
        // suffix, so they install next to the minified app. Two copies on
        // one device can then share files with each other.
        getByName("debug") {
            applicationIdSuffix = ".debug"
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
    implementation(project(":wdt"))
}
