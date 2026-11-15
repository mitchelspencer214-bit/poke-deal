plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pokedealscanner.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pokedealscanner.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"

        // Phones only: drops emulator (x86) copies of the OCR engine to shrink the APK.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
