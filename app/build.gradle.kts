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
        // targetSdk 30 on purpose: appliance profile for the MatePad (Android 10/11).
        // Android 14+ guards are handled in code (typed FGS, runtime checks).
        minSdk = 24
        targetSdk = 30
        versionCode = 3
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }

        buildConfigField("String", "PICOVOICE_KEY", "\"${localProps.getProperty("PICOVOICE_KEY", "")}\"")
        buildConfigField("String", "SALUTE_CLIENT_ID", "\"${localProps.getProperty("SALUTE_CLIENT_ID", "")}\"")
        buildConfigField("String", "SALUTE_CLIENT_SECRET", "\"${localProps.getProperty("SALUTE_CLIENT_SECRET", "")}\"")
        buildConfigField("String", "GIGACHAT_CLIENT_ID", "\"${localProps.getProperty("GIGACHAT_CLIENT_ID", "")}\"")
        buildConfigField("String", "GIGACHAT_CLIENT_SECRET", "\"${localProps.getProperty("GIGACHAT_CLIENT_SECRET", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
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

    implementation(libs.security.crypto)

    implementation(libs.porcupine.android)


    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
}
