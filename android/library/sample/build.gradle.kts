plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Same property as the library (see ../wdt/build.gradle.kts)
val wdtAbis: List<String> = providers.gradleProperty("wdt.abis")
    .map { it.split(",").map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))

// Set by CI from its build number: every published build is newer, which is
// how updaters (Obtainium, F-Droid clients...) find updates
val wdtVersionCode: Int = providers.gradleProperty("wdt.versionCode").map(String::toInt).getOrElse(1)

// The release key, from the environment (CI: the repository's secrets). Without
// it, builds are signed with the public debug key below: fine for testing,
// but never publish those (anyone could sign "updates" with that key)
val releaseKeystore: String? = providers.environmentVariable("AWDT_KEYSTORE_FILE").orNull

android {
    namespace = "com.facebook.wdt.sample"
    compileSdk = 35
    // Same NDK as the library: used to strip libwdtjni.so when packaging
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "io.github.cryptid11.awdt"
        minSdk = 24
        targetSdk = 35
        versionCode = wdtVersionCode
        versionName = "1.0.$wdtVersionCode"
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
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("AWDT_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("AWDT_KEY_ALIAS").get()
                keyPassword = storePassword // PKCS12 keystores: one password
            }
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
        // The published build: minified (which also checks the library's
        // consumer ProGuard rules), signed with the release key when there's one
        create("minified") {
            initWith(getByName("debug"))
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
    testImplementation("junit:junit:4.13.2")
}
