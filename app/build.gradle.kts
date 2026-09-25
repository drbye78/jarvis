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
    compileSdk = 36

    val localProps = LocalProperties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(f.inputStream())
    }

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        // targetSdk 36: the D9 end state (Play requires 36 from Aug 31 2026). The
        // 35 opt-outs are gone here, so the unconditional edge-to-edge adoption in
        // ui/EdgeToEdge.kt is now the only thing keeping the insets correct — there
        // is no `windowOptOutEdgeToEdgeEnforcement` to fall back on.
        // minSdk 29: HarmonyOS 2.0 devices (e.g. Huawei AGS6-W09) report API 29.
        // No backward compat below it is claimed or needed.
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        // ABI trim (owner decision): arm64-v8a = the target Huawei tablet,
        // x86_64 = the nightly gradle-managed emulator, so
        // `connectedDebugAndroidTest` keeps working. The 32-bit ABIs ship no
        // device we support.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Release signing is OPTIONAL (audit P1-B#2). The keystore exists only on the
    // maintainer's machine; configuring it unconditionally produced a
    // signing config with EMPTY passwords, so `:app:assembleRelease` could never
    // package anywhere else. Now the config is created only when local.properties
    // supplies a real, existing keystore plus all three secret fields; otherwise
    // the release build is unsigned — R8/proguard still run, which is exactly what
    // CI needs to validate the shrinker and the keep rules.
    val releaseKeystoreFile = localProps.getProperty("RELEASE_STORE_FILE", "")
        .takeIf { it.isNotBlank() }
        ?.let { file(it) }
        ?.takeIf { it.isFile }
    val releaseSigningReady = releaseKeystoreFile != null &&
        listOf("RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
            .all { localProps.getProperty(it, "").isNotBlank() }

    val releaseSigningConfig = if (releaseSigningReady) {
        signingConfigs.create("release") {
            storeFile = releaseKeystoreFile
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD", "")
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Null in CI / on fresh clones → AGP emits app-release-unsigned.apk.
            releaseSigningConfig?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    // The `lint { disable += "ExpiredTargetSdkVersion" }` suppression is gone:
    // it existed only while targetSdk trailed Play's requirement, and at 36 the
    // check has nothing to report. CI's lint job was already advisory
    // (`continue-on-error: true`).

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

// Memory budget (see gradle.properties): Gradle's Test task passes no -Xmx by
// default, so each forked test JVM would take its own ergonomic max — ~1.6 GiB
// on this 6.8 GiB host. Pin every Test task (testDebugUnitTest, integrationTest,
// future variants) to an explicit heap.
//
// maxParallelForks = 2, not 1: the suite is 1207 tests and is wall-clock-bound,
// NOT memory-bound. Measured on this host with a 384m fork heap (identical
// 1207/0/0 results every run, 3 consecutive runs):
//     1 fork  → 85s      2 forks → 47s      4 forks → 45s
// Two forks therefore halve the suite at zero correctness cost; four adds
// nothing while doubling peak fork memory, so 2 is the sweet spot. Peak host
// usage during a 2-fork run stayed ~3.8 GB, well inside budget.
//
// This is ALSO capped by org.gradle.workers.max=2 (gradle.properties): do not
// raise maxParallelForks beyond 2 without raising max-workers, or the extra
// forks silently do nothing.
//
// NOTE: several tests are real-time budgeted (bounded waits on latches/polling,
// e.g. the wedged-engine release test). Parallel forks run independent test
// classes in separate JVMs, so this is safe — but re-run the suite a few times
// after touching the fork count.
tasks.withType<Test>().configureEach {
    maxHeapSize = "384m"
    maxParallelForks = 2
}

// COGNITIVE_PLAN 0.6: static analysis. buildUponDefaultConfig + the focused
// config/detekt/detekt.yml; the checked-in baseline absorbs legacy findings
// so every NEW violation fails the build. detekt-formatting = ktlint rules.
//
// audit P1-B#1 / decision 5: autoCorrect MUST stay false. With it on, the CI
// gate (`:app:detekt`) silently REWROTE fixable ktlint violations and passed —
// a cosmetic gate that never reported the formatting debt it was supposed to
// police. Corrections are now reported as failures; a one-off local pass
// (flip true → run → flip back) is the documented way to clear a backlog.
// config/detekt/detekt.yml's `formatting.autoCorrect` is false for the same
// reason (rule-set level flag wins for the ktlint rules).
detekt {
    buildUponDefaultConfig = true
    parallel = true
    autoCorrect = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.9"
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

    // Yandex MapKit (GEO lane): geocoding + masstransit routing. The Key is a
    // MapKit API key (separate SecretVault slot), never the Cloud API key.
    implementation(libs.mapkit) {
        // -full is MANDATORY: -lite ships 0 search + 0 masstransit classes.
        // GMS is NOT embedded (0 gms/play entries in classes.jar); only 8 classes in
        // runtime/sensors/internal + runtime/attestation_storage/internal reference it,
        // and ZERO in search/ or transport/ — so this keeps the GMS-free Huawei target working.
        exclude(group = "com.google.android.gms", module = "play-services-location")
        exclude(group = "com.google.android.play", module = "integrity")
    }

    // gRPC for Sber Salute Speech (ASR + TTS streaming)
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    implementation(libs.protobuf.java)

    // No javax.annotation dependency: grpc-java >= 1.74.0 generates stubs with
    // @generated=omit (only @io.grpc.stub.annotations.GrpcGenerated), so the
    // old @javax.annotation.Generated compileOnly shim is dead.

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
// tasks, but it DOES share src/test with the live smoke classes, so it has to
// exclude them explicitly — the classpath, not the task name, decides what the
// gate runs. See the unitTestTask.configure call below.

afterEvaluate {
    // AGP owns the unit-test tasks; they exist (and are configured) by now.
    val unitTestTask = tasks.named("testDebugUnitTest", Test::class.java)

    // The live tier lives in src/test, so it is compiled into the gate's
    // testClassesDirs and collected by testDebugUnitTest. integration/LiveSecrets
    // self-skips only when creds are ABSENT; with creds present (a gitignored
    // local.secrets.properties or JARVIS_* env vars) the live smoke tests ran
    // inside the gate and failed on the owner's machine on billing errors,
    // turning "green suite" into a red one that had nothing to do with the
    // change at hand. Exclude them by their shared naming convention, which
    // also keeps the offline RecordedFixtureMappingTest in the gate. The
    // dedicated :app:integrationTest task (include() below) remains the only
    // place the live tier runs.
    unitTestTask.configure {
        exclude("**/*LiveSmokeTest*")
    }

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
