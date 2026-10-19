/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.navigateWithAnim

class AboutFragment : PaddingPreferenceFragment() {
    private fun open(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.direct_rime_app_name, R.string.direct_rime_about_description)
            addPreference(R.string.direct_rime_about_engine, R.string.direct_rime_about_engine_summary) {
                open(Const.directRimeMaintainerUrl)
            }
            addPreference(R.string.direct_rime_about_guide, R.string.direct_rime_about_guide_summary) {
                open(Const.directRimeGuideUrl)
            }
            addPreference(R.string.direct_rime_about_builds, R.string.direct_rime_about_builds_summary) {
                open(Const.directRimeBuildsUrl)
            }
            addCategory(R.string.version) {
                addPreference(R.string.current_version, Const.versionName)
                addPreference(R.string.direct_rime_about_package, BuildConfig.APPLICATION_ID)
                addPreference(R.string.build_git_hash, BuildConfig.BUILD_GIT_HASH) {
                    open("${Const.githubRepo}/commit/${BuildConfig.BUILD_GIT_HASH.substringBefore('-')}")
                }
                addPreference(R.string.build_time, formatDateTime(BuildConfig.BUILD_TIME))
            }
            addCategory(R.string.source_code) {
                addPreference(R.string.source_code, R.string.github_repo) {
                    open("${Const.githubRepo}/tree/${Const.directRimeBranch}")
                }
                addPreference(R.string.privacy_policy, R.string.direct_rime_about_privacy_summary) {
                    open(Const.privacyPolicyUrl)
                }
                addPreference(R.string.license, Const.licenseSpdxId) { open(Const.licenseUrl) }
                addPreference(R.string.open_source_licenses, R.string.licenses_of_third_party_libraries) {
                    navigateWithAnim(SettingsRoute.License)
                }
                addPreference(R.string.direct_rime_about_credits, R.string.direct_rime_about_credits_summary) {
                    open(Const.githubRepo)
                }
            }
        }
    }
}
