/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.DraggableFlowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard the action-to-key reuse path: a disabled up-arrow must never disable a key. */
class KeyboardLayoutAdapterViewTest {
    @Test fun reusedActionChipsResetEnabledStateAndDataTags() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_FcitxSettings_System_Light)
            val row = mutableListOf<MutableMap<String, Any?>>()
            val adapter = KeyboardLayoutAdapter(context, listOf(row), NoOpListener)
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0) as KeyboardLayoutAdapter.RowViewHolder
            adapter.onBindViewHolder(holder, 0)
            val disabledUp = holder.keysFlow.getChildAt(1)
            assertFalse(disabledUp.isEnabled)

            row.add(mutableMapOf("type" to "AlphabetKey", "main" to "q"))
            adapter.onBindViewHolder(holder, 0)
            val add = holder.keysFlow.getChildAt(1)
            assertSame(disabledUp, add)
            assertTrue(add.isEnabled)
            assertEquals(1f, add.alpha, 0f)
            assertEquals(DraggableFlowLayout.TAG_NOT_DRAGGABLE, add.tag)

            // The next up-arrow is reused as an ordinary key after two keys are inserted.
            val nextUp = holder.keysFlow.getChildAt(2)
            row.add(mutableMapOf("type" to "AlphabetKey", "main" to "w"))
            row.add(mutableMapOf("type" to "AlphabetKey", "main" to "e"))
            adapter.onBindViewHolder(holder, 0)
            val key = holder.keysFlow.getChildAt(2) as TextView
            assertSame(nextUp, key)
            assertTrue(key.isEnabled)
            assertEquals(1f, key.alpha, 0f)
            assertNull(key.tag)
            assertEquals("e", key.text.toString())
            assertEquals(5, holder.keysFlow.childCount)
            assertEquals(DraggableFlowLayout.TAG_NOT_DRAGGABLE, holder.keysFlow.getChildAt(3).tag)
            assertEquals(DraggableFlowLayout.TAG_NOT_DRAGGABLE, holder.keysFlow.getChildAt(4).tag)
        }
    }

    private object NoOpListener : KeyboardLayoutAdapter.Listener {
        override fun onKeyClick(rowIndex: Int, keyIndex: Int) {}
        override fun onAddKeyClick(rowIndex: Int) {}
        override fun onDeleteRowClick(rowIndex: Int) {}
        override fun onAddRowClick() {}
        override fun onEditRowClick(rowIndex: Int) {}
        override fun onMoveRowUpClick(rowIndex: Int) {}
        override fun onRowPositionChanged(from: Int, to: Int) {}
        override fun onRowDragEnded() {}
        override fun onKeyPositionChanged(rowIndex: Int, from: Int, to: Int) {}
        override fun onKeyDragEnded(rowIndex: Int) {}
        override fun onKeyMovedAcrossRows(fromRow: Int, fromIndex: Int, toRow: Int, toIndex: Int) {}
    }
}
