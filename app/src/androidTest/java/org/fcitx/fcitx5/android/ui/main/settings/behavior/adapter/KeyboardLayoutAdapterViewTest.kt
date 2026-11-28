/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.adapter

import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.update.UpdateMirrorListActivity
import splitties.dimensions.dp
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

    @Test fun attachedCrossRowDropCanReachTheLastKeyAndWrappedRowEnd() = withWindow { host ->
        val context = ContextThemeWrapper(host.context, R.style.Theme_FcitxSettings_System_Light)
        val method = KeyboardLayoutAdapter::class.java.getDeclaredMethod("computeInsertIndexForRow",
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
        for ((count, widthDp) in listOf(3 to 1000, 8 to 360)) {
            val width = context.dp(widthDp)
            val height = context.dp(2000)
            val rows = listOf(mutableListOf(mutableMapOf<String, Any?>("type" to "AlphabetKey", "main" to "source")),
                MutableList(count) { mutableMapOf<String, Any?>("type" to "AlphabetKey", "main" to "k$it") })
            val originalRows = rows.map { row -> row.map { it.toMap() } }
            val adapter = KeyboardLayoutAdapter(context, rows, NoOpListener)
            val recycler = androidx.recyclerview.widget.RecyclerView(context).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
                itemAnimator = null
                this.adapter = adapter
            }
            adapter.setupRowDragTrigger(recycler, null)
            host.addView(recycler, FrameLayout.LayoutParams(width, height))
            try {
                assertTrue(recycler.isAttachedToWindow)
                recycler.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                recycler.layout(0, 0, width, height)
                val holder = recycler.findViewHolderForAdapterPosition(1) as KeyboardLayoutAdapter.RowViewHolder
                val flow = holder.keysFlow
                assertTrue(flow.isAttachedToWindow)
                assertEquals(count, flow.childCount)
                val first = flow.getChildAt(0)
                val last = flow.getChildAt(count - 1)
                assertTrue(last.isAttachedToWindow)
                assertTrue(last.width > 0 && last.height > 0)
                if (count == 3) assertEquals("The wide fixture must stay on one visual line", first.top, last.top)
                else assertTrue("The narrow fixture must wrap", last.top > first.top)

                val flowLocation = IntArray(2).also(flow::getLocationOnScreen)
                val lastLocation = IntArray(2).also(last::getLocationOnScreen)
                assertEquals(flowLocation[0] + last.x, lastLocation[0].toFloat(), 1f)
                assertEquals(flowLocation[1] + last.y, lastLocation[1].toFloat(), 1f)
                fun indexAt(localX: Float, localY: Float): Int = method.invoke(adapter, 1,
                    flowLocation[0] + localX, flowLocation[1] + localY, flow.width) as Int

                assertEquals(count - 1, indexAt(last.x + last.width / 2f - 1f, last.y + last.height / 2f))
                assertEquals(count, indexAt(last.x + last.width + 1f, last.y + last.height / 2f))
                if (count == 8) {
                    val firstLineEndIndex = (0 until count).last { flow.getChildAt(it).top == first.top }
                    assertTrue(firstLineEndIndex < count - 1)
                    val firstLineEnd = flow.getChildAt(firstLineEndIndex)
                    assertEquals(firstLineEndIndex + 1, indexAt(firstLineEnd.x + firstLineEnd.width + 1f,
                        firstLineEnd.y + firstLineEnd.height / 2f))
                }
                assertEquals("Drop targeting must not mutate the in-memory layout", originalRows, rows)
            } finally {
                host.removeView(recycler)
                recycler.adapter = null
                assertFalse(recycler.isAttachedToWindow)
            }
        }
    }

    private fun withWindow(block: (FrameLayout) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Reuse the read-only window host used by CandidateRenderingTest; no editor or save action runs.
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, UpdateMirrorListActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        )
        try {
            instrumentation.waitForIdleSync()
            onMain {
                val host = FrameLayout(activity)
                activity.setContentView(host)
                try {
                    assertTrue(host.isAttachedToWindow)
                    block(host)
                } finally {
                    host.removeAllViews()
                }
            }
        } finally {
            onMain { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure = error }
        }
        failure?.let { throw it }
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
