/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.rimeupdate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.shape.MaterialShapeDrawable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.RimeSchemeTask
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestShadow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangSettingsDaemonDelegate
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangSettingsFixture
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, FcitxDaemonTestShadow::class])
class RimeSchemeUpdateActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var appearance: SettingsUiPreferences.Selection
    private val task = RimeSchemeTask(name = "白霜方案", source = "https://example.test/rime-frost.zip", fileName = "rime-frost.zip")
    @Before fun prepare() {
        appearance = SettingsUiPreferences.read(context)
        WanxiangSettingsFixture.context = context
        WanxiangSettingsFixture.root = File(context.filesDir, UUID.randomUUID().toString()).apply { mkdirs() }
        FcitxDaemonTestShadow.install(WanxiangSettingsDaemonDelegate())
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun cleanup() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        FcitxDaemonTestShadow.reset()
        SettingsUiPreferences.save(context, appearance)
        RuntimeEnvironment.setFontScale(1f)
    }
    private fun form(activity: RimeSchemeUpdateActivity) = ReflectionHelpers.getField<RimeSchemeUpdateForm>(activity, "form")
    private fun editor(activity: RimeSchemeUpdateActivity) = ReflectionHelpers.getField<RimeSchemeTaskEditor>(activity, "editor")
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) { shadowOf(Looper.getMainLooper()).idle(); check(System.nanoTime() < until); Thread.sleep(5) }
    }
    private fun layout(view: View, width: Int, height: Int) = repeat(2) {
        shadowOf(Looper.getMainLooper()).idle()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
    private fun capture(view: View, name: String) {
        val directory = System.getenv("RIME_SCHEME_PREVIEW_DIR") ?: return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(view.context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    @Test fun tasksActionsAndTargetRemainReadableInBothThemesAndSmallLargeTextWindows() {
        RimeSchemeUpdates.save(context, listOf(task, task.copy(id = UUID.randomUUID().toString(), name = "Lua 过滤器", fileName = "filter.lua", unpack = false, destinations = listOf("lua"))))
        for (name in listOf("light", "dark", "large-rtl")) {
            val large = name == "large-rtl"
            RuntimeEnvironment.setFontScale(if (large) 1.6f else 1f)
            SettingsUiPreferences.save(context, appearance.copy(mode = if (name == "dark") SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
            val controller = Robolectric.buildActivity(RimeSchemeUpdateActivity::class.java).setup().visible()
            try {
                val activity = controller.get()
                val form = form(activity)
                await { form.execute.isEnabled }
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                root.layoutDirection = if (large) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                layout(root, if (large) 320 else 360, if (large) 400 else 800)
                assertTrue(form.target.text.toString().contains(WanxiangSettingsFixture.root.canonicalPath))
                assertEquals(View.GONE, form.target.visibility)
                assertTrue(views(root).filterIsInstance<MaterialButton>().filter { it.visibility == View.VISIBLE }.all { it.height >= 48 })
                val scroll = views(root).filterIsInstance<ScrollView>().single()
                assertTrue(form.execute.width > 0 && form.execute.height >= 48)
                capture(root, "custom-update-$name")
                capture(scroll.getChildAt(0), "custom-update-$name-content")
            } finally { controller.get().finish(); controller.pause().stop().destroy() }
        }
    }

    @Test fun fileNameSuggestionsFollowTypingWithoutOverwritingAnExplicitName() {
        val controller = Robolectric.buildActivity(RimeSchemeUpdateActivity::class.java).setup().visible()
        try {
            form(controller.get()).add.performClick()
            val editor = editor(controller.get())
            editor.source.setText("h")
            assertEquals("", editor.fileName.text.toString())
            editor.source.setText("https://example.test/s")
            editor.source.setText("https://example.test/scheme.zip")
            assertEquals("scheme.zip", editor.fileName.text.toString())
            editor.fileName.setText("custom-name.zip")
            editor.source.setText("https://example.test/next.zip")
            assertEquals("custom-name.zip", editor.fileName.text.toString())
            editor.selectedFile("content://provider/document/123", "local.zip")
            assertEquals("local.zip", editor.fileName.text.toString())
            val dialogView = requireNotNull((ShadowDialog.getLatestDialog() as AlertDialog).window).decorView
            layout(dialogView, 360, 800)
            views(editor).filterIsInstance<TextInputLayout>().forEach {
                assertEquals(TextInputLayout.BOX_BACKGROUND_OUTLINE, it.boxBackgroundMode)
                assertTrue(it.editText?.background is MaterialShapeDrawable)
            }
            capture(dialogView, "custom-update-editor")
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun invalidRulesAndEscapingTargetsStayInTheEditorAndDoNotReplaceSavedTasks() {
        val controller = Robolectric.buildActivity(RimeSchemeUpdateActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            form(activity).add.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val fields = editor(activity)
            fields.name.setText("新方案")
            fields.source.setText("https://example.test/scheme.zip?download=1")
            assertEquals("scheme.zip", fields.fileName.text.toString())
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            fields.destinations.setText("../outside")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertTrue(fields.error.text.isNotBlank())
            assertTrue(RimeSchemeUpdates.tasks(context).isEmpty())
            fields.destinations.setText(".\nlua")
            fields.excludes.setText("[")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            fields.excludes.setText(RimeSchemeTask.DEFAULT_EXCLUDES)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            await { !dialog.isShowing }
            val saved = RimeSchemeUpdates.tasks(context).single()
            assertEquals(listOf("", "lua"), saved.destinations)
            assertEquals("新方案", saved.name)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun rotationPreservesAnUnsavedEditorDraft() {
        val controller = Robolectric.buildActivity(RimeSchemeUpdateActivity::class.java).setup().visible()
        val state = Bundle()
        form(controller.get()).add.performClick()
        editor(controller.get()).name.setText("未保存的任务")
        editor(controller.get()).source.setText("https://example.test/module.lua")
        editor(controller.get()).unpack.isChecked = false
        controller.saveInstanceState(state).pause().stop().destroy()
        val next = Robolectric.buildActivity(RimeSchemeUpdateActivity::class.java).create(state).start().resume().visible()
        try {
            val draft = editor(next.get()).draft()
            assertEquals("未保存的任务", draft.name)
            assertEquals("module.lua", draft.fileName)
            assertFalse(draft.unpack)
            assertTrue(RimeSchemeUpdates.tasks(context).isEmpty())
        } finally { next.get().finish(); next.pause().stop().destroy() }
    }

    @Test fun queuedWorkShowsItsCapturedTargetInsteadOfTheCurrentDirectory() {
        RimeSchemeUpdates.save(context, listOf(task))
        val previous = WanxiangTarget("previous", File(context.filesDir, "previous-profile").canonicalPath)
        val job = OneTimeWorkRequestBuilder<RimeSchemeUpdateWorker>().setInitialDelay(1, TimeUnit.DAYS)
            .setInputData(workDataOf(RimeSchemeUpdateWorker.TARGET to Json.encodeToString(previous))).build()
        RimeSchemeUpdates.directory(context, job.id).apply { mkdirs() }
        File(RimeSchemeUpdates.directory(context, job.id), "request.json").writeText(Json.encodeToString(RimeSchemeRequest(previous, listOf(task))))
        WorkManager.getInstance(context).enqueueUniqueWork(RimeSchemeUpdates.WORK, ExistingWorkPolicy.KEEP, job).result.get()
        val controller = Robolectric.buildActivity(RimeSchemeUpdateActivity::class.java).setup().visible()
        try {
            val form = form(controller.get())
            await { form.status.detail.text.toString().contains(previous.path) }
            assertFalse(form.execute.isEnabled)
            assertFalse(form.status.detail.text.toString().contains(WanxiangSettingsFixture.root.canonicalPath))
            assertTrue(form.cancel.isEnabled)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }
}
