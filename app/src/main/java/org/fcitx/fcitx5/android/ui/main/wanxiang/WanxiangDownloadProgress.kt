/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

/** Current-file progress only. An unknown or inconsistent length is never a percentage. */
internal object WanxiangDownloadProgress {
    fun percent(bytes: Long, total: Long): Int? {
        if (total <= 0 || bytes < 0 || bytes > total) return null
        if (bytes == total) return 100
        // Keep normal downloads on integer arithmetic (29/100 must not become
        // 28 through floating point). The fallback also handles hostile Longs.
        return if (bytes <= Long.MAX_VALUE / 100) (bytes * 100 / total).toInt()
        else java.math.BigInteger.valueOf(bytes).multiply(java.math.BigInteger.valueOf(100))
            .divide(java.math.BigInteger.valueOf(total)).toInt()
    }
}
