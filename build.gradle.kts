plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.detekt) apply false // COGNITIVE_PLAN 0.6
    id("org.owasp.dependencycheck") version "13.0.0"
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    suppressionFile = "${rootProject.projectDir}/.dependency-check-suppressions.xml"

    // Scan only the :app subproject's release runtime classpath
    scanProjects = listOf(":app")
    scanConfigurations = listOf("releaseRuntimeClasspath")

    // Report formats
    formats = listOf("HTML", "JSON")

    // Auto-update NVD database before scanning
    autoUpdate = true

    // Skip test configurations by default (already true, but explicit)
    skipTestGroups = true

    // NVD API configuration — set NVD_API_KEY env var for faster downloads
    // nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""
}
