/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.stats.*
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
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
import java.text.NumberFormat

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class InputStatsActivityTest {
    private lateinit var store: InputStats
    private lateinit var originalAppearance: SettingsUiPreferences.Selection
    private val moduleTitles = listOf(R.string.stats_career, R.string.stats_overview, R.string.stats_peak,
        R.string.stats_cloud_chars, R.string.stats_cloud_words, R.string.stats_cloud_mixed,
        R.string.stats_word_lengths, R.string.stats_code_lengths, R.string.stats_heatmap)

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        originalAppearance = SettingsUiPreferences.read(context)
        store = InputStats(context, "stats-ui.db")
        ReflectionHelpers.setStaticField(InputStats::class.java, "instance", store)
        runBlocking { withTimeout(10_000) { store.settings.first { it.loaded } } }
        assertNull(store.settings.value.error)
    }

    @After fun cleanup() {
        try { runBlocking { withTimeout(10_000) { store.close() } } }
        finally {
            ReflectionHelpers.setStaticField(InputStats::class.java, "instance", null)
            SettingsUiPreferences.save(RuntimeEnvironment.getApplication(), originalAppearance)
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun populated(): StatsSnapshot {
        val day = StatsRange.day(System.currentTimeMillis())
        val totals = StatsTotals(characters = 5280, commits = 1840, keys = 13620, deletions = 272,
            totalCodeLength = 15840, activeMillis = 5_280_000, speedCharacters = 5280,
            peakCpm = 96.5, peakKps = 4.8, peakCodeLength = 3.0)
        val words = linkedMapOf("输入" to 220L, "中文" to 170L, "生活" to 92L, "世界" to 66L,
            "学习" to 52L, "键盘" to 38L, "明天" to 30L, "我" to 310L, "的" to 286L, "你" to 221L,
            "好" to 134L, "中" to 118L, "文" to 106L, "新" to 75L, "年" to 61L)
        val geometry = mutableListOf<StatsKeyGeometry>()
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").forEachIndexed { row, keys ->
            val offset = if (row == 1) .05f else if (row == 2) .15f else 0f
            keys.forEachIndexed { column, key -> geometry += StatsKeyGeometry(key.toString(), offset + column*.1f, row*48f, .1f, 48f) }
        }
        geometry += listOf(StatsKeyGeometry("Shift",0f,96f,.15f,48f),StatsKeyGeometry("Backspace",.85f,96f,.15f,48f),
            StatsKeyGeometry("123",0f,144f,.15f,48f),StatsKeyGeometry(",",.15f,144f,.1f,48f),
            StatsKeyGeometry("Space",.25f,144f,.5f,48f),StatsKeyGeometry(".",.75f,144f,.1f,48f),StatsKeyGeometry("Enter",.85f,144f,.15f,48f))
        val counts=geometry.mapIndexed { index,key -> key.label to (if (key.label == "Space") 2140L else 120L + (index*73)%800) }.toMap()
        return StatsSnapshot(StatsRange.today(), null,
            listOf(StatsSchema("one", "方案一", 5280), StatsSchema("two", "方案二", 9120)), totals,
            listOf(StatsDay(day, totals)), mapOf(2 to 560L, 3 to 1020L, 4 to 260L),
            mapOf(1 to 400L, 2 to 680L, 3 to 360L, 4 to 400L),
            counts, words,
            careerCharacters = 14400,
            layout = StatsLayout(geometry = geometry))
    }

    private fun inject(activity: InputStatsActivity, snapshot: StatsSnapshot) {
        ReflectionHelpers.setField(activity, "data", snapshot)
        ReflectionHelpers.setField(activity, "today", snapshot)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render", ReflectionHelpers.ClassParameter.from(StatsSnapshot::class.java, snapshot))
    }

    @Test fun allNineModulesRemainReachableInLightDarkLargeTextAndRtl() {
        runBlocking { store.setEnabled(true); store.setWordFrequencyEnabled(true) }
        for (name in listOf("light", "dark", "large-rtl")) {
            val large = name == "large-rtl"
            RuntimeEnvironment.setFontScale(if (large) 1.6f else 1f)
            val context = RuntimeEnvironment.getApplication()
            SettingsUiPreferences.save(context, originalAppearance.copy(
                mode = if (name == "dark") SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
                palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false))
            val controller = Robolectric.buildActivity(InputStatsActivity::class.java).setup().visible()
            try {
                val activity = controller.get()
                awaitLoaded(activity)
                inject(activity, populated())
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                root.layoutDirection = if (large) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                layout(root, if (large) 320 else 360)
                val scroll = views(root).filterIsInstance<ScrollView>().single()
                val content = scroll.getChildAt(0) as LinearLayout
                assertTrue(root.isAttachedToWindow)
                assertTrue(content.height > scroll.height)
                for (title in moduleTitles) {
                    val label = views(content).filterIsInstance<TextView>().first { it.text.toString() == activity.getString(title) }
                    val bounds = Rect().also { label.getDrawingRect(it); content.offsetDescendantRectToMyCoords(label, it) }
                    assertTrue(bounds.left >= 0 && bounds.right <= content.width && bounds.bottom <= content.height)
                    scroll.scrollTo(0, bounds.top)
                    assertTrue("Module ${activity.getString(title)} must be reachable", label.getGlobalVisibleRect(Rect()))
                }
                views(content).filterIsInstance<Chip>().forEach { assertTrue(it.height >= 48) }
                val labels=views(content).filterIsInstance<TextView>().filter { it.tag == StatsTypography.Role.LABEL }
                val values=views(content).filterIsInstance<TextView>().filter { it.tag == StatsTypography.Role.VALUE }
                val speeds=views(content).filterIsInstance<TextView>().filter { it.tag == StatsTypography.Role.SPEED }
                assertTrue(values.isNotEmpty() && speeds.size == 2)
                assertTrue("Metric values must visually dominate their labels",values.all { value ->
                    value.typeface.isBold && value.textSize > labels.first().textSize*1.35f && value.ellipsize == null
                })
                // Android's large-font curve scales large type less than small labels.
                assertTrue("Speed values use the largest data type",speeds.all { it.textSize > values.maxOf { value -> value.textSize } && it.typeface.isBold })
                (values + speeds).forEach(::assertCompleteValue)
                assertTrue(labels.all { !it.typeface.isBold })
                val heat=views(content).filterIsInstance<StatsChartView>().single { it.heatLayoutSnapshot().isNotEmpty() }
                assertTrue(heat.parent is android.widget.HorizontalScrollView)
                assertTrue(heat.width >= heat.preferredMinimumWidth)
                val viewport = heat.parent as ViewGroup
                val heatContent = viewport.parent as ViewGroup
                val hint = views(heatContent).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.stats_heatmap_scroll) }
                assertEquals("The normal page must show the entire captured keyboard", viewport.width, heat.width)
                assertEquals(View.GONE, hint.visibility)
                assertEquals(33,heat.heatLayoutSnapshot().size)
                assertTrue(heat.heatTextSnapshot().all { !it.percentage.text.contains('…') && !it.percentage.text.contains("...") })
                heat.heatTextSnapshot().forEach { text ->
                    listOf(text.label, text.percentage).forEach { line ->
                        assertTrue(line.textSize <= 11 * activity.resources.displayMetrics.density + .01f)
                    }
                }
                capture(heatContent, "page-$name-complete-heatmap")
                assertEquals(3, views(content).filterIsInstance<WordCloudView>().size)
                views(content).filterIsInstance<WordCloudView>().forEach { cloud ->
                    assertFalse(cloud.layoutSnapshot().isEmpty())
                    cloud.layoutSnapshot().forEach { assertTrue(Rect(0, 0, cloud.width, cloud.height).contains(
                        it.bounds.left.toInt(), it.bounds.top.toInt(), it.bounds.right.toInt(), it.bounds.bottom.toInt())) }
                }
                scroll.scrollTo(0, 0)
                capture(root, "page-$name-viewport")
                capture(content, "page-$name-content")
            } finally { controller.get().finish(); controller.pause().stop().destroy() }
        }
    }

    @Test fun longLifetimeValuesRemainReadableAndCompleteAcrossRepeatedRtlMeasurements() {
        RuntimeEnvironment.setFontScale(1.6f)
        val controller = Robolectric.buildActivity(InputStatsActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            awaitLoaded(activity)
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            root.layoutDirection = View.LAYOUT_DIRECTION_RTL
            for (total in listOf(14400L, 9876543210L, Long.MAX_VALUE)) {
                inject(activity, populated().copy(careerCharacters = total))
                for (width in listOf(320, 600, 320)) {
                    layout(root, width)
                    views(root).filterIsInstance<StatsTypography.ValueView>().forEach {
                        assertTrue("Long totals must not shrink below readable type", it.textSize >= TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_SP, 18f, activity.resources.displayMetrics))
                        assertCompleteValue(it)
                    }
                }
            }
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    private fun assertCompleteValue(view: TextView) {
        val layout = requireNotNull(view.layout)
        val available = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        assertEquals("Every digit must have a laid-out line", view.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
        for (line in 0 until layout.lineCount) {
            assertEquals(0, layout.getEllipsisCount(line))
            assertTrue("${view.text}: line left ${layout.getLineLeft(line)}", layout.getLineLeft(line) >= -1f)
            assertTrue("${view.text}: line right ${layout.getLineRight(line)} > $available", layout.getLineRight(line) <= available + 1f)
            val text = view.text.subSequence(layout.getLineStart(line), layout.getLineEnd(line)).toString()
            assertTrue("Text paint and cached layout must agree for $text", view.paint.measureText(text) <= available + 1f)
        }
    }

    @Test fun emptyDisabledPageKeepsPrivacyControlsAndRequiresWordCollectionConfirmation() {
        val controller = Robolectric.buildActivity(InputStatsActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            awaitLoaded(activity)
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            layout(root, 320)
            val switches = views(root).filterIsInstance<MaterialSwitch>()
            assertEquals(2, switches.size)
            assertTrue(switches.none { it.isChecked })
            assertTrue(views(root).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.stats_disabled) })
            for (title in moduleTitles) assertTrue(views(root).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(title) })
            capture(root, "page-disabled-viewport")
            capture(views(root).filterIsInstance<ScrollView>().single().getChildAt(0), "page-disabled-content")
            val words = switches.single { it.text.toString() == activity.getString(R.string.stats_words_record) }
            words.isChecked = true
            assertFalse(words.isChecked)
            assertFalse(store.settings.value.wordFrequencyEnabled)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertFalse(store.settings.value.wordFrequencyEnabled)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun shareDialogHasNineModulesAndTextSharingDoesNotExposeCloudContents() {
        val controller = Robolectric.buildActivity(InputStatsActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            awaitLoaded(activity)
            val snapshot = populated()
            inject(activity, snapshot)
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            layout(root, 360)
            activity.invalidateOptionsMenu()
            shadowOf(Looper.getMainLooper()).idle()
            val toolbar = views(root).filterIsInstance<MaterialToolbar>().single()
            val expectedMenu = listOf(R.string.stats_share, R.string.stats_refresh, R.string.stats_backup, R.string.stats_restore,
                R.string.stats_clear_period, R.string.stats_clear_peak, R.string.stats_clear_all)
            val items = (0 until toolbar.menu.size()).map(toolbar.menu::getItem)
            for (title in expectedMenu) assertTrue(items.any { it.title.toString() == activity.getString(title) })
            activity.onOptionsItemSelected(items.single { it.title.toString() == activity.getString(R.string.stats_share) })
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(9, dialog.listView.count)
            // AppCompat applies initial checks from ArrayAdapter.getView, not when
            // setMultiChoiceItems installs the adapter. Bind both ends of the real list.
            layout(requireNotNull(dialog.window).decorView, 360)
            dialog.listView.setSelection(8)
            layout(requireNotNull(dialog.window).decorView, 360)
            dialog.listView.setSelection(0)
            layout(requireNotNull(dialog.window).decorView, 360)
            assertEquals(setOf(0, 2, 3, 7), (0 until 9).filter { dialog.listView.isItemChecked(it) }.toSet())
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val chooser = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            val shared = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            assertEquals(Intent.ACTION_SEND, shared.action)
            assertEquals("text/plain", shared.type)
            val text = shared.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            assertTrue(text.contains(NumberFormat.getNumberInstance().format(snapshot.totals.characters)))
            assertTrue(text.contains(activity.getString(R.string.stats_characters)))
            assertFalse(text.contains("明天"))
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun schemaAndPeriodSelectionRefreshThroughTheRealStore() {
        runBlocking {
            store.setEnabled(true)
            store.recordCommit("one", "方案一", "你好", 3)
            store.recordCommit("two", "方案二", "世界", 3)
            store.snapshot()
        }
        val controller = Robolectric.buildActivity(InputStatsActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            awaitLoaded(activity)
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            layout(root, 320)
            fun click(text: String) {
                views(root).filterIsInstance<Chip>().single { it.text.toString() == text }.performClick()
                awaitLoaded(activity)
                layout(root, 320)
            }
            click("方案二")
            assertEquals("two", ReflectionHelpers.getField<String>(activity, "schema"))
            click(activity.getString(R.string.stats_week))
            assertEquals(1, ReflectionHelpers.getField<Int>(activity, "period"))
            click(activity.getString(R.string.stats_all_schemas))
            assertNull(ReflectionHelpers.getField<String?>(activity, "schema"))
            val snapshot = ReflectionHelpers.getField<StatsSnapshot>(activity, "data")
            assertEquals(4L, snapshot.totals.characters)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun aConfirmedClearKeepsTheSchemaCapturedWhenTheDialogOpened() {
        runBlocking {
            store.setEnabled(true)
            store.recordCommit("one", "方案一", "你好", 3)
            store.recordCommit("two", "方案二", "世界", 3)
            store.snapshot()
        }
        val controller = Robolectric.buildActivity(InputStatsActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            awaitLoaded(activity)
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            views(root).filterIsInstance<Chip>().single { it.text.toString() == "方案一" }.performClick()
            awaitLoaded(activity)
            activity.invalidateOptionsMenu(); shadowOf(Looper.getMainLooper()).idle()
            val toolbar = views(root).filterIsInstance<MaterialToolbar>().single()
            val clear = (0 until toolbar.menu.size()).map(toolbar.menu::getItem)
                .single { it.title.toString() == activity.getString(R.string.stats_clear_period) }
            activity.onOptionsItemSelected(clear)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            // Reproduces a changed selection while an older confirmation is still pending.
            ReflectionHelpers.setField(activity, "schema", "two")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            awaitLoaded(activity)
            runBlocking {
                assertEquals(0L, store.snapshot(StatsRange.today(), "one").totals.characters)
                assertEquals(2L, store.snapshot(StatsRange.today(), "two").totals.characters)
            }
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun restoringWithoutTheSelectedSchemaReturnsToAllAvailableData() {
        runBlocking {
            store.setEnabled(true)
            store.recordCommit("one", "方案一", "你好", 3)
            store.snapshot()
        }
        val controller = Robolectric.buildActivity(InputStatsActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            awaitLoaded(activity)
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            views(root).filterIsInstance<Chip>().single { it.text.toString() == "方案一" }.performClick()
            awaitLoaded(activity)
            val replacement = StatsArchive(schemas = listOf(StatsSchema("two", "方案二", 9)),
                buckets = listOf(StatsBucket(StatsRange.day(System.currentTimeMillis()), "two", StatsTotals(characters = 9))))
            runBlocking { store.importJson(StatsArchive.json.encodeToString(replacement)) }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "refresh")
            awaitLoaded(activity)
            assertNull(ReflectionHelpers.getField<String?>(activity, "schema"))
            val restored = ReflectionHelpers.getField<StatsSnapshot>(activity, "data")
            assertNull(restored.schemaId)
            assertEquals(9L, restored.totals.characters)
            assertTrue(views(root).filterIsInstance<Chip>().single {
                it.text.toString() == activity.getString(R.string.stats_all_schemas)
            }.isChecked)

            views(root).filterIsInstance<Chip>().single { it.text.toString() == "方案二" }.performClick()
            awaitLoaded(activity)
            runBlocking { store.clearAllData() }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "refresh")
            awaitLoaded(activity)
            assertNull(ReflectionHelpers.getField<String?>(activity, "schema"))
            assertTrue(views(root).filterIsInstance<Chip>().single {
                it.text.toString() == activity.getString(R.string.stats_all_schemas)
            }.isChecked)
        } finally { controller.get().finish(); controller.pause().stop().destroy() }
    }

    @Test fun aShareDialogOpenedBeforePrivacyResetCannotShareItsOldSnapshot() {
        for (button in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL)) {
            runBlocking {
                store.setEnabled(true); store.setWordFrequencyEnabled(true)
                store.recordCommit("one", "方案一", "中文", 3)
                store.snapshot()
            }
            val controller = Robolectric.buildActivity(InputStatsActivity::class.java).setup().visible()
            try {
                val activity = controller.get()
                awaitLoaded(activity)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "chooseShare")
                val dialog = ShadowDialog.getLatestDialog() as AlertDialog
                runBlocking { store.clearAllData() }
                dialog.getButton(button).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                awaitLoaded(activity)
                assertNull(shadowOf(activity).nextStartedActivity)
                assertFalse(File(activity.cacheDir, "shared/input-statistics").exists())
            } finally { controller.get().finish(); controller.pause().stop().destroy() }
        }
    }

    private fun awaitLoaded(activity: InputStatsActivity) {
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            val loading = ReflectionHelpers.getField<Job?>(activity, "loading")
            if (loading?.isActive != true && ReflectionHelpers.getField<StatsSnapshot?>(activity, "data") != null) return
            check(System.nanoTime() < deadline) { "Statistics page did not finish loading" }
            Thread.sleep(5)
        } while (true)
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    private fun layout(root: View, width: Int) {
        repeat(2) {
            shadowOf(Looper.getMainLooper()).idle()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, 800)
        }
    }

    /** Robolectric renders; viewport and full scrolling page remain distinct artifacts. */
    private fun capture(view: View, name: String) {
        val directory = System.getenv("INPUT_STATS_PAGE_PREVIEW_DIR") ?: return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(view.context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            view.draw(canvas)
            File(directory, "$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
