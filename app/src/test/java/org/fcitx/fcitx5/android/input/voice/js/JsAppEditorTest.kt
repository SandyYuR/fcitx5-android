/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class JsAppEditorTest {
    private fun connection() = BaseInputConnection(View(appContext), true).apply {
        commitText("before selected after", 1)
        setSelection(7, 15)
    }

    @Test fun cancellingPreviewRestoresSelectedTextAndConfirmReplacesItOnce() {
        val ic = connection()
        val editor = JsAppEditor(ic, { true }, {})
        editor.call("setComposition", "replacement")
        assertEquals("before replacement after", ic.editable.toString())
        editor.call("clearComposition", "")
        assertEquals("before selected after", ic.editable.toString())
        ic.setSelection(7, 15)
        editor.call("setComposition", "replacement")
        editor.call("commit", "replacement")
        editor.clearPreview()
        assertEquals("before replacement after", ic.editable.toString())
    }

    @Test fun expiredSessionCannotCommitOrClearAnyEditor() {
        val ic = connection()
        var current = true
        val editor = JsAppEditor(ic, { current }, {})
        editor.call("setComposition", "temporary")
        current = false
        val snapshot = ic.editable.toString()
        assertTrue(runCatching { editor.call("commit", "late result") }.isFailure)
        editor.clearPreview()
        assertEquals(snapshot, ic.editable.toString())
    }

    @Test fun revokedPermissionPreventsSelectionReadsAndWrites() {
        val ic = connection()
        val editor = JsAppEditor(ic, { true }, { error("permission revoked") })
        assertTrue(runCatching { editor.call("getSelection", "") }.isFailure)
        assertTrue(runCatching { editor.call("setComposition", "bad") }.isFailure)
        assertEquals("before selected after", ic.editable.toString())
    }
}
