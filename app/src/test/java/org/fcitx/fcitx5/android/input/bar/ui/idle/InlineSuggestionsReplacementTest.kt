/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import android.widget.inline.InlineContentView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class InlineSuggestionsReplacementTest {
    @Test fun replacingAndClearingSuggestionsReleasesAllPreviousViews() {
        val context = RuntimeEnvironment.getApplication()
        val ui = InlineSuggestionsUi(context)
        // The Context constructor exists in android-all but is hidden by public SDK stubs.
        fun contentView(): InlineContentView = ReflectionHelpers.callConstructor(InlineContentView::class.java,
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))
        val first = contentView()
        val second = contentView()
        val pinned = contentView()
        fun tracked() = ReflectionHelpers.getField<List<InlineContentView>>(ui, "scrollableContentViews")
        ui.setScrollableViews(listOf(first, null))
        assertEquals(listOf(first), tracked())
        assertNotNull(first.parent)
        ui.setScrollableViews(listOf(second))
        assertEquals(listOf(second), tracked())
        assertFalse(tracked().any { it === first })
        ui.setPinnedView(pinned)
        assertNotNull(pinned.parent)
        ui.setPinnedView(null)
        assertNull(pinned.parent)
        ui.clear()
        assertTrue(tracked().isEmpty())
        assertNull(ReflectionHelpers.getField<InlineContentView?>(ui, "pinnedContentView"))
        ui.setScrollableViews(listOf(first))
        assertEquals(listOf(first), tracked())
        ui.clear()
        assertTrue(tracked().isEmpty())
    }
}
