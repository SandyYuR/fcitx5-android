plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.plugin-app-convention")
    id("org.fcitx.fcitx5.android.data-descriptor")
}

android {
    namespace = "org.fcitx.fcitx5.android.plugin.text_editor"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android.plugin.text_editor"
    }

    buildFeatures {
        resValues = true
        viewBinding = true
    }

    // textmate/ lives in a separate source dir so the DataDescriptorPlugin (which scans
    // src/main/assets) doesn't replicate ~10MB of grammars/themes into the main app's dataDir.
    // The plugin reads them via its own AssetManager, so they don't belong in the shared hierarchy.
    sourceSets {
        getByName("main") {
            assets.directories += "src/main/extra-assets"
        }
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

dependencies {
    implementation(project(":lib:plugin-base"))
    implementation(project(":plugin:text-editor:language-textmate"))
    implementation(libs.sora.editor)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.recyclerview)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.kotlinx.coroutines)
    implementation(libs.material)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.timber)
}
