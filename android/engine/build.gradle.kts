// Kokoro on ONNX Runtime, in plain Kotlin. The app brings onnxruntime-android;
// the tests use the desktop onnxruntime, which has the same API, and the
// models in ../../.dev/app/models (dev/rig.sh) or KOKOMUX_MODELS.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.orgjson)
}

tasks.test {
    val models = System.getenv("KOKOMUX_MODELS") ?: rootDir.resolve("../.dev/app/models").path
    systemProperty("kokomux.models", models)
    systemProperty("kokomux.golden", rootDir.resolve("tools/golden.json").path)
    maxHeapSize = "4g"
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
