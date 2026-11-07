/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.keyboardtest

import android.content.ContextWrapper
import android.os.Bundle
import android.os.Build
import android.os.Parcelable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.SparseArray
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.contentcapture.ContentCaptureManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.view.MenuProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 35], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class KeyboardTestPanelTest {
    class Host : SettingsUiActivity() {
        lateinit var toolbar: MaterialToolbar
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            toolbar = MaterialToolbar(this)
            setContentView(LinearLayout(this).apply { addView(toolbar, ViewGroup.LayoutParams(-1, -2)) })
            setSupportActionBar(toolbar)
        }
        override fun onCreateOptionsMenu(menu: Menu): Boolean {
            menu.add(0, 900, 0, "Existing action")
            return true
        }
    }

    private fun host(block: (Host) -> Unit) {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        try { block(controller.get()) }
        finally { controller.pause().stop().destroy() }
    }

    @Test
    @Config(sdk = [29, 30])
    fun contentCaptureOptOutRespectsItsPlatformApiBoundary() = host { activity ->
        if (Build.VERSION.SDK_INT < 29) return@host
        // Supply an enabled Activity-scoped service; the default shadow starts disabled
        // and could otherwise make a missing opt-out look successful.
        val manager = Shadow.newInstanceOf(ContentCaptureManager::class.java)
        manager.setContentCaptureEnabled(true)
        assertTrue(manager.isContentCaptureEnabled)
        val serviceName = activity.getSystemServiceName(ContentCaptureManager::class.java)
        val context = object : ContextWrapper(activity) {
            override fun getSystemService(name: String): Any? =
                if (name == serviceName) manager else super.getSystemService(name)
        }
        val panel = KeyboardTestPanel(context) {}
        assertFalse(panel.isAttachedToWindow)
        assertEquals("Android 10 must opt out before attachment; Android 11 retains view-level scoping",
            Build.VERSION.SDK_INT >= 30, manager.isContentCaptureEnabled)
        panel.select(KeyboardTestPanel.Kind.PIN)
        assertTrue(panel.input.transformationMethod is PasswordTransformationMethod)
        assertFalse(panel.input.isSaveEnabled)
        if (Build.VERSION.SDK_INT >= 26) {
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, panel.input.importantForAutofill)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO, panel.input.importantForContentCapture)
            assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, panel.importantForContentCapture)
        }
        panel.clear()
        assertEquals(Build.VERSION.SDK_INT >= 30, manager.isContentCaptureEnabled)
        manager.setContentCaptureEnabled(false)
        val next = KeyboardTestPanel(context) {}
        next.clear()
        assertFalse("Opening or clearing a panel must not re-enable a previously disabled session",
            manager.isContentCaptureEnabled)
    }

    @Test fun everyTabPublishesTheExpectedAndroidEditorContract() = host { activity ->
        val panel = KeyboardTestPanel(activity) {}
        activity.setContentView(panel)
        val expected = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        assertEquals(4, panel.tabs.childCount)
        KeyboardTestPanel.Kind.entries.forEachIndexed { index, kind ->
            panel.select(kind)
            panel.input.requestFocus()
            val info = EditorInfo()
            assertNotNull(panel.input.onCreateInputConnection(info))
            assertEquals(kind.name, expected[index], info.inputType)
            assertEquals(kind.secret, panel.input.transformationMethod is PasswordTransformationMethod)
            assertEquals(1, panel.tabs.checkedButtonIds.size)
        }
    }

    @Test fun passwordsNeverLeakIntoAnotherTabAndPinOnlyAcceptsDigits() = host { activity ->
        val panel = KeyboardTestPanel(activity) {}
        activity.setContentView(panel)
        panel.select(KeyboardTestPanel.Kind.PASSWORD)
        panel.input.setText("test password")
        panel.select(KeyboardTestPanel.Kind.TEXT)
        assertEquals("", panel.input.text.toString())
        panel.select(KeyboardTestPanel.Kind.PIN)
        panel.input.text!!.append("12a3")
        assertEquals("123", panel.input.text.toString())
        panel.select(KeyboardTestPanel.Kind.NUMBER)
        assertEquals("", panel.input.text.toString())
        panel.input.setText("456")
        panel.select(KeyboardTestPanel.Kind.TEXT)
        assertEquals("456", panel.input.text.toString())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [28, 35], qualifiers = "en-w360dp-h800dp-mdpi")
    fun typeButtonsStayEqualInOneRowOrTheLargeTextTwoByTwoFallback() {
        try {
            for (scale in listOf(1f, 1.6f)) {
                RuntimeEnvironment.setFontScale(scale)
                host { activity ->
                    val panel = KeyboardTestPanel(activity) {}
                    activity.setContentView(panel)
                    for (width in listOf(320, 360)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                        panel.layoutDirection = direction
                        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        panel.layout(0, 0, width, panel.measuredHeight)
                        val buttons = (0 until panel.tabs.childCount).map { panel.tabs.getChildAt(it) as MaterialButton }
                        assertEquals(4, buttons.size)
                        assertEquals("All four cells must have exactly equal widths", 1, buttons.map { it.width }.distinct().size)
                        assertEquals(if (scale == 1f) 1 else 2, buttons.map { it.top }.distinct().size)
                        buttons.forEach { button ->
                            assertTrue(button.left >= 0 && button.right <= panel.tabs.width)
                            assertTrue(button.height >= 48)
                            val textLayout = checkNotNull(button.layout)
                            repeat(textLayout.lineCount) { line ->
                                assertEquals("The complete label must remain readable", 0, textLayout.getEllipsisCount(line))
                                assertTrue(textLayout.getLineWidth(line) <= button.width - button.compoundPaddingLeft - button.compoundPaddingRight + 1f)
                            }
                        }
                        buttons.last().performClick()
                        buttons.last().performClick()
                        assertEquals(KeyboardTestPanel.Kind.PIN, panel.kind)
                        assertEquals(listOf(buttons.last().id), panel.tabs.checkedButtonIds)
                    }
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun testContentsAreExcludedFromSavedViewState() = host { activity ->
        val panel = KeyboardTestPanel(activity) {}
        activity.setContentView(panel)
        panel.select(KeyboardTestPanel.Kind.PIN)
        panel.input.setText("1234")
        val saved = SparseArray<Parcelable>()
        panel.saveHierarchyState(saved)
        assertFalse(panel.input.isSaveEnabled)
        assertFalse(panel.input.isSaveFromParentEnabled)
        val restored = KeyboardTestPanel(activity) {}
        restored.restoreHierarchyState(saved)
        assertEquals("", restored.input.text.toString())
        panel.clear()
        assertEquals("", panel.input.text.toString())
    }

    @Test fun actionBarAndPageMenuRebuildsKeepOneEntryAndPreserveOtherActions() = host { activity ->
        repeat(3) {
            activity.invalidateOptionsMenu()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertNotNull(activity.toolbar.menu.findItem(900))
            assertEquals(1, (0 until activity.toolbar.menu.size()).count { index ->
                activity.toolbar.menu.getItem(index).itemId == R.id.keyboard_test_entry
            })
        }
        val page = MaterialToolbar(activity)
        KeyboardTestMenu.attach(page, activity)
        page.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) { menu.add(0, 901, 0, "Page action") }
            override fun onMenuItemSelected(menuItem: MenuItem) = false
        })
        repeat(3) { page.invalidateMenu() }
        assertNotNull(page.menu.findItem(901))
        assertEquals(1, (0 until page.menu.size()).count { page.menu.getItem(it).itemId == R.id.keyboard_test_entry })
        assertTrue(page.menu.performIdentifierAction(R.id.keyboard_test_entry, 0))
        assertTrue(ShadowDialog.getLatestDialog() is KeyboardTestDialog)
    }

    @Test fun dismissingOrBackgroundingTheHostClearsThePasswordAndReopensAnEmptyPanel() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        try {
            val activity = controller.get()
            activity.showKeyboardTest()
            val first = ShadowDialog.getLatestDialog() as KeyboardTestDialog
            first.panel.select(KeyboardTestPanel.Kind.PIN)
            first.panel.input.setText("1234")
            first.dismiss()
            assertEquals("", first.panel.input.text.toString())
            activity.showKeyboardTest()
            val second = ShadowDialog.getLatestDialog() as KeyboardTestDialog
            assertEquals("", second.panel.input.text.toString())
            second.panel.select(KeyboardTestPanel.Kind.PASSWORD)
            second.panel.input.setText("test password")
            controller.pause().stop()
            assertFalse(second.isShowing)
            assertEquals("", second.panel.input.text.toString())
        } finally { controller.destroy() }
    }
}
