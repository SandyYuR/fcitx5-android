plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.tabledata"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.tabledata"
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

val copyTableDicts = tasks.register<Copy>("copyTableDicts") {
    from(rootProject.file("lib/fcitx5/src/main/cpp/prebuilt/libime/table")) {
        include("db.main.dict", "wbpy.main.dict", "wbx.main.dict", "zrm.main.dict")
    }
    into(layout.projectDirectory.dir("src/main/assets/usr/share/fcitx5/table"))
}

tasks.named("generateDataDescriptor") {
    dependsOn(copyTableDicts)
}

tasks.named("clean") {
    doLast {
        projectDir.resolve("src/main/assets/usr").deleteRecursively()
    }
}

dependencies {
    implementation(project(":lib:plugin-base"))
}
