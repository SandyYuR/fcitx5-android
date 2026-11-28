/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.picker

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.descendants
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupActionListener
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class SymbolPanelCancelTest {
    @Test fun cancelledReleaseDismissesWithoutTriggeringACommitAndNormalReleaseStillCommits() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val committed = mutableListOf<KeyAction>()
        val popups = mutableListOf<PopupAction>()
        val panel = SymbolPanelUi(controller.get(), ThemeManager.DefaultTheme, SymbolCatalogType.Symbols,
            6, 14f, DefaultPickerPolicy(), KeyActionListener { action, _ -> committed += action },
            PopupActionListener { action ->
                popups += action
                if (action is PopupAction.TriggerAction) action.outAction = KeyAction.CommitAction("chosen")
            })
        try {
            val grid = (panel.root as ViewGroup).descendants.filterIsInstance<RecyclerView>()
                .single { it.layoutManager is GridLayoutManager }
            @Suppress("UNCHECKED_CAST")
            val adapter = grid.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
            val holder = adapter.createViewHolder(grid, adapter.getItemViewType(0))
            adapter.bindViewHolder(holder, 0)
            val key = holder.itemView as KeyView
            fun release(cancelled: Boolean) = requireNotNull(key.onGestureListener).onGesture(key,
                CustomGestureView.Event(CustomGestureView.GestureType.Up, false, 0f, 0f,
                    0, 0, 0, 0, cancelled = cancelled))
            assertFalse(release(true))
            assertTrue(committed.isEmpty())
            assertEquals(listOf(PopupAction.DismissAction(key.id)), popups)
            popups.clear()
            assertTrue(release(false))
            assertEquals(listOf(KeyAction.CommitAction("chosen")), committed)
            assertTrue(popups[0] is PopupAction.TriggerAction)
            assertEquals(PopupAction.DismissAction(key.id), popups.last())
        } finally {
            panel.detach()
            controller.pause().stop().destroy()
        }
    }
}
