import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Release signing.
 *
 * Android identifies an app by its signing key, not by its package name or version. Every APK this
 * project has handed out so far was signed with a throwaway debug key, so each one was a *different
 * app*: nobody could update, only uninstall and lose everything they had collected. For a build
 * people are asked to run for months that is fatal, and it is the reason this block exists.
 *
 * The key and its passwords live outside the repository -- see keystore.properties.template. When
 * that file is absent the release build still assembles, unsigned: a fresh clone, another machine
 * or CI must never need the private key in order to compile.
 *
 * Generate the key ONCE and back it up. There is no recovery: lose it and no existing install can
 * ever be updated again, by anyone.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val releaseKeystore = keystoreProps.getProperty("storeFile")
    // A copied-but-unfilled template leaves this as the empty string, and rootProject.file("")
    // throws "Cannot convert '' to File" -- failing the build for everyone, including the debug
    // build that never wanted a key. Absent and blank both mean "no keystore configured".
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.signalscope"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.signalscope"
        minSdk = 31
        targetSdk = 36
        // versionCode must increase by one on every build handed to anyone, and must never go
        // backwards: Android refuses to install an older code over a newer one. versionName is for
        // humans and carries no rules. 2 is the first release-signed build.
        versionCode = 2
        versionName = "0.2.0"

        // MapLibre ships native libs for four ABIs, which took the debug APK to ~75 MB.
        // The reference device is arm64; keep installs over wireless adb quick.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            // R8 stays off for now. Room, MapLibre and Shizuku all reach for classes reflectively
            // and shrinking them needs a keep-rule pass that has not been done or tested; a release
            // that crashes only on other people's phones is worse than a larger APK.
            isMinifyEnabled = false
            // Null when no keystore is configured, which leaves the APK unsigned rather than
            // silently falling back to the debug key -- the exact substitution that caused the
            // update problem in the first place.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Pre-added so the map work needs no build-file change.
    implementation("org.maplibre.gl:android-sdk:11.11.0")

    // Shizuku: shell-UID privileges granted by the user via wireless debugging, no root.
    // Reaches MODIFY_PHONE_STATE and READ_PRECISE_PHONE_STATE, which the shell UID holds.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
}
