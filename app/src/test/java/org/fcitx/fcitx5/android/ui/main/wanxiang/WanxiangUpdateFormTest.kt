/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.common.MaterialSpinner
import org.fcitx.fcitx5.android.ui.common.OperationStatusView
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class WanxiangUpdateFormTest {
    @Test fun updateControlsRemainReachableInLightDarkAndLargeTextLayouts() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            for (name in listOf("light", "dark", "large", "large-rtl")) {
                val dark = name == "dark"
                val large = name.startsWith("large")
                val direction = if (name.endsWith("rtl")) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                RuntimeEnvironment.setFontScale(if (large) 1.6f else 1f)
                val context = SettingsUiPreferences.themedContext(activity, SettingsUiPreferences.read(activity).copy(
                    mode = if (dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                    palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
                val form = WanxiangUpdateForm(context, WanxiangSelection(scheme = WanxiangSelection.Scheme.PRO))
                form.target.text = "rime\n/storage/emulated/0/Android/data/org.fcitx.fcitx5.android.fx.rime/files/data/rime"
                activity.setContentView(form)
                form.layoutDirection = direction
                form.setBusy(false, canUpdate = true)
                form.status.show(context.getString(R.string.wanxiang_ready))
                layout(form, if (large) 320 else 360)
                assertTrue(form.isAttachedToWindow)
                assertEquals(direction, form.update.layoutDirection)
                assertTrue(form.update.isEnabled)
                assertEquals(View.GONE, form.cancel.visibility)
                assertEquals(WanxiangSelection.Scheme.PRO, form.selection().scheme)
                assertTrue(form.update.measuredHeight >= 48)
                assertFixedFooter(form)
                form.scroll.scrollTo(0, 0)
                capture(form, name)

                for (label in listOf(R.string.wanxiang_directory, R.string.wanxiang_protection, R.string.wanxiang_network_options)) {
                    button(form, label).performClick()
                }
                layout(form, if (large) 320 else 360)
                assertFixedFooter(form)
                form.scroll.scrollTo(0, 0)
                capture(form, "$name-expanded")

                form.setBusy(true, canUpdate = true, canCancel = false)
                form.status.show("正在更新文件并重新部署 Rime…", state = OperationStatusView.State.WORKING)
                layout(form, if (large) 320 else 360)
                assertFalse(form.update.isEnabled)
                assertFalse(form.cancel.isEnabled)
                assertAllChangesDisabled(form)
                assertFixedFooter(form)
                form.scroll.scrollTo(0, 0)
                capture(form, "$name-working")
                form.status.show("万象更新失败", "下载失败，请检查网络连接。", OperationStatusView.State.ERROR)
                layout(form, if (large) 320 else 360)
                assertFixedFooter(form)
                form.scroll.scrollTo(0, 0)
                capture(form, "$name-error")
            }
        } finally { RuntimeEnvironment.setFontScale(1f); controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun shortWindowsScrollTheActionsAndRestoreTheFixedFooterWhenExpanded() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            RuntimeEnvironment.setFontScale(1.6f)
            val activity = controller.get()
            val form = WanxiangUpdateForm(SettingsUiPreferences.themedContext(activity, SettingsUiPreferences.read(activity)), WanxiangSelection())
            activity.setContentView(form)
            form.layoutDirection = View.LAYOUT_DIRECTION_RTL
            form.setBusy(false, canUpdate = true)
            form.status.show(activity.getString(R.string.wanxiang_ready))
            layout(form, 320, 320)
            assertTrue(form.isAttachedToWindow)
            assertTrue(isInside(form.update, form.scroll))
            assertTrue(isInside(form.status, form.scroll))
            assertTrue(form.content.height > form.scroll.height)
            form.scroll.scrollTo(0, form.content.height)
            assertVisibleInside(form, form.update)
            assertVisibleInside(form, form.status)
            capture(form, "short-rtl-bottom")

            layout(form, 320)
            assertFalse(isInside(form.update, form.scroll))
            assertFalse(isInside(form.status, form.scroll))
            assertFixedFooter(form)
            form.scroll.scrollTo(0, 0)
            capture(form, "short-restored")
        } finally { RuntimeEnvironment.setFontScale(1f); controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun updateScopeShowsOnlyApplicableOptionsAndRequiresAnExtraSelection() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val form = WanxiangUpdateForm(SettingsUiPreferences.themedContext(activity, SettingsUiPreferences.read(activity)), WanxiangSelection())
            activity.setContentView(form)
            form.setBusy(false, canUpdate = true)
            layout(form, 360)
            val scope = views(form).filterIsInstance<MaterialSpinner>().single {
                it.contentDescription.toString() == activity.getString(R.string.wanxiang_scope)
            }
            val extras = button(form, R.string.wanxiang_extra_choose)
            assertTrue(extras.isShown)
            for (choice in listOf(WanxiangSelection.Scope.SCHEME, WanxiangSelection.Scope.MODEL)) {
                scope.setSelection(choice.ordinal)
                layout(form, 360)
                assertEquals(choice, form.selection().scope)
                assertFalse("Extra dictionaries must be hidden for $choice", extras.isShown)
                assertTrue(form.update.isEnabled)
            }
            scope.setSelection(WanxiangSelection.Scope.EXTRA.ordinal)
            layout(form, 360)
            assertTrue(extras.isShown)
            assertFalse(form.update.isEnabled)
            form.setExtras(listOf("medical"))
            assertTrue(form.update.isEnabled)
            assertEquals(listOf("medical"), form.selection().extraDictionaries)
            form.setExtras(emptyList())
            assertFalse(form.update.isEnabled)

            scope.setSelection(WanxiangSelection.Scope.DICTIONARY.ordinal)
            layout(form, 360)
            assertTrue(extras.isShown)
            assertTrue(form.update.isEnabled)
            form.setBusy(false, canUpdate = false)
            assertFalse(form.update.isEnabled)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun activeOperationsDisableEveryMutationButCanRetainTheCancelAction() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val form = WanxiangUpdateForm(SettingsUiPreferences.themedContext(activity, SettingsUiPreferences.read(activity)), WanxiangSelection(scheme = WanxiangSelection.Scheme.PRO))
            activity.setContentView(form)
            for (label in listOf(R.string.wanxiang_protection, R.string.wanxiang_network_options)) button(form, label).performClick()
            form.setBusy(true, canUpdate = true, canCancel = true)
            layout(form, 360)
            assertAllChangesDisabled(form)
            assertFalse(form.update.isEnabled)
            assertEquals(View.VISIBLE, form.cancel.visibility)
            assertTrue(form.cancel.isEnabled)
            form.setBusy(true, canUpdate = true, canCancel = false)
            assertAllChangesDisabled(form)
            assertFalse(form.cancel.isEnabled)
            form.setBusy(false, canUpdate = true)
            assertTrue(form.update.isEnabled)
            assertEquals(View.GONE, form.cancel.visibility)
            assertTrue(form.advanced.isEnabled)
            assertTrue(button(form, R.string.wanxiang_protection_edit).isEnabled)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun button(form: WanxiangUpdateForm, label: Int) = views(form).filterIsInstance<MaterialButton>()
        .single { it.text.toString() == form.context.getString(label) }

    private fun assertAllChangesDisabled(form: WanxiangUpdateForm) {
        views(form).filterIsInstance<MaterialSpinner>().forEach { assertFalse(it.contentDescription.toString(), it.isEnabled) }
        for (label in listOf(R.string.wanxiang_protection_edit, R.string.wanxiang_token, R.string.wanxiang_github_check,
                R.string.wanxiang_extra_choose, R.string.wanxiang_extra_refresh)) {
            assertFalse(form.context.getString(label), button(form, label).isEnabled)
        }
        assertFalse(form.advanced.isEnabled)
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if (parent === ancestor) return true
            parent = parent.parent
        }
        return false
    }

    private fun bounds(form: WanxiangUpdateForm, view: View) = Rect().also {
        view.getDrawingRect(it)
        form.offsetDescendantRectToMyCoords(view, it)
    }

    private fun assertVisibleInside(form: WanxiangUpdateForm, view: View) {
        val rect = bounds(form, view)
        assertTrue("${view.javaClass.simpleName} must be shown", view.isShown)
        assertTrue("${view.javaClass.simpleName} is outside ${form.width}x${form.height}: $rect",
            rect.left >= 0 && rect.top >= 0 && rect.right <= form.width && rect.bottom <= form.height && !rect.isEmpty)
    }

    private fun assertFixedFooter(form: WanxiangUpdateForm) {
        assertFalse(isInside(form.update, form.scroll))
        assertFalse(isInside(form.status, form.scroll))
        form.scroll.scrollTo(0, 0)
        val update = bounds(form, form.update)
        val status = bounds(form, form.status)
        assertVisibleInside(form, form.update)
        assertVisibleInside(form, form.status)
        form.scroll.scrollTo(0, form.content.height)
        assertEquals(update, bounds(form, form.update))
        assertEquals(status, bounds(form, form.status))
        assertVisibleInside(form, form.update)
        assertVisibleInside(form, form.status)
    }

    private fun layout(view: View, width: Int, height: Int = 800) {
        // Spinner selection notifications can be posted by the first layout pass.
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
        }
    }

    /** These are Robolectric renders: the viewport and full scroll content are separate artifacts. */
    private fun capture(form: WanxiangUpdateForm, name: String) {
        val directory = System.getenv("WANXIANG_PREVIEW_DIR") ?: return
        for ((suffix, page) in listOf("viewport" to form, "content" to form.content)) {
            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(form.context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
                page.draw(canvas)
                File(directory, "$name-$suffix.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
    }
}
