import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release key lives outside this repository (Tetra's own, ~/.android/tetra-release.properties), so an update the phone
// sends installs over the app already in the car. Without it, `assembleRelease` still builds, unsigned.
val signing = File(System.getProperty("user.home"), ".android/tetra-release.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "com.kalotrapezis.tetra.auto"
    compileSdk { version = release(37) { minorApiLevel = 2 } } // Compose 1.12 asks for it

    defaultConfig {
        applicationId = "com.kalotrapezis.tetra.auto"
        minSdk = 29 // head units: Android 10 sold as "13" (QP1A build ids)
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    signingConfigs {
        signing?.let { properties ->
            create("release") {
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }
    // Two editions (the user, 13 Nov): projection shows the phone's picture; standalone (Tetra Auto SE, "stand alone edition")
    // runs Tetra Car on the unit itself and takes only signals and commands from the phone. Both install side by side.
    flavorDimensions += "edition"
    productFlavors {
        create("projection") { dimension = "edition" }
        create("standalone") {
            dimension = "edition"
            applicationIdSuffix = ".se"
            versionNameSuffix = "-se"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signing?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true }
    lint { checkOnly += setOf("NewApi", "InlinedApi") }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation("com.google.zxing:core:3.5.3") // the pairing QR the phone scans
    // Tetra Auto SE: the map (MapLibre, BSD-2; reads Tetra's .pmtiles) and the radio (Media3 ExoPlayer, Apache 2.0; its own sound)
    "standaloneImplementation"("org.maplibre.gl:android-sdk-opengl:13.6.1")
    "standaloneImplementation"("androidx.media3:media3-exoplayer:1.11.1")
    "standaloneImplementation"("androidx.media3:media3-exoplayer-hls:1.11.1")
    testImplementation(libs.junit)
}
