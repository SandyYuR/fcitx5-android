plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.pinyinlm"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.pinyinlm"
    }

    buildFeatures {
        resValues = true
    }

    buildTypes {
        release {
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }
}

val copyPinyinLmAssets = tasks.register<Copy>("copyPinyinLmAssets") {
    from(rootProject.file("lib/fcitx5/src/main/cpp/prebuilt/libime/data")) {
        include("zh_CN.lm", "zh_CN.lm.predict")
    }
    into(layout.projectDirectory.dir("src/main/assets/usr/share/libime"))
}

tasks.named("generateDataDescriptor") {
    dependsOn(copyPinyinLmAssets)
}

tasks.named("clean") {
    doLast {
        projectDir.resolve("src/main/assets/usr").deleteRecursively()
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
}
