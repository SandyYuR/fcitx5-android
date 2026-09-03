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
include(":lib:fcitx5-lua")
include(":lib:libime")
include(":lib:fcitx5-chinese-addons")
include(":codegen")
include(":app")
include(":lib:plugin-base")
include(":plugin:rime")
include(":plugin:pinyin-lm")
include(":plugin:table-data")
