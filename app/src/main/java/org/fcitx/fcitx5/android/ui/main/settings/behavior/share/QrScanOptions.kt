/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.share

import com.journeyapps.barcodescanner.ScanOptions

object QrScanOptions {
    fun forPrompt(prompt: String): ScanOptions = ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt(prompt)
        setBeepEnabled(false)
        setOrientationLocked(true)
        setCaptureActivity(QrScanCaptureActivity::class.java)
    }
}
