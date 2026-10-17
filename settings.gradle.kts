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
include(":codegen")
include(":app")

// Accept the former opt-in flag for existing build commands, but never silently
// turn an explicitly requested legacy build into a direct APK.
providers.gradleProperty("directRimeFrontend").orNull?.let {
    require(it == "true") {
        "This branch only builds Direct Rime. Use fx-rime-only@a714feff for the historical Fcitx backend."
    }
}

// Deliberately a separate APK/process/data sandbox: librime has process-global state.
// This optional diagnostic app is not the full keyboard product.
if (providers.gradleProperty("directRimePrototype").orNull == "true") {
    include(":direct-rime")
}
