/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class ShareReceiveDirectoryMemoryTest {
    @Test fun rememberingTheImportRootKeepsTheEmptyPathInsteadOfTheDefaultSubfolder() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val prefs = AppPrefs.getInstance().internal
        val remembered = prefs.lastShareReceiveDirectoryRemembered.getValue()
        val previous = prefs.lastShareReceiveDirectory.getValue()
        try {
            val picker = ShareReceiveDirectoryPicker(controller.get())
            prefs.lastShareReceiveDirectoryRemembered.setValue(false)
            assertEquals("shared", picker.lastDirectoryOr("shared"))
            picker.rememberDirectory("")
            assertEquals("", picker.lastDirectoryOr("shared"))
            picker.rememberDirectory("layouts")
            assertEquals("layouts", picker.lastDirectoryOr("shared"))
        } finally {
            prefs.lastShareReceiveDirectory.setValue(previous)
            prefs.lastShareReceiveDirectoryRemembered.setValue(remembered)
            controller.pause().stop().destroy()
        }
    }
}
