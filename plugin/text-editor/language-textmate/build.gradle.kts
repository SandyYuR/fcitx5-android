/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 *
 * Vendored from sora-editor 0.23.4 (LGPL-2.1) so we can rewrite four tm4e record
 * classes that R8 9.2.x mis-desugars (the `RecordTag` synthetic superclass is
 * referenced but never emitted, producing NoClassDefFoundError at runtime).
 */
plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
}

android {
    namespace = "io.github.rosemoe.sora.langs.textmate"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    compileOnly(libs.sora.editor)

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jruby.jcodings:jcodings:1.0.58")
    implementation("org.jruby.joni:joni:2.2.1")
    implementation("org.snakeyaml:snakeyaml-engine:2.7")
    implementation("org.eclipse.jdt:org.eclipse.jdt.annotation:2.2.800")
}
