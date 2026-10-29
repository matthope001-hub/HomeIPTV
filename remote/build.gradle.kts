plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.matt.tvdash.remote"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.matt.tvdash.remote"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "local")   // GitHub build number
    }
    // One shared key for debug builds (your computer and GitHub), so any build installs over any other.
    // It only signs this personal sideloaded app; it is not a Play Store key.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("tvdash-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
