/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/**
 * Native Rime log level handed to [org.fcitx.directrime.Native.start].
 *
 * librime applies `min_log_level` in `setup()`, which runs once per process, so the developer
 * switch only takes effect after the application process restarts. Restarting the engine inside
 * the running process keeps the level that process already applied.
 */
object DirectRimeEngineLog {
    /** INFO: librime's INFO, WARNING and ERROR records. */
    const val INFO = 0

    /** ERROR: only ERROR and FATAL records, the default. */
    const val ERROR = 2

    /** Before [AppPrefs.init] (unit tests, early startup) the default ERROR level applies. */
    fun minLevel(): Int =
        if (AppPrefs.isInitialized() && AppPrefs.getInstance().internal.rimeEngineLog.getValue())
            INFO
        else
            ERROR
}
