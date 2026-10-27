/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.utils

import android.os.Build
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/** Android 6 has no charset constructor. It can safely read standard UTF-8 archives. */
internal fun compatibleZipInputStream(input: InputStream, charset: Charset? = Charsets.UTF_8): ZipInputStream =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ZipInputStream(input, charset ?: Charsets.UTF_8)
    else {
        require(charset == null || charset == Charsets.UTF_8) { "Non-UTF-8 ZIP names require Android 7 or later" }
        ZipInputStream(input)
    }
