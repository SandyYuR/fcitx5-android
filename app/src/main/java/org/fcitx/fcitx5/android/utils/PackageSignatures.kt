/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.content.pm.PackageManager
import timber.log.Timber

/**
 * Signature comparison that works on every supported API level.
 *
 * The two call sites (plugin IPC admission and plugin data loading) previously read
 * `getPackageInfo(..., GET_SIGNING_CERTIFICATES).signingInfo.apkContentsSigners`. Both the flag and
 * `signingInfo` are API 28, and minSdk here is 23: below 28 `signingInfo` is always null, so the
 * check returned false for every caller and self-signed plugins were silently rejected on
 * Android 6.0 through 8.1 (see C28).
 *
 * [PackageManager.checkSignatures] has existed since API 1 and compares the same certificate sets,
 * including the v3 rotation history, so one implementation covers all levels.
 */
object PackageSignatures {

    /**
     * True when both packages are signed by the same certificate(s).
     *
     * Returns false when either package is missing or the platform cannot answer.
     */
    fun haveSameSignature(
        packageManager: PackageManager,
        packageName: String,
        otherPackageName: String
    ): Boolean {
        if (packageName == otherPackageName) return true
        return try {
            packageManager.checkSignatures(packageName, otherPackageName) ==
                PackageManager.SIGNATURE_MATCH
        } catch (e: Exception) {
            Timber.w(e, "Failed to compare signatures of $packageName and $otherPackageName")
            false
        }
    }
}
