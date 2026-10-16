plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.fcitx.directrime"
    compileSdk = 36
    ndkVersion = providers.gradleProperty("ndkVersion").getOrElse("28.0.13004108")
    defaultConfig {
        applicationId = "io.github.sandyyur.rime.direct"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-experimental"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += providers.gradleProperty("buildABI").getOrElse("arm64-v8a").split(",") }
        externalNativeBuild.cmake {
            arguments("-DANDROID_STL=c++_shared")
        }
    }
    externalNativeBuild.cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = providers.gradleProperty("cmakeVersion").getOrElse("3.31.6")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
    sourceSets.getByName("main").assets.directories.add(layout.buildDirectory.dir("rime-assets").get().asFile.path)
}

// Reuse exactly the parent's pinned data and patched prebuilt; never fetch a new engine.
val prepareRimeAssets = tasks.register<Sync>("prepareRimeAssets") {
    into(layout.buildDirectory.dir("rime-assets/shared"))
    from(rootProject.file("plugin/rime/src/main/cpp")) { include("default.yaml") }
    from(rootProject.file("plugin/rime/src/main/cpp/rime-prelude")) {
        include("key_bindings.yaml", "punctuation.yaml", "symbols.yaml")
    }
    into("opencc") { from(rootProject.file("lib/fcitx5/src/main/cpp/prebuilt/opencc/data")) }
}
tasks.named("preBuild") { dependsOn(prepareRimeAssets) }

dependencies {
    implementation(libs.kotlinx.coroutines)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
