/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

open class NativeBaseConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val prebuiltDir = target.rootProject.projectDir.resolve("native/prebuilt")
        val abiOverride = target.buildAbiOverride
        val selectedAbis = selectBuildAbis(abiOverride, Versions.supportedABIs)
        val isBuildingBundle = requestsBundle(target.rootProject.gradle.startParameter.taskNames, target.path)
        target.extensions.configure<CommonExtension> {
            ndkVersion = target.ndkVersion
            defaultConfig.apply {
                minSdk = Versions.minSdk
                if (isBuildingBundle && abiOverride != null) ndk.abiFilters.addAll(selectedAbis)
                @Suppress("UnstableApiUsage")
                externalNativeBuild {
                    cmake {
                        arguments(
                            "-DANDROID_STL=c++_shared",
                            "-DVERSION_NAME=${Versions.baseVersionName}",
                            "-DPREBUILT_DIR=${prebuiltDir.absolutePath}"
                        )
                    }
                }
            }
            externalNativeBuild.apply {
                cmake {
                    version = target.cmakeVersion
                    path("src/main/cpp/CMakeLists.txt")
                }
            }
            // split apks should be disabled when building bundle
            // https://issuetracker.google.com/issues/402800800
            if (!isBuildingBundle) {
                splits.abi {
                    isEnable = true
                    isUniversalApk = false
                    reset()
                    selectedAbis.forEach {
                        include(it)
                    }
                }
            }
        }
        registerCleanCxxTask(target)
    }

    private fun registerCleanCxxTask(project: Project) {
        project.tasks.register<Delete>("cleanCxxIntermediates") {
            delete(project.file(".cxx"))
        }.also {
            project.cleanTask.dependsOn(it)
        }
    }

}
