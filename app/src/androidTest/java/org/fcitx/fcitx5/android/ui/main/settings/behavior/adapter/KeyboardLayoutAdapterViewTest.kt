/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row actions are header views; the flow contains editable keys only. */
class KeyboardLayoutAdapterViewTest {
    @Test fun headerActionsStaySeparateFromReusedKeyCells() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_FcitxSettings_System_Light)
            val row = mutableListOf<MutableMap<String, Any?>>()
            val adapter = KeyboardLayoutAdapter(context, listOf(row), NoOpListener)
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0) as KeyboardLayoutAdapter.RowViewHolder
            val add = holder.addKeyButton
            fun bindAndCheck() {
                adapter.onBindViewHolder(holder, 0)
                assertEquals(row.size, holder.keysFlow.childCount)
                assertSame(add, holder.addKeyButton)
                assertEquals(View.VISIBLE, add.visibility)
                assertEquals(View.GONE, holder.moveUpButton.visibility)
                assertEquals(View.GONE, holder.moveDownButton.visibility)
                for (i in row.indices) {
                    val key = holder.keysFlow.getChildAt(i) as TextView
                    assertEquals(View.VISIBLE, key.visibility)
                    assertTrue(key.isEnabled)
                    assertEquals(1f, key.alpha, 0f)
                    assertNull(key.tag)
                    assertEquals(row[i]["main"], key.text.toString())
                    assertFalse(key === holder.addKeyButton || key === holder.moveUpButton || key === holder.moveDownButton)
                }
            }
            bindAndCheck()
            row.add(mutableMapOf("type" to "AlphabetKey", "main" to "q"))
            bindAndCheck()
            val first = holder.keysFlow.getChildAt(0)
            row.add(mutableMapOf("type" to "AlphabetKey", "main" to "w"))
            row.add(mutableMapOf("type" to "AlphabetKey", "main" to "e"))
            bindAndCheck()
            assertSame(first, holder.keysFlow.getChildAt(0))
            row.removeAt(0)
            bindAndCheck()
        }
    }

    @Test fun headerMoveButtonsFollowBoundariesWhenTheHolderIsRebound() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_FcitxSettings_System_Light)
            val rows = MutableList(3) { mutableListOf(mutableMapOf<String, Any?>("type" to "AlphabetKey", "main" to "a")) }
            val adapter = KeyboardLayoutAdapter(context, rows, NoOpListener)
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0) as KeyboardLayoutAdapter.RowViewHolder
            fun check(position: Int, up: Boolean, down: Boolean) {
                adapter.onBindViewHolder(holder, position)
                assertEquals(if (up) View.VISIBLE else View.GONE, holder.moveUpButton.visibility)
                assertEquals(if (down) View.VISIBLE else View.GONE, holder.moveDownButton.visibility)
                assertEquals(View.VISIBLE, holder.addKeyButton.visibility)
                assertEquals(1, holder.keysFlow.childCount)
            }
            check(0, false, true)
            check(1, true, true)
            check(2, true, false)
            rows.removeAt(2); rows.removeAt(1)
            check(0, false, false)
            rows.add(mutableListOf(mutableMapOf("type" to "AlphabetKey", "main" to "b")))
            check(0, false, true)
        }
    }

    private object NoOpListener : KeyboardLayoutAdapter.Listener {
        override fun onKeyClick(rowIndex: Int, keyIndex: Int) {}
        override fun onAddKeyClick(rowIndex: Int) {}
        override fun onDeleteRowClick(rowIndex: Int) {}
        override fun onAddRowClick() {}
        override fun onEditRowClick(rowIndex: Int) {}
        override fun onMoveRowUpClick(rowIndex: Int) {}
        override fun onMoveRowDownClick(rowIndex: Int) {}
        override fun onRowPositionChanged(from: Int, to: Int) {}
        override fun onRowDragEnded() {}
        override fun onKeyPositionChanged(rowIndex: Int, from: Int, to: Int) {}
        override fun onKeyDragEnded(rowIndex: Int) {}
        override fun onKeyMovedAcrossRows(fromRow: Int, fromIndex: Int, toRow: Int, toIndex: Int) {}
    }
}
