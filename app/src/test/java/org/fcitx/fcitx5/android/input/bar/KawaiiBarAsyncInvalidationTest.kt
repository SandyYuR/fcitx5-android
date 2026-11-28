/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar

import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import kotlinx.coroutines.Job
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class KawaiiBarAsyncInvalidationTest {
    @Test fun clipboardClearInvalidatesOutstandingPreviewAndTimeoutEvenAfterDetach() {
        val bar = KawaiiBarComponent()
        val preview = Job()
        val timeout = Job()
        ReflectionHelpers.setField(bar, "clipboardPreviewJob", preview)
        ReflectionHelpers.setField(bar, "clipboardTimeoutJob", timeout)
        ReflectionHelpers.setField(bar, "isClipboardFresh", true)
        ReflectionHelpers.setField(bar, "scopeTornDown", true)
        val listener = ReflectionHelpers.getField<ClipboardManager.OnClipboardUpdateListener>(bar,
            "onClipboardUpdateListener")
        listener.onUpdate(ClipboardEntry(text = ""))
        assertTrue(preview.isCancelled)
        assertTrue(timeout.isCancelled)
        assertFalse(ReflectionHelpers.getField<Boolean>(bar, "isClipboardFresh"))
        assertEquals(1L, ReflectionHelpers.getField<Long>(bar, "clipboardPreviewGeneration"))
        assertNull(ReflectionHelpers.getField<Job?>(bar, "clipboardPreviewJob"))
        assertNull(ReflectionHelpers.getField<Job?>(bar, "clipboardTimeoutJob"))
    }

    @Test fun evenAnEmptyResponseAfterDetachCancelsThePreviousInflationTree() {
        val bar = KawaiiBarComponent()
        val response = Job()
        val pinned = Job(response)
        val scrollable = Job(response)
        ReflectionHelpers.setField(bar, "inlineSuggestionJob", response)
        ReflectionHelpers.setField(bar, "scopeTornDown", true)
        // Public SDK stubs hide the List constructor; both API 30/35 runtimes expose it.
        val emptyResponse = ReflectionHelpers.callConstructor(InlineSuggestionsResponse::class.java,
            ReflectionHelpers.ClassParameter.from(List::class.java, emptyList<InlineSuggestion>()))
        assertTrue(emptyResponse.inlineSuggestions.isEmpty())
        assertTrue(bar.handleInlineSuggestions(emptyResponse))
        assertTrue(response.isCancelled)
        assertTrue(pinned.isCancelled)
        assertTrue(scrollable.isCancelled)
        assertEquals(1L, ReflectionHelpers.getField<Long>(bar, "inlineSuggestionGeneration"))
        assertNull(ReflectionHelpers.getField<Job?>(bar, "inlineSuggestionJob"))
    }
}
