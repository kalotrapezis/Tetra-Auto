import java.util.Properties

// Tetra Auto SE, "stand alone edition": Tetra for Android built for a head unit — Android 10, Tetra Car its start and home screen.
// Moved here from Tetra-mobile's `car` module at 0.8.0 Beta 24 (0c3e70f5, 18 Nov 2026): app's code, resources and assets
// copied into src/main, its manifest the one Beta 24's build generated from app's (Tetra Car the only icon), its dependencies
// app's as they were. Tetra for Android no longer carries the car; a change here is a change to the car only.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val signing = File(System.getProperty("user.home"), ".android/tetra-release.properties")
    .takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

android {
    namespace = "com.kalotrapezis.drive"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "com.kalotrapezis.tetra.auto.se"
        minSdk = 29 // head units: Android 10 sold as "13" (QP1A build ids)
        targetSdk = 36
        versionCode = 43
        versionName = "0.8.0-beta.27-se"
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
        debug { isDebuggable = project.hasProperty("debuggable") }
        release {
            isMinifyEnabled = false
            signing?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }
    // head units run Android 10: what newer than that is called must be guarded (`./gradlew :se:lintDebug`)
    lint { checkOnly += setOf("NewApi", "InlinedApi"); abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md") }
        jniLibs { useLegacyPackaging = true }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a") // UIS8581A units: often a 32-bit Android on a 64-bit chip
            if (project.hasProperty("emulator")) include("x86_64")
            isUniversalApk = false
        }
    }
}

// Tetra for Android's dependencies as they were at Beta 24 (its test ones left out: the car had no tests)
dependencies {
    val cameraX = "1.6.2"
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.google.zxing:core:3.5.3") // drawing a QR code; ML Kit only reads them
    implementation("com.mohamedrejeb.richeditor:richeditor-compose:1.2.0") // Writer: bold, lists, headings as you type (Apache 2.0)
    implementation("org.opencv:opencv:4.12.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    implementation("org.maplibre.gl:android-sdk-opengl:13.6.1")
    implementation("androidx.car.app:app:1.4.0") // Maps on the car's screen: Android Auto's templates (Apache 2.0)
    implementation("com.artifex.mupdf:fitz:1.28.5") // PDF: drawing (annotations too), editing, pages, forms (AGPL-3.0; Tetra is free software)
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0") // PDF › Recognize text: Greek and English OCR (Apache 2.0)
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // AI Assist live: Voxtral realtime's WebSocket (already in the app through MapLibre)
    implementation("org.dmfs:lib-recur:0.17.1") // Calendar: iCalendar RRULE repeats (Apache 2.0)
    implementation("org.mozilla.geckoview:geckoview:159.0.20261102144711") // Browser: Firefox's engine and its extensions (MPL 2.0)
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.42") // Phone: a number read in the SIM's country (Apache 2.0)
    implementation("org.eclipse.angus:angus-mail:2.0.3") // Mail: IMAP and SMTP for the accounts the phone fetches itself (EPL 2.0 / GPL 2.0 + Classpath Exception)
    implementation("androidx.autofill:autofill:1.1.0") // Passwords' autofill: its rows in the keyboard's strip (Gboard asks for them) (Apache 2.0)
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2") // Browser: Argon2id for the passwords' sync passphrase (MIT)
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
}

/** GraphHopper's ByteBuffer.get/put(index, bytes, offset, length) and Stream.toList() calls (Android 14+) turned into Nio29's (Android 10). */
abstract class Nio29Calls : com.android.build.api.instrumentation.AsmClassVisitorFactory<com.android.build.api.instrumentation.InstrumentationParameters.None> {
    override fun createClassVisitor(classContext: com.android.build.api.instrumentation.ClassContext, nextClassVisitor: org.objectweb.asm.ClassVisitor) =
        object : org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?) =
                object : org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (opcode == org.objectweb.asm.Opcodes.INVOKEVIRTUAL && owner == "java/nio/ByteBuffer" && (name == "get" || name == "put") && descriptor == "(I[BII)Ljava/nio/ByteBuffer;")
                            super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "com/kalotrapezis/drive/Nio29", name, "(Ljava/nio/ByteBuffer;I[BII)Ljava/nio/ByteBuffer;", false)
                        else if (opcode == org.objectweb.asm.Opcodes.INVOKEINTERFACE && owner == "java/util/stream/Stream" && name == "toList" && descriptor == "()Ljava/util/List;")
                            super.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, "com/kalotrapezis/drive/Nio29", name, "(Ljava/util/stream/Stream;)Ljava/util/List;", false)
                        else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
        }
    override fun isInstrumentable(classData: com.android.build.api.instrumentation.ClassData) = classData.className.startsWith("com.graphhopper.")
}
androidComponents {
    onVariants { v -> v.instrumentation.transformClassesWith(Nio29Calls::class.java, com.android.build.api.instrumentation.InstrumentationScope.ALL) {} }
}
