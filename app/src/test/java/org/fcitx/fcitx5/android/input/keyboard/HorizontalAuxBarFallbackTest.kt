/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.AuxBarAction
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class HorizontalAuxBarFallbackTest {
    @Test fun topAndBottomRestoreConfiguredActionsWhenTabsDisappear() {
        val context = RuntimeEnvironment.getApplication()
        val splitEnabled = AppPrefs.getInstance().keyboard.splitKeyboardEnabled
        val previousSplitEnabled = splitEnabled.getValue()
        val previousManager = ReflectionHelpers.getStaticField<SplitKeyboardStateManager?>(
            SplitKeyboardStateManager::class.java, "instance"
        )
        try {
            // Bind a real manager to this application rather than inheriting a previous test's singleton.
            ReflectionHelpers.setStaticField(SplitKeyboardStateManager::class.java, "instance", null)
            SplitKeyboardStateManager.init(context)
            splitEnabled.setValue(false)
            listOf(AuxBarPosition.Top, AuxBarPosition.Bottom).forEach { position ->
                val key = AlphabetKey("q", "!")
                val keyboard = object : BaseKeyboard(context, ThemePreset.PixelLight,
                    { listOf(listOf(key)) }, { AuxBarConfig(position, 15f) }, { listOf(key) }) {
                    override fun currentLayoutSignature() = "horizontal-aux-regression"
                }
                val actions = mutableListOf<KeyAction>()
                keyboard.keyActionListener = KeyActionListener { action, _ -> actions += action }
                val recycler = ReflectionHelpers.getField<RecyclerView>(keyboard, "auxBarScrollableRv")
                val fallback = requireNotNull(recycler.adapter)
                assertEquals(1, fallback.itemCount)
                @Suppress("UNCHECKED_CAST")
                fun clickConfiguredKey() {
                    val adapter = recycler.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
                    val holder = adapter.createViewHolder(recycler, adapter.getItemViewType(0))
                    adapter.bindViewHolder(holder, 0)
                    val container = holder.itemView as ViewGroup
                    assertEquals(1, container.childCount)
                    val keyView = container.getChildAt(0) as KeyView
                    assertTrue(keyView.isEnabled)
                    assertTrue(keyView.performClick())
                }
                clickConfiguredKey()
                assertTrue(actions.single() is KeyAction.FcitxKeyAction)
                keyboard.updateAuxBarActions(listOf(AuxBarAction(1, "一"), AuxBarAction(2, "二")))
                assertNotSame(fallback, recycler.adapter)
                assertEquals(2, recycler.adapter!!.itemCount)
                keyboard.updateAuxBarActions(emptyList())
                assertSame(fallback, recycler.adapter)
                assertEquals(1, recycler.adapter!!.itemCount)
                clickConfiguredKey()
                assertEquals(2, actions.size)
                assertEquals("q", (actions.last() as KeyAction.FcitxKeyAction).act)
            }
        } finally {
            splitEnabled.setValue(previousSplitEnabled)
            ReflectionHelpers.setStaticField(SplitKeyboardStateManager::class.java, "instance", previousManager)
        }
    }
}
