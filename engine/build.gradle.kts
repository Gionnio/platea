// Platea playback engine: device capabilities, server profile and playback decisions.
// Must not depend on any UI code (neither Wholphin's nor the future Platea GUI).
plugins {
    alias(libs.plugins.android.library)
}

private fun Provider<String>.getInt() = get().toInt()

android {
    namespace = "com.github.gionnio.platea.engine"
    compileSdk {
        version = release(libs.versions.compileSdk.getInt())
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.getInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Jellyfin API models (DeviceProfile, MediaSourceInfo, MediaStream...)
    api(libs.jellyfin.core)
    implementation(libs.androidx.core.ktx)
    // Same audio capability check as ExoPlayer
    implementation(libs.androidx.media3.exoplayer)

    testImplementation(libs.junit)
}
