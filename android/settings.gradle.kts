// kokomux for Android: the app (Compose, Chaquopy, Media3) and the engine
// (Kokoro on ONNX Runtime, plain Kotlin so it's tested on the build machine).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "kokomux"
include(":app", ":engine")
