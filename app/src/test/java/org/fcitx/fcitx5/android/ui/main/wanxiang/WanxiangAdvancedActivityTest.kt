/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.ImageButton
import android.widget.TextView
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestDelegate
import org.fcitx.fcitx5.android.testing.FcitxDaemonTestShadow
import org.fcitx.fcitx5.android.data.*
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.EditorDraftState
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
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
import java.io.File
import java.lang.reflect.Proxy

/** Exercise the real Activity/ViewModel/transaction with a deterministic configuration API. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class, FcitxDaemonTestShadow::class])
class WanxiangAdvancedActivityTest {
    @Before fun prepare() {
        FcitxDaemonTestShadow.install(WanxiangSettingsDaemonDelegate())
        // AndroidX caches this factory outside Robolectric's per-test Application lifecycle.
        org.robolectric.util.ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        val context = RuntimeEnvironment.getApplication()
        WanxiangSettingsFixture.root = File(context.filesDir, "advanced-profile").apply { deleteRecursively(); mkdirs() }
        WanxiangSettingsFixture.context = context
        WanxiangSettingsFixture.failDeploy = false
        File(WanxiangSettingsFixture.root, "default.yaml").writeText("schema_list: [{schema: wanxiang}]\nmenu: {page_size: 6}\nkey_binder: {bindings: []}\n")
        File(WanxiangSettingsFixture.root, "wanxiang.schema.yaml").writeText("schema: {schema_id: wanxiang, name: Test}\nmenu: {page_size: 6}\ntranslator: {dictionary: wanxiang, enable_user_dict: true}\nspeller: {alphabet: abc}\nswitches: [{name: emoji}]\n")
    }
    @After fun resetFactory() {
        org.robolectric.util.ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
        FcitxDaemonTestShadow.reset()
    }
    private fun model(activity: WanxiangAdvancedActivity) = ViewModelProvider(activity)[WanxiangAdvancedViewModel::class.java]
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Activity operation did not finish" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun thePageEditsAndSavesCommonSettingsWithoutASecondDirectoryPicker() {
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
        try {
            val vm = model(controller.get())
            await { !vm.state.value.busy }
            assertNull(vm.state.value.error)
            assertEquals(WanxiangAdvancedViewModel.COMMON, vm.state.value.file)
            assertTrue(vm.state.value.rows.isNotEmpty())
            val row = vm.state.value.rows.first { it.common == WanxiangCommonSettings.Key.PAGE_SIZE }
            assertEquals(WanxiangSettingsFixture.context.noBackupFilesDir, vm.getApplication<android.app.Application>().noBackupFilesDir)
            vm.edit(row, "8")
            vm.save()
            await { !vm.state.value.busy }
            assertTrue(vm.state.value.error.orEmpty(), vm.state.value.saved)
            assertEquals(0, vm.state.value.dirty)
            val patch = File(WanxiangSettingsFixture.root, "default.custom.yaml")
            assertTrue(patch.isFile)
            assertTrue(patch.readText().contains("8"))
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    private fun navigationButton(activity: android.app.Activity): ImageButton {
        fun toolbar(view: View): MaterialToolbar? = if (view is MaterialToolbar) view else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { toolbar(view.getChildAt(it)) } else null
        val bar = toolbar(activity.findViewById(android.R.id.content))!!
        return (0 until bar.childCount).map(bar::getChildAt).filterIsInstance<ImageButton>().first()
    }

    @Test fun updaterToolbarArrowReturnsAfterTheActionBarHasInstalledItsCallbacks() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val controller = Robolectric.buildActivity(WanxiangUpdateActivity::class.java).setup()
        try {
            controller.get().invalidateOptionsMenu()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(navigationButton(controller.get()).performClick())
            assertTrue(controller.get().isFinishing)
        } finally {
            controller.get().finish(); controller.pause().stop().destroy()
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    @Test fun readingTheAdvancedPagePreservesAlreadyDeployedFilesAndCanReturnNormally() {
        val root = WanxiangSettingsFixture.root
        File(root, "build").mkdirs()
        listOf("default.yaml", "wanxiang.schema.yaml").forEach { name -> File(root, name).copyTo(File(root, "build/$name")) }
        val before = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to (it.readText() to it.lastModified()) }
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
        try {
            val vm = model(controller.get()); await { !vm.state.value.busy }
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.compiled)
            assertEquals(before, root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to (it.readText() to it.lastModified()) })
            navigationButton(controller.get()).performClick()
            assertTrue(controller.get().isFinishing)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun anExternalEditBlocksTheSaveAndKeepsTheDraftAvailable() {
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
        try {
            val vm = model(controller.get()); await { !vm.state.value.busy }
            vm.edit(vm.state.value.rows.first { it.common == WanxiangCommonSettings.Key.PAGE_SIZE }, "8")
            File(WanxiangSettingsFixture.root, "default.yaml").appendText("outside_change: true\n")
            vm.save(); await { !vm.state.value.busy }
            assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.error.orEmpty(), vm.state.value.stale)
            assertTrue(vm.state.value.dirty > 0)
            assertFalse(File(WanxiangSettingsFixture.root, "default.custom.yaml").exists())
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun restoringDuringSelectionLoadingCompletesTheSelectionAndKeepsEarlierEdits() {
        val root = WanxiangSettingsFixture.root
        val before = root.listFiles()!!.associate { it.name to it.readText() }
        for (selected in listOf(WanxiangAdvancedViewModel.COMMON, "wanxiang.schema.yaml")) {
            // A file switch (or initial common-page load) has selected the main scheme,
            // but only default.yaml was received when Android saved the Activity state.
            val draft = WanxiangAdvancedSession(WanxiangTarget("rime", root.canonicalPath), false).apply {
                add(WanxiangAdvancedFiles.snapshot(root, "default.yaml"))
                change("default.yaml", "menu/page_size", yaml.scalar(8))
            }
            val state = Bundle()
            EditorDraftState(WanxiangSettingsFixture.context, "wanxiang-advanced").save(state,
                Json.encodeToString(WanxiangAdvancedViewModel.Draft(draft.draft(), selected, "menu")))
            val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup(state)
            try {
                val vm = model(controller.get()); await { !vm.state.value.busy }
                assertNull(vm.state.value.error)
                assertFalse(vm.state.value.stale)
                assertEquals(selected, vm.state.value.file)
                assertTrue(vm.state.value.rows.isNotEmpty())
                assertTrue(vm.session!!.documents.containsKey("wanxiang.schema.yaml"))
                assertEquals("8", vm.session!!.text("default.yaml", "menu/page_size"))
                assertEquals(draft.edits, vm.session!!.edits)
                assertEquals(before, root.listFiles()!!.associate { it.name to it.readText() })
            } finally { controller.get().finish(); controller.pause().stop().destroy() }
        }
    }

    @Test fun restoringAnIncompleteSelectionVerifiesTheDraftBeforeLoadingMissingFiles() {
        val root = WanxiangSettingsFixture.root
        val draft = WanxiangAdvancedSession(WanxiangTarget("rime", root.canonicalPath), false).apply {
            add(WanxiangAdvancedFiles.snapshot(root, "default.yaml"))
            change("default.yaml", "menu/page_size", yaml.scalar(8))
        }
        File(root, "wanxiang.schema.yaml").appendText("outside_change: true\n")
        val state = Bundle()
        EditorDraftState(WanxiangSettingsFixture.context, "wanxiang-advanced").save(state,
            Json.encodeToString(WanxiangAdvancedViewModel.Draft(draft.draft(), "wanxiang.schema.yaml", "menu")))
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup(state)
        try {
            val vm = model(controller.get()); await { !vm.state.value.busy }
            assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.stale)
            assertEquals(draft.edits, vm.session!!.edits)
            assertFalse(vm.session!!.documents.containsKey("wanxiang.schema.yaml"))
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun explicitRepairReloadsMissingDeployedFilesWithoutEditingSources() {
        val root = WanxiangSettingsFixture.root
        val before = root.listFiles()!!.associate { it.name to (it.readText() to it.lastModified()) }
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
        try {
            val vm = model(controller.get()); await { !vm.state.value.busy }
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.compiled)
            vm.repairDeployment(); await { !vm.state.value.busy }
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.compiled)
            assertEquals(before, before.keys.associateWith { File(root, it).let { file -> file.readText() to file.lastModified() } })
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun aFailedDeploymentRestoresOriginalFilesAndLeavesTheEditsForCorrection() {
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
        try {
            val vm = model(controller.get()); await { !vm.state.value.busy }
            vm.edit(vm.state.value.rows.first { it.common == WanxiangCommonSettings.Key.PAGE_SIZE }, "8")
            WanxiangSettingsFixture.failDeploy = true
            vm.save(); await { !vm.state.value.busy }
            assertNotNull(vm.state.value.error)
            assertTrue(vm.state.value.dirty > 0)
            assertFalse(File(WanxiangSettingsFixture.root, "default.custom.yaml").exists())
            assertTrue(File(WanxiangSettingsFixture.root, "default.yaml").readText().contains("page_size: 6"))
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun anUnsafePartialResetReportsAnErrorAndKeepsTheDraft() {
        File(WanxiangSettingsFixture.root, "wanxiang.custom.yaml").writeText(
            "patch: {menu: {page_size: 8}, 'menu/+': {other: false}}\n")
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
        try {
            val vm = model(controller.get()); await { !vm.state.value.busy }
            vm.select("wanxiang.schema.yaml"); await { !vm.state.value.busy }
            vm.select("wanxiang.schema.yaml", "menu")
            val before = vm.session!!.draft()
            vm.reset(vm.state.value.rows.single { it.path == "menu/page_size" })
            assertNotNull(vm.state.value.error)
            assertEquals(before, vm.session!!.draft())
            assertFalse(vm.state.value.stale)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun commonRowsExplainWhichDictionaryIsShownWhenSchemesUseDifferentOnes() {
        val root = WanxiangSettingsFixture.root
        val main = File(root, "wanxiang.schema.yaml")
        val source = main.readText()
        main.appendText("custom_phrase: {dictionary: personal}\n")
        File(root, "wanxiang_pro.schema.yaml").writeText(source.replace("schema_id: wanxiang", "schema_id: wanxiang_pro") +
            "custom_phrase: {dictionary: another}\n")
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
        try {
            val vm = model(controller.get()); await { !vm.state.value.busy }
            val row = vm.state.value.rows.single { it.common == WanxiangCommonSettings.Key.PHRASE_DICTIONARY }
            assertTrue(row.help.contains("personal"))
            assertTrue(descendants(controller.get().findViewById(android.R.id.content)).filterIsInstance<TextView>()
                .any { it.text.toString() == row.help })
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun lightDarkAndLargeTextPagesKeepTheirControlsInsideTheScrollContent() {
        for ((name, dark, large) in listOf(Triple("light", false, false), Triple("dark", true, false), Triple("large", false, true))) {
            RuntimeEnvironment.setFontScale(if (large) 1.6f else 1f)
            val context = RuntimeEnvironment.getApplication()
            SettingsUiPreferences.save(context, SettingsUiPreferences.read(context).copy(
                mode = if (dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
            val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
            try {
                val vm = model(controller.get()); await { !vm.state.value.busy }
                capture(controller.get(), name, if (large) 320 else 360)
                vm.select("wanxiang.schema.yaml"); await { !vm.state.value.busy }
                capture(controller.get(), "$name-schema", if (large) 320 else 360)
            } finally { controller.get().finish(); controller.pause().stop().destroy() }
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun saveActionsRemainReachableAndJoinTheScrollAreaInAShortWindow() {
        RuntimeEnvironment.setFontScale(1.6f)
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
        try {
            val activity = controller.get()
            val vm = model(activity); await { !vm.state.value.busy }
            vm.edit(vm.state.value.rows.first { it.common == WanxiangCommonSettings.Key.PAGE_SIZE }, "8")
            shadowOf(Looper.getMainLooper()).idle()
            // A common setting can update both default.yaml and the active schema.
            // Resizing must preserve the full draft, regardless of its file count.
            val editsBeforeLayout = vm.session!!.edits.toMap()
            assertTrue(editsBeforeLayout.isNotEmpty())
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val save = descendants(root).filterIsInstance<MaterialButton>().single { it.text == activity.getString(R.string.wanxiang_advanced_save) }
            fun inScroll(): Boolean = generateSequence(save.parent) { it.parent }.any { it is ScrollView }
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                root.layoutDirection = direction
                layout(root, 320, 800)
                assertFalse(inScroll())
                assertTrue(save.isEnabled)
                val bounds = Rect(); save.getDrawingRect(bounds); root.offsetDescendantRectToMyCoords(save, bounds)
                assertTrue(bounds.toString(), bounds.left >= 0 && bounds.right <= root.width && bounds.top >= 0 && bounds.bottom <= root.height)
                assertTrue(save.height >= 48)
                layout(root, 320, 240)
                assertTrue(inScroll())
                val scroll = descendants(root).filterIsInstance<ScrollView>().first()
                assertTrue(scroll.height > 0)
            }
            assertEquals(editsBeforeLayout, vm.session!!.edits)
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            controller.get().finish(); controller.pause().stop().destroy()
        }
    }

    @Test fun editingOptionsAndValueRowsRemainUsableWithoutModifyingFiles() {
        val controller = Robolectric.buildActivity(WanxiangAdvancedActivity::class.java).setup()
        try {
            val activity = controller.get()
            val vm = model(activity); await { !vm.state.value.busy }
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            val before = WanxiangSettingsFixture.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() }
            assertTrue(descendants(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.wanxiang_advanced_scope_hint) })
            val options = descendants(root).filterIsInstance<MaterialButton>().single { it.text == activity.getString(R.string.wanxiang_advanced_options) }
            options.performClick()
            assertEquals(activity.getString(R.string.wanxiang_advanced_options_hide), options.text.toString())
            val preview = descendants(root).filterIsInstance<TextView>().first { it.text.toString() == "6" }
            assertFalse("Preview text must pass taps to the editable row", preview.isTextSelectable)
            assertTrue((preview.parent as View).performClick())
            assertNotNull(vm.pending)
            assertEquals(WanxiangCommonSettings.Key.PAGE_SIZE.name, vm.pending!!.common)
            assertEquals(0, vm.state.value.dirty)
            assertEquals(before, WanxiangSettingsFixture.root.walkTopDown().filter { it.isFile }.associate { it.path to it.readText() })
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    private fun capture(activity: WanxiangAdvancedActivity, name: String, width: Int) {
        val root = activity.findViewById<View>(android.R.id.content)
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, 800)
        fun scroll(view: View): ScrollView? = if (view is ScrollView) view else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { scroll(view.getChildAt(it)) } else null
        val body = scroll(root)!!.getChildAt(0)
        assertTrue(body.height > 0)
        val directory = System.getenv("WANXIANG_ADVANCED_PREVIEW_DIR") ?: return
        fun write(view: View, suffix: String) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(activity.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
                view.draw(canvas)
                File(directory, "$name$suffix.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
        write(body, "")
        write(root, "-viewport")
    }
}

internal object WanxiangSettingsFixture {
    lateinit var root: File
    lateinit var context: Context
    var failDeploy = false
    private fun hex(value: String) = value.toByteArray().joinToString("") { "%02x".format(it) }
    val api: FcitxAPI = Proxy.newProxyInstance(FcitxAPI::class.java.classLoader, arrayOf(FcitxAPI::class.java)) { _, method, arguments ->
        when (method.name) {
            "getAddonSubConfig" -> {
                val path = arguments!![1] as String
                if (path == "profile-manager") RawConfig(arrayOf(RawConfig("Current", "rime"), RawConfig("Profiles", "rime"),
                    RawConfig("PathsV2", "${hex("rime")}|${hex(root.canonicalPath)}")))
                else RawConfig(arrayOf(RawConfig("Snapshot", Json.encodeToString(WanxiangAdvancedFiles.snapshot(root, path.substringAfter('/'))))))
            }
            "setAddonSubConfig" -> {
                val request = arguments!![2] as RawConfig
                if (arguments[1] == "wanxiang-settings-repair") {
                    check(request.findByName("Profile")!!.value == "rime" && request.findByName("Path")!!.value == root.canonicalPath)
                    File(root, "build").mkdirs()
                    listOf("default.yaml", "wanxiang.schema.yaml").forEach { name -> File(root, name).copyTo(File(root, "build/$name"), overwrite = true) }
                    return@newProxyInstance Unit
                }
                val job = File(context.noBackupFilesDir, "wanxiang-settings/${request.findByName("RequestId")!!.value}")
                check(File(job, "plan.json").isFile) { "Missing staged save plan" }
                val plan = Json.decodeFromString<WanxiangAdvancedFiles.Plan>(File(job, "plan.json").readText())
                WanxiangAdvancedFiles.verify(root, plan.expected)
                WanxiangUpdateTransaction.install(root, File(job, "payload"), { if (failDeploy) error("fixture deployment failed") }, {}, settings = true)
                Unit
            }
            "toString" -> "WanxiangSettingsFixture"
            else -> error("Unexpected configuration API: ${method.name}")
        }
    } as FcitxAPI
}

internal class WanxiangSettingsDaemonDelegate : FcitxDaemonTestDelegate {
    override fun connect(name: String): FcitxConnection = object : FcitxConnection {
        override val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T = runBlocking { block(WanxiangSettingsFixture.api) }
        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T = block(WanxiangSettingsFixture.api)
        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) { lifecycleScope.launch { block(WanxiangSettingsFixture.api) } }
    }
    override fun disconnect(name: String) = Unit
}
