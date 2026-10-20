/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.BuildConfig

object Const {
    const val versionName = "${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_TYPE}"
    // Repository this build is made from; also the source of update checks
    // (see UpdateRepository.fetchLatestRelease) and the About page links.
    const val githubRepo = "https://github.com/SandyYuR/fcitx5-android"
    const val licenseSpdxId = "LGPL-2.1-or-later"
    const val licenseUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1"
    // Pin documentation to the APK's source: feature branches can be deleted
    // after merging, and the default branch's manual can describe a later build.
    val sourceRevision = BuildConfig.BUILD_GIT_HASH.substringBefore('-')
    val sourceUrl = "$githubRepo/tree/$sourceRevision"
    val directRimeGuideUrl = "$githubRepo/blob/$sourceRevision/docs/manual/DIRECT_RIME_USER_GUIDE_zh-CN.md"
    val directRimeMaintainerUrl = "$githubRepo/blob/$sourceRevision/docs/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md"
    const val releasesUrl = "$githubRepo/releases"
    val privacyPolicyUrl = "$directRimeGuideUrl#隐私与数据"
    val faqUrl = "$directRimeGuideUrl#常见问题"
}
