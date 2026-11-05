import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.FilterConfiguration
import com.google.protobuf.gradle.id
import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.aboutLibraries)
}

val ffmpegModuleExists =
    providers.provider { project.file("libs/lib-decoder-ffmpeg-release.aar").exists() }
val av1ModuleExists =
    providers.provider { project.file("libs/lib-decoder-av1-release.aar").exists() }
val extensionsRepoActive =
    providers.provider { project.hasProperty("WholphinExtensionsUsername") }

// See https://issuetracker.google.com/issues/402800800
val isBuildingBundle =
    providers.provider {
        gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
    }

// Version code = base + number of commits: grows with every build, so the Fire TV launcher refreshes icon and
// banner. When the history is rewritten (fewer commits), raise the base so the code stays above the last one
// published: Android refuses to install an older code over a newer one, and so does the self-update.
val versionCodeBase = 2000 // public repository started over on 5/11/2026; the last private build was 1967
val gitCommitCount =
    providers
        .exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText
        .get()

// Platea's own version: 1.1.0 is the first one without the inherited interface, 1.2.0 the first public one.
// The name also carries the version code and the hash, so every build can be told apart without git tags.
val plateaVersion = "1.2.0"
val gitHash =
    providers
        .exec { commandLine("git", "rev-parse", "--short=8", "HEAD") }
        .standardOutput.asText
        .get()

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3
        jvmTarget = JvmTarget.JVM_11
        javaParameters = true

        // Workaround for https://github.com/google/ksp/issues/2964
        moduleName = "${project.group}_${project.name}"
    }
}

private fun Provider<String>.getInt() = get().toInt()

configure<ApplicationExtension> {
    namespace = "com.github.gionnio.platea"
    compileSdk = libs.versions.compileSdk.getInt()

    defaultConfig {
        applicationId = "com.github.gionnio.platea"
        minSdk = libs.versions.minSdk.getInt()
        targetSdk = libs.versions.targetSdk.getInt()
        versionCode = versionCodeBase + gitCommitCount.trim().toInt()
        versionName = "$plateaVersion-$versionCode-g${gitHash.trim()}"

        buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString())
        manifestPlaceholders += mapOf("leanback" to true)
    }

    // The release key lives outside the repository: its path and password come from ~/.gradle/gradle.properties
    // (PlateaReleaseStoreFile, PlateaReleaseStorePassword, PlateaReleaseKeyAlias). Without them the release
    // build stays unsigned.
    val releaseStoreFile = providers.gradleProperty("PlateaReleaseStoreFile").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("PlateaReleaseStorePassword").get()
                keyAlias = providers.gradleProperty("PlateaReleaseKeyAlias").getOrElse("platea")
                keyPassword = providers.gradleProperty("PlateaReleaseStorePassword").get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            isDebuggable = false
            buildConfigField("boolean", "PLATEA_BENCH", "false")
        }

        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "PLATEA_BENCH", "true")
        }

        // Release-optimized build for benchmarks (scripts/bench_home.sh): same package and key as debug,
        // so it installs over the debug app and keeps login and settings
        create("bench") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "PLATEA_BENCH", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    splits {
        abi {
            // Disable split abis when building bundles
            isEnable = !isBuildingBundle.get()

            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs {
            // libass-android and the Media3 decoder extensions both package libc++_shared.so
            pickFirsts += "lib/*/libc++_shared.so"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        disable.add("MissingTranslation")
    }
    androidResources {
        generateLocaleConfig = true
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs
            .map { it as com.android.build.api.variant.impl.VariantOutputImpl }
            .forEach { output ->
                val abi =
                    output
                        .getFilter(FilterConfiguration.FilterType.ABI)
                        .let { if (it != null) "-${it.identifier}" else "" }
                val outputFileName =
                    "Platea-${variant.buildType}-${output.versionName.get()}-${output.versionCode.get()}$abi.apk"
                output.outputFileName = outputFileName
            }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.protobuf.kotlin.lite.get().version}"
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("java") {
                    option("lite")
                }
            }
            it.builtins {
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}
aboutLibraries {
    collect {
        configPath = file("config")
    }
    library {
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.SIMPLE
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    implementation(libs.androidx.media3.exoplayer)
    // Platea: direct reading from the NAS (SMB2/3), see nas/SmbDataSource
    implementation(libs.smbj)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.ass.media)

    implementation(libs.coil.core)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.cachecontrol)
    implementation(libs.coil.network.okhttp)

    implementation(libs.jellyfin.core)
    implementation(libs.jellyfin.api)
    implementation(libs.jellyfin.api.okhttp)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    implementation(libs.androidx.room.common.jvm)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.media3.effect)
    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.android.compiler)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.timber)
    implementation(libs.slf4j2.timber)
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.kache)
    implementation(libs.kache.file)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    if (ffmpegModuleExists.get()) {
        logger.info("Using local ffmpeg decoder")
        implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    } else if (extensionsRepoActive.get()) {
        logger.info("Using prebuilt ffmpeg decoder")
        implementation(libs.wholphin.extensions.ffmpeg)
    } else {
        logger.warn("Media3 ffmpeg decoder was NOT found")
    }
    if (av1ModuleExists.get()) {
        logger.info("Using local av1 decoder")
        implementation(files("libs/lib-decoder-av1-release.aar"))
    } else if (extensionsRepoActive.get()) {
        logger.info("Using prebuilt av1 decoder")
        implementation(libs.wholphin.extensions.av1)
    } else {
        logger.warn("Media3 av1 decoder was NOT found")
    }
    implementation(project(":engine"))
    // Installs the baseline profile (src/main/baseline-prof.txt) also on sideloaded builds
    implementation(libs.androidx.profileinstaller)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.mockk.android)
    testImplementation(libs.mockk.agent)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
}
