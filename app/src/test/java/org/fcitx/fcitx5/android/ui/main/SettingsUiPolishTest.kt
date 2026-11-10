/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeMonet
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.clipboard.ClipboardEntryUi
import org.fcitx.fcitx5.android.ui.common.OperationStatusView
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.ui.main.settings.theme.ResponsiveThemeListView
import org.fcitx.fcitx5.android.ui.main.settings.theme.SimpleThemeListAdapter
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeListAdapter
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeThumbnailUi
import org.fcitx.fcitx5.android.ui.main.update.AssetStatus
import org.fcitx.fcitx5.android.ui.main.update.AssetUiState
import org.fcitx.fcitx5.android.ui.main.update.ReleaseAsset
import org.fcitx.fcitx5.android.ui.main.update.UpdateAssetAdapter
import org.fcitx.fcitx5.android.utils.settingsColor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class SettingsUiPolishTest {
    private val activities = mutableListOf<ActivityController<Activity>>()
    private fun host(dark: Boolean = false): Pair<Activity, Context> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().also(activities::add).get()
        val selection = SettingsUiPreferences.read(activity).copy(
            mode = if (dark) SettingsUiPreferences.MODE_DARK else SettingsUiPreferences.MODE_LIGHT,
            palette = SettingsUiPreferences.PALETTE_COMPAT, followKeyboardTheme = false)
        return activity to SettingsUiPreferences.themedContext(activity, selection)
    }
    @After fun cleanUp() {
        activities.forEach { it.pause().stop().destroy() }
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun capture(activity: Activity, root: View, name: String, width: Int = 360, height: Int? = null) {
        activity.setContentView(root)
        val direction = root.layoutDirection
        root.layoutDirection = direction
        shadowOf(Looper.getMainLooper()).idle()
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height ?: 1100, if (height == null) View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        System.getenv("UI_POLISH_PREVIEW_DIR")?.let { directory ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(root.context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            root.draw(canvas)
            File(directory, "$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private class Themes(val events: MutableList<String> = mutableListOf()) : ThemeListAdapter() {
        override fun onAddNewTheme() = Unit
        override fun onSelectTheme(theme: Theme) { events += "select" }
        override fun onEditTheme(theme: Theme.Custom) { events += "edit" }
        override fun onEditMonetTheme(theme: Theme.Monet) = Unit
        override fun onEditRandomTheme(theme: Theme.Custom) = Unit
        override fun onRandomizeTheme() { events += "random" }
        override fun onExportTheme(theme: Theme.Custom) { events += "export" }
    }

    @Test fun themeNamesStayOutsideThePreviewAndActionsAtLargeText() {
        for ((dark, scale) in listOf(false to 1f, true to 1f, false to 1.5f)) {
            RuntimeEnvironment.setFontScale(scale)
            val (activity, context) = host(dark)
            val first = ThemePreset.PixelLight.deriveCustomNoBackground("海盐 · 清爽日间")
            val second = ThemePreset.MaterialDark.deriveCustomNoBackground("松林 · 柔和夜间")
            val third = ThemePreset.MaterialDark.deriveCustomNoBackground(org.fcitx.fcitx5.android.data.theme.ThemeManager.RANDOM_THEME_NAME)
            val adapter = Themes().apply {
                setThemes(listOf(first, second, third))
                setSelectedThemes(first, null, second)
            }
            val grid = ResponsiveThemeListView(context).apply { this.adapter = adapter }
            capture(activity, grid, if (scale > 1f) "themes-large" else if (dark) "themes-dark" else "themes-light", height = 560)
            for (index in 0 until grid.childCount) {
                val holder = grid.getChildViewHolder(grid.getChildAt(index)) as ThemeListAdapter.ViewHolder
                val ui = holder.ui as? ThemeThumbnailUi ?: continue
                val name = Rect(0, 0, ui.themeNameText.width, ui.themeNameText.height)
                ui.root.offsetDescendantRectToMyCoords(ui.themeNameText, name)
                val preview = Rect(0, 0, ui.thumbnailView.width, ui.thumbnailView.height)
                ui.root.offsetDescendantRectToMyCoords(ui.thumbnailView, preview)
                assertTrue(name.top >= preview.bottom)
                val clipped = Bitmap.createBitmap(ui.root.width, ui.root.height, Bitmap.Config.ARGB_8888)
                val background = context.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer)
                val canvas = Canvas(clipped).apply { drawColor(background) }
                ui.root.draw(canvas)
                assertEquals("The preview must not paint outside the rounded card", background, clipped.getPixel(1, 1))
                clipped.recycle()
                assertEquals(1, ui.themeNameText.maxLines)
                for (button in listOf(ui.randomButton, ui.editButton, ui.exportButton).filter { it.visibility == View.VISIBLE }) {
                    val action = Rect(0, 0, button.width, button.height)
                    ui.root.offsetDescendantRectToMyCoords(button, action)
                    assertTrue(action.top >= name.bottom)
                    assertTrue("No blank row between the name and actions", action.top - name.bottom <= 1)
                    assertTrue("Action $action exceeds card height ${ui.root.height} at font scale $scale", action.bottom <= ui.root.height)
                    assertTrue(button.width >= 48 && button.height >= 32)
                }
            }
        }
    }

    @Test fun themeNameAndActionsUseTwoCompactRowsWithoutSelectingTheTheme() {
        val (activity, context) = host()
        val adapter = Themes().apply { setThemes(listOf(ThemePreset.PixelLight.deriveCustomNoBackground("海盐"))) }
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(1))
        adapter.onBindViewHolder(holder, 1)
        val ui = holder.ui as ThemeThumbnailUi
        capture(activity, ui.root, "theme-compact", width = 152, height = 140)
        ui.editButton.performClick()
        ui.exportButton.performClick()
        assertEquals(listOf("edit", "export"), adapter.events)
        val bounds = Rect(0, 0, ui.editButton.width, ui.editButton.height)
        ui.root.offsetDescendantRectToMyCoords(ui.editButton, bounds)
        assertTrue("No extra blank row below the actions", ui.root.height - bounds.bottom <= 2)
        assertEquals(ui.editButton.top, ui.exportButton.top)
        val details = ui.themeNameText.parent as ViewGroup
        val buttonBounds = Rect(0, 0, ui.editButton.width, ui.editButton.height)
        details.offsetDescendantRectToMyCoords(ui.editButton, buttonBounds)
        val x = buttonBounds.exactCenterX()
        val y = buttonBounds.top - 8f
        val now = android.os.SystemClock.uptimeMillis()
        for (event in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val motion = android.view.MotionEvent.obtain(now, now + event, event, x, y, 0)
            try { assertTrue(details.dispatchTouchEvent(motion)) } finally { motion.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The compact icon still responds in its extended 48 dp touch target", "edit", adapter.events.last())
        ui.cleanup()
    }

    @Test fun randomThemeActionIsInTheSameCompactRowAsEdit() {
        val (activity, context) = host()
        val random = ThemePreset.MaterialDark.deriveCustomNoBackground(org.fcitx.fcitx5.android.data.theme.ThemeManager.RANDOM_THEME_NAME)
        val adapter = Themes().apply { setThemes(listOf(random)) }
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(1))
        adapter.onBindViewHolder(holder, 1)
        val ui = holder.ui as ThemeThumbnailUi
        capture(activity, ui.root, "theme-random", width = 152, height = 140)
        assertSame(ui.editButton.parent, ui.randomButton.parent)
        assertEquals(ui.editButton.top, ui.randomButton.top)
        assertEquals(View.GONE, ui.exportButton.visibility)
        ui.randomButton.performClick()
        assertEquals(listOf("random"), adapter.events)
        ui.cleanup()
    }

    @Test fun monetMarkersRemainVisibleInThePreviewBesideSelectionBadges() {
        for (dark in listOf(false, true)) {
            for (rtl in listOf(false, true)) {
                RuntimeEnvironment.setFontScale(if (rtl) 1.5f else 1f)
                val (activity, context) = host(dark)
                val lightTheme = ThemeMonet.getLight()
                val darkTheme = ThemeMonet.getDark()
                val adapter = Themes().apply {
                    setThemes(listOf(lightTheme, darkTheme))
                    setSelectedThemes(lightTheme, lightTheme, darkTheme)
                }
                for (position in 1..2) {
                    val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(position))
                    adapter.onBindViewHolder(holder, position)
                    val ui = holder.ui as ThemeThumbnailUi
                    try {
                        ui.root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                        capture(activity, ui.root, "monet-$position-dark-$dark-rtl-$rtl", width = 152)
                        assertEquals("Both Monet themes must retain the dynamic-color marker", View.VISIBLE, ui.dynamicIcon.visibility)
                        assertSame("The marker belongs in the preview, outside the action row", ui.thumbnailView, ui.dynamicIcon.parent)
                        assertFalse("The marker must not trigger a random theme", ui.dynamicIcon.isClickable)
                        val marker = Rect(0, 0, ui.dynamicIcon.width, ui.dynamicIcon.height)
                        ui.thumbnailView.offsetDescendantRectToMyCoords(ui.dynamicIcon, marker)
                        val selection = ui.checkMark.parent as View
                        val selected = Rect(0, 0, selection.width, selection.height)
                        ui.thumbnailView.offsetDescendantRectToMyCoords(selection, selected)
                        assertTrue(marker.left >= 0 && marker.top >= 0 && marker.right <= ui.thumbnailView.width && marker.bottom <= ui.thumbnailView.height)
                        assertFalse("Dynamic-color and day/night markers must not overlap", Rect.intersects(marker, selected))
                        val bitmap = Bitmap.createBitmap(ui.root.width, ui.root.height, Bitmap.Config.ARGB_8888)
                        try {
                            ui.root.draw(Canvas(bitmap))
                            val ink = checkNotNull(ui.dynamicIcon.imageTintList).defaultColor
                            var inkPixels = 0
                            ui.root.offsetDescendantRectToMyCoords(ui.thumbnailView, marker)
                            for (y in marker.top until marker.bottom) for (x in marker.left until marker.right) {
                                if (bitmap.getPixel(x, y) == ink) inkPixels++
                            }
                            assertTrue("The star must actually reach the rendered card", inkPixels > 10)
                        } finally { bitmap.recycle() }
                    } finally { ui.cleanup() }
                }
            }
        }
    }

    @Test fun recyclingMonetCardsClearsTheMarkerForStaticAndRandomThemes() {
        val (activity, context) = host()
        val random = ThemePreset.MaterialDark.deriveCustomNoBackground(org.fcitx.fcitx5.android.data.theme.ThemeManager.RANDOM_THEME_NAME)
        val adapter = Themes().apply {
            setThemes(listOf(ThemeMonet.getLight(), ThemePreset.PixelLight.deriveCustomNoBackground("静态主题"),
                ThemeMonet.getDark(), random, ThemePreset.MaterialDark))
        }
        val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(1))
        val ui = holder.ui as ThemeThumbnailUi
        try {
            for (position in listOf(1, 2, 3, 4, 5, 1)) {
                adapter.onBindViewHolder(holder, position)
                capture(activity, ui.root, "monet-recycled-$position", width = 152)
                assertEquals("A recycled card must show only its own theme marker",
                    if (position == 1 || position == 3) View.VISIBLE else View.GONE, ui.dynamicIcon.visibility)
            }
        } finally { ui.cleanup() }
    }

    @Test fun themeSelectionDialogsHideActionsWhileKeepingThemeSelection() {
        val (activity, context) = host()
        val themes = listOf(ThemeMonet.getLight(), ThemeMonet.getDark(),
            ThemePreset.MaterialDark.deriveCustomNoBackground(org.fcitx.fcitx5.android.data.theme.ThemeManager.RANDOM_THEME_NAME),
            ThemePreset.PixelLight.deriveCustomNoBackground("静态主题"), ThemePreset.MaterialDark)
        val selected = mutableListOf<Theme>()
        val adapter = object : SimpleThemeListAdapter<Theme>(themes) {
            override fun onClick(theme: Theme) { selected += theme }
        }
        val grid = ResponsiveThemeListView(context).apply { this.adapter = adapter }
        try {
            capture(activity, grid, "theme-selection-dialog", height = 560)
            for ((position, theme) in themes.withIndex()) {
                val holder = checkNotNull(grid.findViewHolderForAdapterPosition(position)) as SimpleThemeListAdapter.ViewHolder
                val ui = holder.ui as ThemeThumbnailUi
                for (action in listOf(ui.randomButton, ui.editButton, ui.exportButton)) {
                    assertEquals("Selection dialogs must not expose theme-management actions", View.GONE, action.visibility)
                }
                ui.root.performClick()
                assertEquals(theme, adapter.selectedTheme)
                assertEquals(theme, selected.last())
            }
            assertEquals(themes, selected)
        } finally {
            for (index in 0 until grid.childCount) {
                val holder = grid.getChildViewHolder(grid.getChildAt(index)) as SimpleThemeListAdapter.ViewHolder
                (holder.ui as ThemeThumbnailUi).cleanup()
            }
        }
    }

    @Test
    @Config(sdk = [28])
    fun staticMonetFallbackDoesNotAdvertiseDynamicColorMapping() {
        val (_, context) = host()
        val ui = ThemeThumbnailUi(context)
        try {
            for (theme in listOf(ThemeMonet.getLight(), ThemeMonet.getDark())) {
                ui.setTheme(theme)
                assertEquals(View.GONE, ui.dynamicIcon.visibility)
                assertEquals(View.GONE, ui.editButton.visibility)
                assertEquals(View.VISIBLE, ui.exportButton.visibility)
            }
        } finally { ui.cleanup() }
    }

    @Test fun movingRenamingAndRemovingThemesKeepsSelectionOnItsIdentity() {
        val (_, context) = host()
        val a = ThemePreset.PixelLight.deriveCustomNoBackground("A")
        val b = a.copy(name = "B")
        val c = a.copy(name = "C")
        val adapter = Themes().apply {
            setThemes(listOf(a, b, c))
            setSelectedThemes(b, null, c)
        }
        fun state(name: String): String {
            val position = adapter.entries.indexOfFirst { it.name == name } + 1
            val holder = adapter.onCreateViewHolder(FrameLayout(context), adapter.getItemViewType(position))
            adapter.onBindViewHolder(holder, position)
            val ui = holder.ui as ThemeThumbnailUi
            return ui.stateLabel.text.toString().also { ui.cleanup() }
        }
        adapter.removeTheme("A")
        assertEquals(context.getString(R.string.ui_theme_selected), state("B"))
        adapter.replaceTheme("C", c.copy(name = "Night"))
        assertEquals(context.getString(R.string.ui_theme_night), state("Night"))
        assertEquals(context.getString(R.string.ui_theme_selected), state("B"))
        adapter.prependTheme(a)
        assertEquals(context.getString(R.string.ui_theme_selected), state("B"))
        adapter.removeTheme("B")
        assertEquals("", state("A"))
        assertEquals(context.getString(R.string.ui_theme_night), state("Night"))
    }

    @Test fun clipboardFormWrapsWholeActionsWithLargeTextAndRtl() {
        RuntimeEnvironment.setFontScale(1.5f)
        for (rtl in listOf(false, true)) {
            val (activity, context) = host()
            val root = LayoutInflater.from(context).inflate(R.layout.activity_clipboard_edit, null) as ViewGroup
            root.findViewById<EditText>(R.id.clipboard_edit_text).setText("把常用的文字保存到剪贴板，编辑后可以复制，也可以确认保存。")
            root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            activity.setContentView(root)
            capture(activity, root, if (rtl) "clipboard-editor-rtl" else "clipboard-editor-large", width = 300)
            for (id in listOf(R.id.clipboard_edit_copy, R.id.clipboard_edit_cancel, R.id.clipboard_edit_ok)) {
                val button = root.findViewById<MaterialButton>(id)
                val bounds = Rect(0, 0, button.width, button.height)
                root.offsetDescendantRectToMyCoords(button, bounds)
                assertTrue(bounds.left >= 0 && bounds.right <= root.width && bounds.bottom <= root.height)
                assertTrue(button.height >= 48)
            }
        }
    }

    @Test fun pinnedClipboardEntriesReserveSpaceAndRemainEasyToTap() {
        val (activity, context) = host()
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        val ui = ClipboardEntryUi(context, ThemePreset.PixelLight, 12f)
        ui.setEntry("常用地址与短语", true)
        column.addView(ui.root, LinearLayout.LayoutParams(-1, -2))
        capture(activity, column, "clipboard-pinned")
        assertTrue(ui.root.height >= 48)
        assertTrue(ui.textView.paddingEnd >= 34)
        ui.setEntry("普通条目", false)
        assertEquals(View.GONE, ui.pin.visibility)
        assertEquals(12, ui.textView.paddingEnd)
    }

    @Test fun updateActionIconsDispatchTheCorrectActionAfterRecycling() {
        val (activity, context) = host()
        val clicks = mutableListOf<String>()
        val adapter = UpdateAssetAdapter({ clicks += "download" }, { clicks += it.status.name })
        val asset = ReleaseAsset("中州鹅 ARM64.apk", "https://example.invalid/app.apk", 24_000_000, null)
        val states = listOf(AssetStatus.IDLE, AssetStatus.DOWNLOADING, AssetStatus.FINISHED, AssetStatus.FAILED)
        adapter.submitList(states.mapIndexed { index, state -> AssetUiState(asset.copy(
            name = "中州鹅-${listOf("arm64", "armv7", "x86_64", "x86")[index]}.apk",
            browserDownloadUrl = "https://example.invalid/$index/app.apk"), state, progressPercent = 42) })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
        for (index in states.indices) {
            val holder = adapter.onCreateViewHolder(column, adapter.getItemViewType(index))
            adapter.onBindViewHolder(holder, index)
            val action = holder.itemView.findViewById<MaterialButton>(R.id.assetAction)
            assertNotNull(action.icon)
            action.performClick()
            column.addView(holder.itemView)
        }
        capture(activity, column, "updates")
        assertEquals(listOf("download", "DOWNLOADING", "FINISHED", "download"), clicks)
        val recycled = adapter.onCreateViewHolder(column, adapter.getItemViewType(0))
        adapter.onBindViewHolder(recycled, 1)
        adapter.onBindViewHolder(recycled, 3)
        recycled.itemView.findViewById<MaterialButton>(R.id.assetAction).performClick()
        assertEquals("download", clicks.last())
    }

    @Test fun operationDetailsExpandWithoutCarryingErrorsIntoTheNextOperation() {
        val (activity, context) = host()
        val status = OperationStatusView(context)
        status.show("方案导入失败", "方案文件不完整，请检查 default.yaml 与依赖文件。", OperationStatusView.State.ERROR)
        assertEquals(View.GONE, status.detail.visibility)
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        views(status).filterIsInstance<MaterialButton>().single().performClick()
        assertEquals(View.VISIBLE, status.detail.visibility)
        capture(activity, status, "import-error")
        status.show("正在导入方案", "目标目录：/Rime/雾凇拼音", OperationStatusView.State.WORKING)
        assertFalse(status.detail.text.contains("不完整"))
        capture(activity, status, "import-progress")
        status.show("导入完成", "可以在下方输入框试用。", OperationStatusView.State.SUCCESS)
        capture(activity, status, "import-success")
    }
}
