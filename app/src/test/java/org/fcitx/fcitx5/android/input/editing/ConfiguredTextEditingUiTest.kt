/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.editing

import android.graphics.drawable.BitmapDrawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.allViews
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.input.action.RimeSchemaMenuAction
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.SwipeSymbolDirection
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = FcitxApplication::class,
    shadows = [EditorApplicationShadow::class])
class ConfiguredTextEditingUiTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val unlockedSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" fill="#000000"/></svg>"""
    private val lockedSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="2" fill="#000000"/></svg>"""

    private fun bundledRoot(profile: String) = run {
        // Robolectric AssetManager cannot reliably open Chinese asset filenames.
        val directory = File("src/main/assets/bundled/keyboard_layouts")
            .takeIf { it.isDirectory } ?: File("app/src/main/assets/bundled/keyboard_layouts")
        Json.parseToJsonElement(File(directory, "TextKeyboardLayout.$profile.json").readText()).jsonObject
    }

    private fun bundledEditor(profile: String): TextEditingLayout =
        requireNotNull(TextEditingLayoutLoader.fromJson(bundledRoot(profile), "", listOf("rime")))

    private fun configuredUi(layout: TextEditingLayout) = ConfiguredTextEditingUi(
        context, ThemePreset.PixelLight, ripple = false, border = false, radius = 0f, configuredLayout = layout
    )

    private fun withIconTheme(icons: Map<String, String>, block: () -> Unit) {
        val previous = IconThemeManager.activeTheme
        try {
            IconThemeManager.activeTheme = IconTheme(name = "configured editor test", icons = icons)
            block()
        } finally {
            IconThemeManager.activeTheme = previous
        }
    }

    private fun selectionKey(layout: TextEditingLayout) = layout.rows.flatten().single { key ->
        (key.tapAction as? MacroAction)?.steps?.any {
            it is MacroStep.Edit && it.action in setOf("toggleSelection", "toggle_selection")
        } == true
    }

    private fun swipeEditor(): TextEditingLayout {
        val root = Json.parseToJsonElement("""
            {"rime":{"editor":[[{
              "type":"MacroKey","label":"swipe","tap":{"macro":[{"type":"edit","action":"copy"}]},
              "swipeUp":{"macro":[{"type":"edit","action":"cut"}]},
              "swipeDown":{"macro":[{"type":"edit","action":"paste"}]}
            }]]}}
        """).jsonObject
        return requireNotNull(TextEditingLayoutLoader.fromJson(root, "", listOf("rime")))
    }

    private fun selectionButton(layout: TextEditingLayout, ui: ConfiguredTextEditingUi): TextEditingButton {
        val key = selectionKey(layout)
        return ui.keyViews.single { it.row == key.row && it.index == key.index }.view
    }

    private fun assertIcon(button: TextEditingButton) {
        assertSame(button, button.imageView.parent)
        assertNull(button.textView.parent)
        assertNotNull(button.imageView.drawable)
    }

    private fun assertText(button: TextEditingButton, expected: String) {
        assertSame(button, button.textView.parent)
        assertNull(button.imageView.parent)
        assertEquals(expected, button.textView.text.toString())
    }

    @Test
    fun bundledEditorLayoutsRenderComplete26By4GridWithoutDuplicateTargets() {
        listOf("水彩", "普蓝").forEach { profile ->
            val layout = bundledEditor(profile)
            assertEquals("$profile row count", 4, layout.rows.size)
            assertEquals("$profile grid width", 26, layout.columnCount)
            assertEquals("$profile key count", 15, layout.rows.flatten().size)

            val directions = mapOf(
                KeyEvent.KEYCODE_DPAD_LEFT to "Left",
                KeyEvent.KEYCODE_DPAD_RIGHT to "Right"
            )
            directions.forEach { (keyCode, label) ->
                val matches = layout.rows.flatten().filter { key ->
                    (key.tapAction as? KeyAction.SymAction)?.sym?.keyCode == keyCode
                }
                assertEquals("$profile $label key count", 1, matches.size)
                assertEquals("$profile $label row span", 3, matches.single().gridCell?.rowSpan)
            }

            val ui = configuredUi(layout)
            val root = ui.root as ViewGroup
            val width = 1040
            val height = 800
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, width, height)
            assertEquals(15, ui.keyViews.size)
            assertEquals(15, root.childCount)
            assertEquals(15, ui.keyViews.map { it.view }.toSet().size)

            val occupied = Array(4) { BooleanArray(26) }
            ui.keyViews.forEach { rendered ->
                val cell = requireNotNull(layout.rows[rendered.row][rendered.index].gridCell)
                val expectedLeft = cell.column * width / layout.columnCount
                val expectedRight = (cell.column + cell.columnSpan) * width / layout.columnCount
                val expectedTop = cell.row * height / layout.rows.size
                val expectedBottom = (cell.row + cell.rowSpan) * height / layout.rows.size
                assertEquals("$profile left edge", expectedLeft, rendered.view.left)
                assertEquals("$profile right edge", expectedRight, rendered.view.right)
                assertEquals("$profile top edge", expectedTop, rendered.view.top)
                assertEquals("$profile bottom edge", expectedBottom, rendered.view.bottom)
                for (row in cell.row until cell.row + cell.rowSpan) {
                    for (column in cell.column until cell.column + cell.columnSpan) {
                        assertFalse("$profile duplicate target at $row,$column", occupied[row][column])
                        occupied[row][column] = true
                    }
                }
            }
            assertTrue("$profile grid has an uncovered cell", occupied.all { row -> row.all { it } })
        }
    }

    @Test
    fun selectionUsesLockedSlotAndFallsBackToOriginalSlotOrLabel() {
        val layout = bundledEditor("水彩")
        withIconTheme(mapOf("text_editor.select" to unlockedSvg, "text_editor.select_locked" to lockedSvg)) {
            val ui = configuredUi(layout)
            val button = selectionButton(layout, ui)
            assertIcon(button)
            val unlocked = (button.imageView.drawable as BitmapDrawable).bitmap

            ui.updateSelection(hasSelection = true, userSelection = false)
            assertTrue(button.isActivated)
            assertIcon(button)
            val locked = (button.imageView.drawable as BitmapDrawable).bitmap
            // Compare cached bitmap identity: legacy Robolectric does not rasterize SVG pixels.
            assertNotSame("selection should use its locked icon slot", unlocked, locked)

            ui.updateSelection(hasSelection = false, userSelection = true)
            assertTrue(button.isActivated)
            assertSame(locked, (button.imageView.drawable as BitmapDrawable).bitmap)
            ui.updateSelection(hasSelection = false, userSelection = false)
            assertFalse(button.isActivated)
            assertSame(unlocked, (button.imageView.drawable as BitmapDrawable).bitmap)
        }

        withIconTheme(mapOf("text_editor.select" to unlockedSvg)) {
            val ui = configuredUi(layout)
            val button = selectionButton(layout, ui)
            val unlocked = (button.imageView.drawable as BitmapDrawable).bitmap
            ui.updateSelection(hasSelection = true, userSelection = false)
            assertIcon(button)
            assertSame("missing locked slot should retain the normal slot", unlocked,
                (button.imageView.drawable as BitmapDrawable).bitmap)
        }

        withIconTheme(emptyMap()) {
            val ui = configuredUi(layout)
            val button = selectionButton(layout, ui)
            assertText(button, "选择")
            ui.updateSelection(hasSelection = true, userSelection = false)
            assertTrue(button.isActivated)
            assertText(button, "选择")
        }
    }

    @Test
    fun foxySelectionStateDoesNotRewriteItsIndependentSwipeBar() {
        val rootJson = bundledRoot("水彩")
        val swipeBar = requireNotNull(TextEditingLayoutLoader.fromJson(rootJson, "text_editor", listOf("rime")))
        val ui = FoxyTextEditingUi(
            viewContext = context,
            viewTheme = ThemePreset.PixelLight,
            rippleEnabled = false,
            borderEnabled = false,
            buttonRadius = 0f,
            stepDp = 18,
            longPressDelayMs = 300,
            promptMove = "",
            promptLongPress = "",
            promptSelecting = "",
            promptReleaseSelection = "",
            configuredLayout = swipeBar
        )
        val root = ui.root as ViewGroup
        val bottomBar = root.getChildAt(1) as ViewGroup
        val buttons = (0 until bottomBar.childCount).flatMap { rowIndex ->
            val row = bottomBar.getChildAt(rowIndex) as ViewGroup
            (0 until row.childCount).map { childIndex -> row.getChildAt(childIndex) as TextEditingButton }
        }
        assertEquals(6, buttons.size)
        val before = buttons.map { Triple(it.isActivated, it.isEnabled, it.textView.text.toString()) }

        ui.updateSelection(hasSelection = true, userSelection = false)
        assertTrue(ui.selectButton.isActivated)
        assertEquals(before, buttons.map { Triple(it.isActivated, it.isEnabled, it.textView.text.toString()) })
        ui.updateSelection(hasSelection = false, userSelection = false)
        assertFalse(ui.selectButton.isActivated)
        assertEquals(before, buttons.map { Triple(it.isActivated, it.isEnabled, it.textView.text.toString()) })
    }

    @Test
    fun configuredMacroKeyDispatchesPhysicalSwipeMacros() {
        val preference = AppPrefs.getInstance().keyboard.swipeSymbolDirection
        val previous = preference.getValue()
        try {
            preference.setValue(SwipeSymbolDirection.Auto)
            val layout = swipeEditor()
            val actions = mutableListOf<KeyAction>()
            val ui = ConfiguredTextEditingUi(
                context, ThemePreset.PixelLight, false, false, 0f, layout, { action -> actions.add(action) }
            )
            val button = ui.keyViews.single().view
            assertTrue(button.swipeEnabled)
            val listener = requireNotNull(button.onGestureListener)
            fun swipe(totalY: Int, cancelled: Boolean = false) = CustomGestureView.Event(
                CustomGestureView.GestureType.Up, consumed = true,
                x = 0f, y = totalY.toFloat(), countX = 0, countY = totalY,
                totalX = 0, totalY = totalY, cancelled = cancelled
            )
            assertTrue(listener.onGesture(button, swipe(-40)))
            assertEquals(layout.rows.single().single().swipeBehavior?.upMacro, actions.single())
            assertTrue(listener.onGesture(button, swipe(40)))
            assertEquals(layout.rows.single().single().swipeBehavior?.downMacro, actions[1])
            assertFalse(listener.onGesture(button, swipe(-40, cancelled = true)))
            assertEquals(2, actions.size)
        } finally {
            preference.setValue(previous)
        }
    }

    @Test
    fun selectionLockAddsShiftToMacroNavigationWithoutDuplicatingExplicitShift() {
        val shift = KeyRef.Android(KeyEvent.KEYCODE_SHIFT_LEFT)
        val left = KeyRef.Android(KeyEvent.KEYCODE_DPAD_LEFT)
        val a = KeyRef.Android(KeyEvent.KEYCODE_A)
        val transformer = TextEditingMacroSelectionLock()
        val tap = MacroStep.Tap(listOf(a, left))
        assertEquals(
            listOf(
                MacroStep.Tap(listOf(a)), MacroStep.Down(listOf(shift)),
                MacroStep.Tap(listOf(left)), MacroStep.Up(listOf(shift))
            ),
            transformer.transform(tap, selectionLocked = true)
        )
        val shortcut = MacroStep.Shortcut(emptyList(), left)
        assertEquals(
            MacroStep.Shortcut(listOf(shift), left),
            transformer.transform(shortcut, selectionLocked = true).single()
        )

        val explicitShift = TextEditingMacroSelectionLock()
        val down = MacroStep.Down(listOf(shift))
        assertEquals(listOf(down), explicitShift.transform(down, selectionLocked = true))
        assertEquals(listOf(tap), explicitShift.transform(tap, selectionLocked = true))
        assertEquals(listOf(shortcut), explicitShift.transform(shortcut, selectionLocked = true))
        assertEquals(
            listOf(MacroStep.Up(listOf(shift))),
            explicitShift.transform(MacroStep.Up(listOf(shift)), selectionLocked = true)
        )
        assertEquals(
            transformer.transform(tap, selectionLocked = true),
            explicitShift.transform(tap, selectionLocked = true)
        )
        assertEquals(listOf(shortcut), explicitShift.transform(shortcut, selectionLocked = false))
    }

    @Test
    fun schemaMenuKeyActionMapsToTheCommonMenuButtonAction() {
        assertEquals(
            RimeSchemaMenuAction.id,
            textEditingButtonActionId(KeyAction.ShowRimeSchemaMenuAction)
        )
    }
}
