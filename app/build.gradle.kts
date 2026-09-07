import java.util.Properties as LocalProperties
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.detekt) // COGNITIVE_PLAN 0.6
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
        // targetSdk 34: Android 14+ guards handled in code.
        // minSdk 29: HarmonyOS 2.0 devices (e.g. Huawei AGS6-W09) report API 29.
        // No backward compat below it is claimed or needed.
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "0.2.1"

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
        disable += "ExpiredTargetSdkVersion"
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

// COGNITIVE_PLAN 0.6: static analysis. buildUponDefaultConfig + the focused
// config/detekt/detekt.yml; the checked-in baseline absorbs legacy findings
// so every NEW violation fails the build. detekt-formatting = ktlint rules.
detekt {
    buildUponDefaultConfig = true
    parallel = true
    autoCorrect = true // P5.1: formatting rules re-enabled; ktlint fixes apply in place
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
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

    // 0.6: ktlint-backed formatting rules inside the detekt run.
    detektPlugins(libs.detekt.formatting)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    // P1.1: in-process gRPC transport for Salute ASR/TTS client suites —
    // no real network, no credentials. Same gRPC version as production deps.
    testImplementation(libs.grpc.inprocess)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
}

// ===== P2.1: live-service integration tier (REMEDIATION_PLAN Phase 2) ==========
// Credentials live in a GITIGNORED `local.secrets.properties` at the repo root
// (copy local.secrets.properties.example) or in JARVIS_* environment variables.
// The build NEVER reads credential values. Both tasks below are ALWAYS
// registered and self-skip at RUNTIME (configuration-cache safe: script
// closures in onlyIf{} are not serializable — the previous onlyIf gate failed
// under org.gradle.configuration-cache=true):
//   - :app:integrationTest runs the integration tests, which self-skip via
//     JUnit assumptions in integration/LiveSecrets.kt when creds are absent;
//   - :app:recordSaluteFixtures' main() exits 0 with a logged skip message.
// The normal gate (testDebugUnitTest + assembleDebug) never invokes these
// tasks, so CI stays green by construction.

afterEvaluate {
    // AGP owns the unit-test tasks; they exist (and are configured) by now.
    val unitTestTask = tasks.named("testDebugUnitTest", Test::class.java)

    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "Live Sber service smoke tests (GigaChat + Salute ASR/TTS), local-only. " +
            "Requires local.secrets.properties (see local.secrets.properties.example) or JARVIS_* env vars; " +
            "tests skip with logged reasons when credentials are absent."
        // Reuse the unit-test variant's compiled classes and runtime classpath;
        // inheriting testDebugUnitTest's upstream dependencies runs the COMPILERS
        // only, never the whole unit suite.
        dependsOn(unitTestTask.map { it.dependsOn })
        testClassesDirs = unitTestTask.get().testClassesDirs
        classpath = unitTestTask.get().classpath
        include("com/jarvis/assistant/integration/**")
        testLogging {
            events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
        }
    }

    tasks.register<JavaExec>("recordSaluteFixtures") {
        group = "verification"
        description = "Records SANITIZED Salute ASR/TTS fixtures from the live services into " +
            "app/src/test/resources/recorded/ (local-only; requires credentials; main() exits 0 " +
            "with a skip message when credentials are absent — no secrets, no user audio/text " +
            "ever reaches the fixtures)."
        dependsOn(unitTestTask.map { it.dependsOn })
        classpath = unitTestTask.get().testClassesDirs + unitTestTask.get().classpath
        mainClass.set("com.jarvis.assistant.integration.RecordSaluteFixturesKt")
        args("$projectDir/src/test/resources/recorded")
    }
}
