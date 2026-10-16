pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fcitx5-android"

include(":lib:common")
include(":lib:fcitx5")
include(":codegen")
include(":app")

// Deliberately a separate APK/process/data sandbox: librime has process-global state.
// The shipping IME remains unchanged unless the migration gates have been met.
if (providers.gradleProperty("directRimePrototype").orNull == "true") {
    include(":direct-rime")
}
