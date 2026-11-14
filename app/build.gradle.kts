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
        versionCode = 1
        versionName = "0.1"
    }
}
