import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The release key lives outside this repository, which is public: the file says where it is and how to open
// it, and neither it nor the key is ever committed. Without it, `assembleRelease` still builds — unsigned — so
// a fresh clone is not broken, it just cannot produce an installable APK.
val signing = File(System.getProperty("user.home"), ".android/tetra-release.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "com.kalotrapezis.drive"
    compileSdk { version = release(37) { minorApiLevel = 2 } } // 37.2: GeckoView 159 asks for it

    defaultConfig {
        applicationId = "com.kalotrapezis.tetra.auto"
        minSdk = 29 // head units: Android 10 sold as "13" (QP1A build ids)
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
        // The languages Settings offers: English (res/values) and each res/values-<tag> with a strings.xml, so adding a
        // language is that one file. Android's own App languages page reads the same folders (generateLocaleConfig).
        val languages = listOf("en") + file("src/main/res").listFiles().orEmpty()
            .filter { it.name.matches(Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")) && File(it, "strings.xml").exists() }
            .map { it.name.removePrefix("values-").replace("-r", "-") }.sorted()
        buildConfigField("String", "LANGUAGES", "\"${languages.joinToString(",")}\"")
    }
    androidResources { generateLocaleConfig = true }
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
    buildTypes {
        // What goes on the phone and the tablet is not debuggable (the user, 25 Oct): Android runs a debuggable app's
        // Compose several times slower (Notes 435 → 90 ms, Calendar 780 → 111 on the tablet). Same debug key, so it
        // installs over the app as it is. Logs: adb reads them in /sdcard/Android/data/<package>/files/logs (WriterLog.dir);
        // `./gradlew assembleDebug -Pdebuggable` for a build that run-as and a debugger can reach.
        debug { isDebuggable = project.hasProperty("debuggable") }
        release {
            isMinifyEnabled = false
            signing?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }
    // head units run Android 10: calls newer than minSdk are what lint must find
    lint { checkOnly += setOf("NewApi", "InlinedApi"); abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true; buildConfig = true }
    // One APK per processor. OpenCV, ML Kit, TensorFlow and MapLibre each ship a native library for four
    // architectures, and together they were 346 MB of a 423 MB APK — two thirds of it for emulators nobody
    // installs this on. Split, an arm64 phone downloads only its own.
    // Angus Mail's four jars each carry the same licence texts; one is enough (its META-INF/javamail.* stay)
    packaging {
        resources { excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md") }
        // Native libraries compressed (the user, 3 Nov): GeckoView's libxul is 154 MB stored, so the download is ~120 MB
        // smaller; the phone unpacks them at install, which costs that much storage there instead.
        jniLibs { useLegacyPackaging = true }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            // `-Pemulator`: an x86_64 APK too, for the AVDs; releases stay arm-only
            if (project.hasProperty("emulator")) include("x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation("com.google.zxing:core:3.5.3") // drawing a QR code; ML Kit only reads them
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    implementation("androidx.car.app:app:1.4.0") // Maps on the car's screen: Android Auto's templates (Apache 2.0)
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // AI Assist live: Voxtral realtime's WebSocket (already in the app through MapLibre)
    implementation("org.mozilla.geckoview:geckoview:159.0.20261102144711") // Browser: Firefox's engine and its extensions (MPL 2.0)
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.42") // Phone: a number read in the SIM's country (Apache 2.0)
    // Maps: offline routing on the graph the computer builds (Apache 2.0). Only what reading a graph needs: Janino (run-time
    // bytecode, which Android cannot load — MapsRouting writes the custom models out), the OSM reader, XML and logback stay out.
    implementation("com.graphhopper:graphhopper-core:10.2") {
        exclude(group = "org.codehaus.janino")
        exclude(group = "org.openstreetmap.osmosis")
        exclude(group = "com.fasterxml.jackson.dataformat")
        exclude(group = "org.apache.xmlgraphics")
        exclude(group = "de.westnordost")
        exclude(group = "ch.qos.logback")
        exclude(group = "org.jetbrains.kotlin")
    }
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // real org.json for JVM tests (Android stubs it)
    testImplementation("com.ibm.icu:icu4j:72.1") // CLDR's plural rules for JVM tests (Android has them as android.icu)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
