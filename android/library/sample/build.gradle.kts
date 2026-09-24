plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
