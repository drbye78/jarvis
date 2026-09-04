import java.util.Properties as LocalProperties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 34

    val localProps = LocalProperties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(f.inputStream())
    }

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        // targetSdk 34: Android 14+ guards are handled in code (typed FGS,
        // SCHEDULE_EXACT_ALARM, RECEIVER_NOT_EXPORTED, POST_NOTIFICATIONS).
        // HarmonyOS 2.0 (API-29-based) ignores unknown permissions and
        // behavioral changes — compatibility is maintained.
        // A11: minSdk 30 aligns the build with the documented support window
        // (Android 11 / HarmonyOS 2.0 is API-30-based) — no backward compat
        // below it is claimed or needed.
        minSdk = 30
        targetSdk = 34
        versionCode = 4
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("RELEASE_STORE_FILE", "release.keystore"))
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // ExpiredTargetSdkVersion is no longer disabled — targetSdk is now 34 (current).
    }

    sourceSets {
        getByName("debug") {
            java.srcDir("build/generated/java/generateDebugProto/java")
        }
        getByName("release") {
            java.srcDir("build/generated/java/generateReleaseProto/java")
        }
        // B4: MigrationTestHelper reads the exported Room schemas from the
        // instrumentation assets — without this the three androidTest
        // migration tests failed at setup ("schema file not found"), so the
        // v2→v3 chain had no real coverage.
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.83.1"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java")
            }
            task.plugins {
                create("grpc")
            }
        }
    }
}

dependencies {
    implementation(platform(libs.kotlin.bom))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.recyclerview)
    // MUSIC lane: MediaBrowserCompat (browser lane) + MediaControllerCompat
    // (compat transport actions: repeat/shuffle/speed) in Phase 4
    implementation(libs.androidx.media)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)

    // gRPC for Sber Salute Speech (ASR + TTS streaming)
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.protobuf.java)

    // Generated gRPC code uses @javax.annotation.Generated
    compileOnly(libs.javax.annotation)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // A3: secrets are encrypted by util/KeystoreVault (AndroidKeyStore
    // AES-256-GCM, zero dependencies) — the deprecated security-crypto
    // (EncryptedSharedPreferences) library is gone.

    implementation(libs.porcupine.android)

    // Sherpa-ONNX Keyword Spotting (prebuilt AAR, fully on-device engine).
    implementation(files("libs/sherpa-onnx.aar"))


    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
}
