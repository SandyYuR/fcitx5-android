plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val packageBase = "org.fcitx.fcitx5.android"
val flavorFx = "fx"
val imeSettingsActivity = "$packageBase.ui.main.MainActivity"
val inputDiagnostics = providers.gradleProperty("inputDiagnostics").orNull == "true"

android {
    namespace = packageBase

    defaultConfig {
        applicationId = "io.github.sandyyur.rime.direct"
        buildConfigField("boolean", "INPUT_DIAGNOSTICS", inputDiagnostics.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "@string/direct_rime_app_name"
        resValue("string", "ime_settings_activity", imeSettingsActivity)

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                arguments("-DINPUT_DIAGNOSTICS=${if (inputDiagnostics) "ON" else "OFF"}")
                targets("direct-rime")
            }
        }
    }

    flavorDimensions += "brand"
    productFlavors {
        create(flavorFx) {
            dimension = "brand"
            buildConfigField("boolean", "IS_FX_BUILD", "true")
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
        prefab = false
    }
    externalNativeBuild.cmake.path = file("../direct-rime/src/main/cpp/CMakeLists.txt")

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "中州鹅")
            proguardFile("proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ""
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "中州鹅")
        }
    }


    sourceSets.getByName("main").kotlin.srcDir("../direct-rime/src/engine/java")
    sourceSets.getByName("androidTest").assets.srcDir("../direct-rime/src/main/assets")

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    testOptions {
        unitTests {
            // Let JVM unit tests exercise code that logs via android.util.Log: the stubbed
            // android.jar otherwise throws "Stub!" on every call. Tests here cover pure
            // logic (parsers, validators, path sanitizing), not framework behavior.
            isReturnDefaultValues = true
        }
    }
}

run {
    val directAssets = layout.buildDirectory.dir("generated/direct-assets")
    val prepareDirectRimeAssets = tasks.register<Sync>("prepareDirectRimeAssets") {
        into(directAssets)
        from("src/main/assets") { exclude("usr/**", "descriptor.json") }
        into("usr/share/rime-data") {
            from(rootProject.file("native/rime-data"))
            from(rootProject.file("native/rime-prelude")) { include("key_bindings.yaml", "punctuation.yaml", "symbols.yaml") }
        }
        into("usr/share/opencc") { from(rootProject.file("native/prebuilt/opencc/data")) }
    }
    android.sourceSets.getByName("main").assets.setSrcDirs(listOf(directAssets))
    tasks.named<DataDescriptorPlugin.DataDescriptorTask>("generateDataDescriptor") {
        dependsOn(prepareDirectRimeAssets)
        inputDir.set(directAssets)
        outputFile.set(directAssets.map { it.file("descriptor.json") })
    }
}

generateDataDescriptor {
    // rime-data ships its own copy of opencc data; link it to the shared one
    symlinks.put("usr/share/rime-data/opencc", "usr/share/opencc")
    // 内置布局/主题/图标主题/符号面板数据只随 APK 分发、由 BundledPresets 在运行时解包到
    // 外部存储，不参与 dataDir 资源同步（避免占用 data 目录且保持清单驱动）。
    // 符号 catalog 由 SymbolCatalogs 直接从 assets 读取，不落盘到 data 目录。
    excludes.addAll(
        listOf(
            "bundled",
            "bundled/keyboard_layouts",
            "bundled/keyboard_layouts/PopupPreset.json",
            "bundled/themes",
            "bundled/icon_themes",
            "bundled/symbols",
            "bundled/symbols/symbols.json",
            "bundled/symbols/emoji.json",
            "bundled/symbols/kaomoji.json"
        )
    )
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.androidx.recyclerview)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.tracing.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)
    implementation(libs.xz)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation(libs.pictureselector)
    implementation(libs.androidsvg)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}
