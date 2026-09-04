plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val packageBase = "org.fcitx.fcitx5.android"
val appIdBase = packageBase
// Distinct from fx2 / upstream fx builds (org.fcitx.fcitx5.android.fx) so both
// can be installed side by side on the same device.
val appIdFxSuffix = ".fx.rime"
val flavorFx = "fx"
val appLabelDefault = "@string/app_name"
val imeSettingsActivity = "$packageBase.ui.main.MainActivity"

android {
    namespace = packageBase

    defaultConfig {
        applicationId = appIdBase
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = appLabelDefault
        resValue("string", "ime_settings_activity", imeSettingsActivity)

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidnotification",
                    // fcitx5-rime addon (rime engine fused into main apk)
                    "rime"
                )
            }
        }
    }

    flavorDimensions += "brand"
    productFlavors {
        create(flavorFx) {
            dimension = "brand"
            applicationIdSuffix = appIdFxSuffix
            buildConfigField("boolean", "IS_FX_BUILD", "true")
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
        }
        debug {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
        }
    }

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

androidComponents {
    onVariants { variant ->
        if (variant.flavorName == flavorFx) {
            variant.outputs.forEach { output ->
                if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                    output.outputFileName.set(
                        output.outputFileName.get()
                            .replace("org.fcitx.fcitx5.android-", "org.fcitx.fcitx5.android.fx.rime-")
                            .replace("-fx-", "-")
                    )
                }
            }
        }
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5"
    )
    // rime 专版只保留 rime 输入法：imselector / spell / unicode 三个 fcitx5
    // 模块已不再打包，其 addon .conf 一并从 assets 排除，避免出现在
    // 附加组件设置页里。
    excludeFiles = listOf(
        "usr/share/fcitx5/addon/imselector.conf",
        "usr/share/fcitx5/addon/spell.conf",
        "usr/share/fcitx5/addon/unicode.conf"
    )
    installPrebuiltAssets = true
}

generateDataDescriptor {
    // rime-data ships its own copy of opencc data; link it to the shared one
    symlinks.put("usr/share/rime-data/opencc", "usr/share/opencc")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
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
