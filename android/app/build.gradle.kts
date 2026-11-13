// The kokomux app: one screen for now (text, voice, Play), with misaki in
// Python (Chaquopy) and Kokoro in Kotlin (:engine). Sideloaded, arm64 only.
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "kokomux.app"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "kokomux.app"
        minSdk = 36                       // Android 16, what the S26 Ultra shipped with
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // phonemizer loads espeak-ng by its path, so the library is kept as a file.
    packaging { jniLibs { useLegacyPackaging = true } }
}

// The build machine's Python 3.10, which Chaquopy needs to install packages.
val hostPython = System.getenv("KOKOMUX_BUILD_PYTHON") ?: "python3.10"
val wheels = layout.buildDirectory.dir("wheels")

// Chaquopy installs wheels only, and docopt (num2words' command line, which
// misaki never runs, but pip wants installed) is only a source package: build
// its wheel here, from the release on PyPI, checked against its hash.
val docoptWheel = tasks.register<Exec>("docoptWheel") {
    val req = temporaryDir.resolve("docopt.txt")
    outputs.dir(wheels)
    doFirst {
        req.writeText("docopt==0.6.2 --hash=sha256:49b3a825280bd66b3aa83585ef59c4a8c82f2c8a522dbe754a8bc8d08c85c491\n")
    }
    commandLine(hostPython, "-m", "pip", "wheel", "--quiet", "--no-deps", "--require-hashes",
        "-r", req.path, "-w", wheels.get().asFile.path)
}
tasks.matching { it.name.matches(Regex("install.*PythonRequirements")) }.configureEach { dependsOn(docoptWheel) }

chaquopy {
    defaultConfig {
        version = "3.10"                  // the Python Chaquopy has spaCy 3.8 for
        buildPython(hostPython)
        pip {
            options("--find-links", wheels.get().asFile.path)
            install("-r", "requirements.txt")
        }
        // These find their data files through __file__, so they're unpacked on first use.
        extractPackages("spacy", "thinc", "en_core_web_sm", "misaki", "num2words", "phonemizer")
    }
    sourceSets {
        getByName("main") {
            srcDir("../../src")           // engine.py and words.py, exactly as Termux runs them
            include("**/*.py")
        }
    }
}

/**
 * espeak-ng's data for English (its sound tables, the English dictionary and
 * the language list), taken from the espeakng-loader wheel the container
 * installs, so the phone's espeak reads words exactly as the container's does.
 */
abstract class EspeakData : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private fun digest(file: File) = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun keep(path: String) = path in setOf("phontab", "phonindex", "phondata", "phondata-manifest",
        "intonations", "en_dict") || path.startsWith("lang/")

    @TaskAction
    fun extract() {
        val wheel = temporaryDir.resolve("espeakng_loader.whl")
        if (!wheel.exists() || digest(wheel) != sha256.get()) {
            URI(url.get()).toURL().openStream().use { input -> wheel.outputStream().use { input.copyTo(it) } }
        }
        check(digest(wheel) == sha256.get()) { "espeakng-loader wheel: checksum doesn't match" }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        ZipFile(wheel).use { zip ->
            for (entry in zip.entries()) {
                val path = entry.name.removePrefix("espeakng_loader/espeak-ng-data/")
                if (entry.isDirectory || path == entry.name || !keep(path)) continue
                val dest = out.resolve("espeak-ng-data/$path")
                dest.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}

val espeakData = tasks.register<EspeakData>("espeakData") {
    url.set("https://files.pythonhosted.org/packages/de/1e/25ec5ab07528c0fbb215a61800a38eca05c8a99445515a02d7fa5debcb32/" +
        "espeakng_loader-0.2.4-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl")
    sha256.set("08721baf27d13d461f6be6eed9a65277e70d68234ff484fd8b9897b222cdcb6d")
    outputDir.set(layout.buildDirectory.dir("generated/espeak/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(espeakData, EspeakData::outputDir)
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.onnxruntime.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.coroutines.android)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.junit)
}
