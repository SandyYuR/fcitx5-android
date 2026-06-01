/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.update.UpdateCheckActivity
import org.fcitx.fcitx5.android.ui.main.update.UpdateRepository
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.addCategory
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.formatDateTime
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import timber.log.Timber

class AboutFragment : PaddingPreferenceFragment() {
    private var currentVersionPreference: Preference? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.privacy_policy) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.privacyPolicyUrl)))
            }
            addPreference(
                R.string.open_source_licenses,
                R.string.licenses_of_third_party_libraries
            ) {
                navigateWithAnim(SettingsRoute.License)
            }
            addPreference(R.string.source_code, R.string.github_repo) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.githubRepo)))
            }
            addPreference(R.string.license, Const.licenseSpdxId) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.licenseUrl)))
            }
            addCategory(R.string.version) {
                isIconSpaceReserved = false
                currentVersionPreference = Preference(requireContext()).apply {
                    setTitle(R.string.current_version)
                    summary = Const.versionName
                    isSingleLineTitle = false
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        startActivity(Intent(requireContext(), UpdateCheckActivity::class.java))
                        true
                    }
                }
                currentVersionPreference?.let(::addPreference)
                addPreference(R.string.build_git_hash, BuildConfig.BUILD_GIT_HASH) {
                    val commit = BuildConfig.BUILD_GIT_HASH.substringBefore('-')
                    val uri = Uri.parse("${Const.githubRepo}/commit/${commit}")
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                addPreference(R.string.build_time, formatDateTime(BuildConfig.BUILD_TIME))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val latest = UpdateRepository.fetchLatestRelease(requireContext())
                val versionFallback = latest.assets.firstOrNull()?.name ?: latest.releaseName
                val summary = if (UpdateRepository.isNewerVersion(
                        latest.tagName,
                        Const.versionName,
                        latest.publishedAt,
                        BuildConfig.BUILD_TIME,
                        versionFallback
                    )) {
                    // Extract version string from asset name (e.g. "0.1.2-352-g6f8634b9" or "latest-352-g6f8634b9")
                    val versionFromAsset = versionFallback.let { name ->
                        Regex("""([\w.]+-\d+-g[0-9a-fA-F]+)""").find(name)?.value
                    }
                    getString(
                        R.string.about_current_version_new_available,
                        Const.versionName,
                        versionFromAsset ?: latest.tagName
                    )
                } else {
                    Const.versionName
                }
                currentVersionPreference?.summary = summary
            } catch (t: Throwable) {
                Timber.w(t, "Failed to check latest version on About page")
                currentVersionPreference?.summary = Const.versionName
            }
        }
    }
}
