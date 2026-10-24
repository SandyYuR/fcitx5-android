/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.common

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.ActionMenuView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.google.android.material.appbar.MaterialToolbar
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual Toolbar layout, including the shared activity's edge-to-edge inset handling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class SettingsToolbarTest {
    class EditorActivity : SettingsUiActivity() {
        lateinit var toolbar: MaterialToolbar
        lateinit var root: LinearLayout
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            toolbar = MaterialToolbar(this)
            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            }
            setContentView(root)
            setSupportActionBar(toolbar)
            supportActionBar!!.apply {
                title = "Keyboard layout"
                setDisplayHomeAsUpEnabled(true)
            }
        }
        override fun onCreateOptionsMenu(menu: Menu): Boolean {
            menu.add(0, 1, 0, R.string.save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu.add(0, 2, 1, R.string.delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            return true
        }
    }

    private fun verifyAlignment(subtitle: Boolean) {
        val controller = Robolectric.buildActivity(EditorActivity::class.java).setup()
        try {
            val activity = controller.get()
            if (subtitle) activity.supportActionBar!!.subtitle = "Sandy · rime"
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val toolbar = activity.toolbar
            // Includes repeated delivery and returning from a tall caption/status area to fullscreen.
            for (statusTop in listOf(0, 24, 48, 48, 0)) {
                ViewCompat.dispatchApplyWindowInsets(toolbar, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTop, 0, 0)).build())
                assertEquals(statusTop, toolbar.paddingTop)
                activity.root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                activity.root.layout(0, 0, 360, 640)
                val controls = toolbar.children.filter { it is ImageButton || it is ActionMenuView }.toList()
                assertEquals("Both navigation and action menu must be present", 2, controls.size)
                val expectedCenter = (toolbar.paddingTop + toolbar.height - toolbar.paddingBottom) / 2f
                for (control in controls) {
                    assertEquals("${control.javaClass.simpleName}, top inset $statusTop", expectedCenter,
                        (control.top + control.bottom) / 2f, 1f)
                    assertTrue(control.top >= toolbar.paddingTop)
                    assertTrue(control.bottom <= toolbar.height - toolbar.paddingBottom)
                }
                val titles = toolbar.children.filterIsInstance<TextView>().filter {
                    it.text == toolbar.title || (subtitle && it.text == toolbar.subtitle)
                }.toList()
                assertEquals(if (subtitle) 2 else 1, titles.size)
                assertEquals("Title bounds ${titles.map { it.top to it.bottom }}, toolbar ${toolbar.height}, " +
                    "margins ${toolbar.titleMarginTop}/${toolbar.titleMarginBottom}, inset $statusTop",
                    expectedCenter, (titles.minOf { it.top } + titles.maxOf { it.bottom }) / 2f, 1f)
                assertTrue(titles.minOf { it.top } >= toolbar.paddingTop)
                assertTrue(titles.maxOf { it.bottom } <= toolbar.height - toolbar.paddingBottom)
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun singleLineTitleKeepsButtonsCenteredWithStatusInsets() = verifyAlignment(false)
    @Test fun subtitleKeepsButtonsCenteredWithStatusInsets() = verifyAlignment(true)
    @Test fun largerTextKeepsControlsInsideTheToolbar() {
        RuntimeEnvironment.setFontScale(1.5f)
        try { verifyAlignment(true) } finally { RuntimeEnvironment.setFontScale(1f) }
    }
}
