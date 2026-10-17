/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

/** Lifecycle belongs to the daemon; the keyboard and settings only see FcitxAPI. */
interface InputEngine : FcitxAPI, FcitxLifecycleOwner {
    fun start()
    fun stop()
}
