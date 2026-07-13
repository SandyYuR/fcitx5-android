/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.graphics.Typeface
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.serialization.encodeToString
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.config.ButtonsLayoutConfig
import org.fcitx.fcitx5.android.input.config.ButtonIconFile
import org.fcitx.fcitx5.android.input.config.ConfigProviders
import org.fcitx.fcitx5.android.input.config.ConfigProvider
import org.fcitx.fcitx5.android.input.config.ConfigurableButton
import org.fcitx.fcitx5.android.input.keyboard.KeyRef
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutDataManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.MacroEditorActivity
import org.fcitx.fcitx5.android.ui.main.settings.behavior.utils.LayoutJsonUtils
import org.fcitx.fcitx5.android.utils.serializable
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageDrawable
import java.io.File

private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

private fun ConfigurableButton.normalizedIcon(): ConfigurableButton =
    if (ButtonIconFile.isFileIcon(icon)) copy(icon = ButtonIconFile.toRelative(icon!!)) else this

/**
 * Unified activity for customizing buttons in both Kawaii Bar and Status Area.
 * Uses a grid layout similar to Status Area for button display.
 */
class ButtonsCustomizerActivity : AppCompatActivity() {

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            setTitleTextColor(styledColor(android.R.attr.textColorPrimary))
            elevation = dp(4f)
        }
    }

    private val scrollView by lazy {
        androidx.core.widget.NestedScrollView(this).apply {
            isFillViewport = true
            isNestedScrollingEnabled = true
        }
    }

    private val mainContainer by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
    }

    private val recyclerView by lazy {
        RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@ButtonsCustomizerActivity, 4)
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
            isNestedScrollingEnabled = false
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
                supportsChangeAnimations = false
            }
        }
    }

    private val ui by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                toolbar,
                LinearLayout.LayoutParams(matchParent, wrapContent)
            )
            addView(
                scrollView,
                LinearLayout.LayoutParams(matchParent, matchParent)
            )
        }
    }

    private val provider: ConfigProvider = ConfigProviders.provider
    private val theme: Theme by lazy { ThemeManager.activeTheme }

    // Combined list: Section headers + buttons
    private val items = mutableListOf<ListItem>()
    private var originalItems = listOf<ListItem>()
    private var saveMenuItem: MenuItem? = null
    private var adapter: CombinedAdapter? = null
    private var touchHelper: ItemTouchHelper? = null

    // Available button definitions (all buttons can be used in either section)
    // Note: input_method_options is fixed at the end of Status Area and not configurable
    private val availableButtons = listOf(
        ButtonDefinition("undo", R.drawable.ic_baseline_undo_24, R.string.undo),
        ButtonDefinition("redo", R.drawable.ic_baseline_redo_24, R.string.redo),
        ButtonDefinition("cursor_move", R.drawable.ic_cursor_move, R.string.text_editing),
        ButtonDefinition("floating_toggle", R.drawable.ic_floating_toggle_24, R.string.floating_keyboard),
        ButtonDefinition("clipboard", R.drawable.ic_clipboard, R.string.clipboard),
        ButtonDefinition("language_switch", R.drawable.ic_baseline_language_24, R.string.language_switch),
        ButtonDefinition("theme", R.drawable.ic_baseline_palette_24, R.string.theme),
        ButtonDefinition("reload_config", R.drawable.ic_baseline_sync_24, R.string.reload_config),
        ButtonDefinition("virtual_keyboard", R.drawable.ic_baseline_keyboard_24, R.string.virtual_keyboard),
        ButtonDefinition("one_handed_keyboard", R.drawable.ic_baseline_keyboard_tab_24, R.string.one_handed_keyboard)
    )

    /**
     * Set of built-in button IDs that cannot be deleted or have custom labels.
     * These are the core buttons that are always available in the app.
     */
    private val builtInButtonIds = availableButtons.map { it.id }.toSet()

    data class ButtonDefinition(
        val id: String,
        val iconRes: Int,
        val labelRes: Int
    )

    // Sealed class for list items
    sealed class ListItem {
        data class ButtonItem(val button: ConfigurableButton, val section: Section) : ListItem()
        data class AddButtonItem(val buttonDef: ButtonDefinition) : ListItem()
        data object AddButtonPlaceholder : ListItem() // "+" button for Kawaii Bar
        data object StatusAreaAddButtonPlaceholder : ListItem() // "+" button for Status Area
    }

    enum class Section {
        KawaiiBar,
        StatusArea,
        AddButtons
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(ui)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.edit_buttons)

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        loadState()
        buildUi()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, "${getString(R.string.save)}")
        saveMenuItem?.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        updateSaveButtonState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_SAVE_ID -> {
            saveConfig()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun loadState() {
        // Load unified buttons layout config
        val snapshot = ConfigProviders.readButtonsLayoutConfig<ButtonsLayoutConfig>()
        val config = snapshot?.value ?: ButtonsLayoutConfig.default()

        // Build combined list
        items.clear()
        // Kawaii Bar section buttons
        config.kawaiiBarButtons.forEach { button ->
            items.add(ListItem.ButtonItem(button, Section.KawaiiBar))
        }
        // Add "+" button for Kawaii Bar
        items.add(ListItem.AddButtonPlaceholder)
        
        // Status Area section buttons
        // Filter out input_method_options as it's always added automatically at the end
        config.statusAreaButtons.filter { it.id != "input_method_options" }.forEach { button ->
            items.add(ListItem.ButtonItem(button, Section.StatusArea))
        }
        // Add "+" button for Status Area
        items.add(ListItem.StatusAreaAddButtonPlaceholder)
        
        updateAddButtonsSection()

        originalItems = items.toList()
    }

    private fun updateAddButtonsSection() {
        // Remove existing AddButtons section
        items.removeAll { it is ListItem.AddButtonItem }

        // Get all current button IDs
        val currentIds = items.filterIsInstance<ListItem.ButtonItem>().map { it.button.id }

        // Find buttons that can be added
        val availableIds = availableButtons.filter { it.id !in currentIds }

        if (availableIds.isNotEmpty()) {
            availableIds.forEach { buttonDef ->
                items.add(ListItem.AddButtonItem(buttonDef))
            }
        }
    }

    private fun buildUi() {
        mainContainer.removeAllViews()

        // Add hint at the top
        val usageHint = TextView(this).apply {
            text = getString(R.string.buttons_customizer_hint)
            textSize = 12f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(0, 0, 0, dp(8))
        }
        mainContainer.addView(usageHint)

        // Setup RecyclerView
        adapter = CombinedAdapter()
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: android.graphics.Rect, view: android.view.View, parent: RecyclerView, state: RecyclerView.State) {
                val position = parent.getChildAdapterPosition(view)
                if (position >= 0 && position < items.size) {
                    when (items[position]) {
                        is ListItem.ButtonItem, is ListItem.AddButtonItem,
                        ListItem.AddButtonPlaceholder, ListItem.StatusAreaAddButtonPlaceholder -> {
                            outRect.top = dp(4)
                            outRect.bottom = dp(4)
                            outRect.left = dp(4)
                            outRect.right = dp(4)
                        }
                    }
                }
            }
        })

        mainContainer.addView(recyclerView)
        scrollView.addView(
            mainContainer,
            android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP
            }
        )

        setupDragAndDrop()
    }

    private fun setupDragAndDrop() {
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.getAbsoluteAdapterPosition()
                var toPosition = target.getAbsoluteAdapterPosition()

                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION ||
                    fromPosition >= items.size || toPosition >= items.size
                ) {
                    return false
                }

                val fromItem = items[fromPosition]
                val toItem = items[toPosition]

                // Only allow moving ButtonItem
                if (fromItem !is ListItem.ButtonItem) {
                    return false
                }

                val kawaiiBarEndIndex = items.indexOfFirst { it is ListItem.AddButtonPlaceholder }
                val statusAreaEndIndex = items.indexOfFirst { it is ListItem.StatusAreaAddButtonPlaceholder }

                var insertPosition = toPosition
                var targetSection: Section

                // Determine target section and insert position based on drop position
                when {
                    // Dropping on KawaiiBar "+" placeholder
                    toItem is ListItem.AddButtonPlaceholder -> {
                        val dragCenterX = getViewCenterX(viewHolder.itemView)
                        val plusCenterX = getViewCenterX(target.itemView)
                        val (position, section) = determineDropPositionOnKawaiiBarPlus(
                            dragCenterX, plusCenterX, kawaiiBarEndIndex
                        )
                        insertPosition = position
                        targetSection = section
                    }
                    // Dropping on StatusArea "+" placeholder -> insert before it (in StatusArea)
                    toItem is ListItem.StatusAreaAddButtonPlaceholder -> {
                        val dragCenterX = getViewCenterX(viewHolder.itemView)
                        val plusCenterX = getViewCenterX(target.itemView)
                        val (position, section) = determineDropPositionOnStatusAreaPlus(
                            dragCenterX, plusCenterX, statusAreaEndIndex
                        )
                        insertPosition = position
                        targetSection = section
                    }
                    // Dropping on an available item -> move to AddButtons section
                    toItem is ListItem.AddButtonItem -> {
                        targetSection = Section.AddButtons
                        insertPosition = toPosition
                    }
                    // Dropping on a regular button - use drag position relative to target button center
                    else -> {
                        val dragCenterX = getViewCenterX(viewHolder.itemView)
                        val targetCenterX = getViewCenterX(target.itemView)
                        insertPosition = determineInsertPositionOnButton(dragCenterX, targetCenterX, toPosition)
                        targetSection = determineSectionByPosition(insertPosition)
                    }
                }

                // Don't allow dropping after StatusArea "+" unless target is AddButtons section
                if (targetSection != Section.AddButtons && statusAreaEndIndex >= 0 && insertPosition > statusAreaEndIndex) {
                    insertPosition = statusAreaEndIndex
                }

                // Move item to new position
                val item = items.removeAt(fromPosition)
                // Adjust insertPosition if the item was removed from before the target position
                val adjustedInsertPosition = if (fromPosition < insertPosition) insertPosition - 1 else insertPosition
                // Update the section if moving to a different section
                val updatedItem = if (item is ListItem.ButtonItem && item.section != targetSection) {
                    ListItem.ButtonItem(item.button.copy(), targetSection)
                } else {
                    item
                }
                items.add(adjustedInsertPosition, updatedItem)
                adapter?.notifyItemMoved(fromPosition, adjustedInsertPosition)

                updateSaveButtonState()
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    // Give immediate pickup feedback so drag start feels deliberate.
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    viewHolder.itemView.animate().cancel()
                    viewHolder.itemView.alpha = 0.92f
                    viewHolder.itemView.animate()
                        .scaleX(0.96f)
                        .scaleY(0.96f)
                        .translationZ(dp(8f))
                        .setDuration(120L)
                        .start()
                    viewHolder.itemView.setBackgroundColor(
                        this@ButtonsCustomizerActivity.styledColor(android.R.attr.colorControlHighlight)
                    )
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Restore original appearance with a short settle animation.
                viewHolder.itemView.animate().cancel()
                viewHolder.itemView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(0f)
                    .setDuration(120L)
                    .start()
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // Enable long press to start drag
            override fun isLongPressDragEnabled(): Boolean {
                return true
            }

            override fun getMoveThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                return 0.18f
            }

            override fun getBoundingBoxMargin(): Int {
                return dp(12)
            }
        }).apply {
            attachToRecyclerView(recyclerView)
        }
    }

    /**
     * Determine which section a position belongs to based on placeholders.
     * Positions before AddButtonPlaceholder belong to KawaiiBar.
     * Positions after AddButtonPlaceholder (but before StatusAreaAddButtonPlaceholder) belong to StatusArea.
     */
    private fun determineSectionByPosition(position: Int): Section {
        val kawaiiBarEndIndex = items.indexOfFirst { it is ListItem.AddButtonPlaceholder }
        val statusAreaEndIndex = items.indexOfFirst { it is ListItem.StatusAreaAddButtonPlaceholder }

        return when {
            // Before or at KawaiiBar "+" button position -> KawaiiBar
            position <= kawaiiBarEndIndex -> Section.KawaiiBar
            // After KawaiiBar "+" but before StatusArea "+" -> StatusArea
            position < statusAreaEndIndex -> Section.StatusArea
            // After StatusArea "+" -> AddButtons section
            else -> Section.AddButtons
        }
    }

    /**
     * Calculate the center X coordinate of a view in window coordinates.
     */
    private fun getViewCenterX(view: View): Float {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return location[0] + view.width / 2f
    }

    /**
     * Determine insert position and target section when dropping on KawaiiBar "+" placeholder.
     * @param dragCenterX Center X of the dragged view
     * @param plusCenterX Center X of the "+" placeholder
     * @param kawaiiBarEndIndex Position of KawaiiBar "+" placeholder
     * @return Pair of (insertPosition, targetSection)
     */
    private fun determineDropPositionOnKawaiiBarPlus(
        dragCenterX: Float,
        plusCenterX: Float,
        kawaiiBarEndIndex: Int
    ): Pair<Int, Section> {
        return if (dragCenterX < plusCenterX) {
            // Left of "+" -> KawaiiBar (insert before "+")
            Pair(kawaiiBarEndIndex, Section.KawaiiBar)
        } else {
            // Right of "+" -> StatusArea (insert after "+")
            Pair(kawaiiBarEndIndex + 1, Section.StatusArea)
        }
    }

    /**
     * Determine insert position and target section when dropping on StatusArea "+" placeholder.
     * Left side keeps it in StatusArea; right side moves it to AddButtons section.
     */
    private fun determineDropPositionOnStatusAreaPlus(
        dragCenterX: Float,
        plusCenterX: Float,
        statusAreaEndIndex: Int
    ): Pair<Int, Section> {
        return if (dragCenterX < plusCenterX) {
            Pair(statusAreaEndIndex, Section.StatusArea)
        } else {
            Pair((statusAreaEndIndex + 1).coerceAtMost(items.size), Section.AddButtons)
        }
    }

    /**
     * Determine insert position when dropping on a regular button.
     * @param dragCenterX Center X of the dragged view
     * @param targetCenterX Center X of the target button
     * @param toPosition Position of the target button
     * @return Insert position (toPosition if left of center, toPosition + 1 if right of center)
     */
    private fun determineInsertPositionOnButton(
        dragCenterX: Float,
        targetCenterX: Float,
        toPosition: Int
    ): Int {
        return if (dragCenterX > targetCenterX) {
            toPosition + 1
        } else {
            toPosition
        }
    }

    private fun openAddButtonDialog(buttonDef: ButtonDefinition) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_button_to_section_title))
            .setItems(arrayOf(getString(R.string.kawaii_bar_section), getString(R.string.status_area_section))) { _, which ->
                val newButton = ConfigurableButton(
                    id = buttonDef.id,
                    icon = null,
                    label = null,
                )

                val targetSection = if (which == 0) Section.KawaiiBar else Section.StatusArea
                // Find the position before the section's "+" placeholder
                val insertPosition = if (targetSection == Section.KawaiiBar) {
                    val kawaiiBarEndIndex = items.indexOfFirst { it is ListItem.AddButtonPlaceholder }
                    if (kawaiiBarEndIndex >= 0) kawaiiBarEndIndex else items.size
                } else {
                    val statusAreaEndIndex = items.indexOfFirst { it is ListItem.StatusAreaAddButtonPlaceholder }
                    if (statusAreaEndIndex >= 0) statusAreaEndIndex else items.size
                }

                items.add(insertPosition, ListItem.ButtonItem(newButton, targetSection))
                adapter?.notifyItemInserted(insertPosition)
                updateAddButtonsSection()
                adapter?.notifyDataSetChanged() // 更新 AddButtons 区域
                updateSaveButtonState()
            }
            .show()
    }

    // Macro editor launcher

    private val macroJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var pendingMacroCallback: ((List<MacroStep>?) -> Unit)? = null
    private val macroEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val json = result.data?.getStringExtra(MacroEditorActivity.EXTRA_MACRO_RESULT_JSON)
            val steps = if (json != null) {
                try { macroJson.decodeFromString<List<MacroStep>>(json) } catch (_: Exception) { null }
            } else null
            pendingMacroCallback?.invoke(steps ?: MacroEditorActivity.fromStepsExtra(
                result.data?.serializable<ArrayList<Map<*, *>>>(MacroEditorActivity.EXTRA_MACRO_RESULT)
            ))
        }
    }

    // File picker for custom icon
    private var pendingIconCallback: ((String?) -> Unit)? = null
    private val iconPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                // Get the original filename from the content provider
                var fileName = "icon"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) fileName = cursor.getString(nameIdx)
                    }
                }
                val extDir = getExternalFilesDir(null) ?: filesDir
                val iconDir = File(extDir, ButtonIconFile.DIR)
                iconDir.mkdirs()
                val destFile = File(iconDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                // Store a portable relative path so the config survives export/import
                // across build variants with different applicationIds.
                pendingIconCallback?.invoke("${ButtonIconFile.PREFIX}${ButtonIconFile.DIR}/${destFile.name}")
            } catch (_: Exception) {
                pendingIconCallback?.invoke(null)
            }
        } else {
            pendingIconCallback?.invoke(null)
        }
    }

    private fun openMacroEditor(steps: List<MacroStep>?, callback: (List<MacroStep>?) -> Unit) {
        pendingMacroCallback = callback
        val intent = android.content.Intent(this, MacroEditorActivity::class.java).apply {
            val stepsList = steps ?: emptyList()
            putExtra(MacroEditorActivity.EXTRA_MACRO_STEPS_JSON, macroJson.encodeToString(stepsList))
            // Also include Map format for backward compatibility
            putExtra(MacroEditorActivity.EXTRA_MACRO_STEPS, MacroEditorActivity.toStepsExtra(stepsList))
            putExtra(MacroEditorActivity.EXTRA_EVENT_TYPE, getString(R.string.edit_button_macro_event_type))
            putStringArrayListExtra(MacroEditorActivity.EXTRA_LAYOUT_TARGETS, ArrayList(availableLayoutTargets()))
        }
        macroEditorLauncher.launch(intent)
    }

    private fun availableLayoutTargets(): List<String> = runCatching {
        val dataManager = LayoutDataManager(this)
        dataManager.loadFromFile(ConfigProviders.provider.textKeyboardLayoutFile())
        val entryKeys = dataManager.entries.keys.toList()
        val layerAliases = entryKeys.mapNotNull { key ->
            val subMode = key.substringAfter(':', "")
            if (subMode.isNotEmpty() && LayoutJsonUtils.isLayerSubModeLabel(subMode)) {
                LayoutJsonUtils.childNameFromLayerLabel(subMode)
            } else {
                null
            }
        }
        (layerAliases + entryKeys).distinct().sortedWith(
            compareBy<String> { if (it.contains(':')) 1 else 0 }.thenBy { it }
        )
    }.getOrDefault(emptyList())

    private fun labeledInput(label: String, hint: String, value: String?, parent: LinearLayout): EditText {
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        TextView(parent.context).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        }.also { row.addView(it) }
        val editText = EditText(parent.context).apply {
            this.hint = hint
            setText(value ?: "")
            layoutParams = LinearLayout.LayoutParams(0, wrapContent).apply { weight = 1f; marginStart = dp(8) }
        }
        row.addView(editText)
        parent.addView(row)
        return editText
    }

    private fun openButtonEditor(button: ConfigurableButton, position: Int, section: Section) {
        val isBuiltIn = button.id in builtInButtonIds

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Macro steps data
        var currentSteps = button.macroSteps
        lateinit var stepsSummaryText: TextView
        fun stepsSummary(): String {
            val s = currentSteps ?: return "(none)"
            if (s.isEmpty()) return "(none)"
            return s.joinToString(" → ") { step ->
                when (step) {
                    is MacroStep.Tap -> "Tap ${step.keys.joinToString { (it as? KeyRef.Fcitx)?.code ?: it.toString() }}"
                    is MacroStep.Text -> "Text: ${step.text.take(12)}"
                    is MacroStep.AppAction -> "App: ${step.id}"
                    is MacroStep.Shortcut -> "Shortcut: ${step.key}"
                    is MacroStep.Edit -> "Edit: ${step.action}"
                    is MacroStep.Down -> "Down: ${step.keys.size} key(s)"
                    is MacroStep.Up -> "Up: ${step.keys.size} key(s)"
                    is MacroStep.LayerSwitch -> "Layer: ${step.mode}→${step.target}"
                }
            }
        }

        // ID input (only for non-built-in)
        var idInput: EditText? = null
        if (!isBuiltIn) {
            idInput = labeledInput(getString(R.string.edit_button_label_id), getString(R.string.edit_button_hint_id), button.id, dialogView)
        }

        // Text icon input
        val textInput = labeledInput(getString(R.string.edit_button_label_text), getString(R.string.edit_button_hint_text), button.text, dialogView)

        // Custom label input (only for non-built-in)
        var labelInput: EditText? = null
        if (!isBuiltIn) {
            labelInput = labeledInput(getString(R.string.edit_button_label_label), getString(R.string.custom_label_hint), button.label, dialogView)
        }

        // File icon picker
        var iconPath = button.icon
        lateinit var iconPathText: TextView
        val iconBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val pickIconBtn = Button(this).apply {
            text = getString(R.string.edit_button_pick_icon)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, wrapContent).apply { weight = 1f; topMargin = dp(4) }
            setOnClickListener {
                pendingIconCallback = { path ->
                    if (path != null) {
                        iconPath = path
                        iconPathText.text = path.removePrefix("file:").takeLast(30)
                    }
                }
                iconPickerLauncher.launch(arrayOf("image/png", "image/webp"))
            }
        }
        val clearIconBtn = Button(this).apply {
            text = "✕"
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent).apply { marginStart = dp(4); topMargin = dp(4) }
            setOnClickListener {
                iconPath = null
                iconPathText.text = getString(R.string.edit_button_no_custom_icon)
            }
        }
        iconBtnRow.addView(pickIconBtn)
        iconBtnRow.addView(clearIconBtn)
        dialogView.addView(iconBtnRow)
        iconPathText = TextView(this).apply {
            text = if (button.icon != null && button.icon.startsWith("file:"))
                button.icon.removePrefix("file:").takeLast(30) else getString(R.string.edit_button_no_custom_icon)
            textSize = 11f
            setTextColor(styledColor(android.R.attr.textColorSecondary))
            setPadding(dp(8), dp(2), 0, 0)
        }
        dialogView.addView(iconPathText)

        // Edit Macro Steps button
        if (!isBuiltIn) {
            val macroBtn = Button(this).apply {
                text = getString(R.string.edit_button_macro)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply { topMargin = dp(4) }
                setOnClickListener {
                    openMacroEditor(currentSteps) { newSteps ->
                        if (newSteps != null) {
                            currentSteps = newSteps.ifEmpty { null }
                            stepsSummaryText.text = stepsSummary()
                        }
                    }
                }
            }
            dialogView.addView(macroBtn)
        }

        // Macro steps summary
        if (!isBuiltIn) {
            stepsSummaryText = TextView(this).apply {
                text = stepsSummary()
                textSize = 11f
                setTextColor(styledColor(android.R.attr.textColorSecondary))
                setPadding(dp(8), dp(4), 0, 0)
            }
            dialogView.addView(stepsSummaryText)
        }

        // Delete button (only for non-built-in buttons) - shown as neutral button in dialog
        if (!isBuiltIn) {
            dialogView.addView(View(this).apply { layoutParams = ViewGroup.LayoutParams(0, dp(8)) })
        }

        AlertDialog.Builder(this)
            .setTitle("${getString(R.string.edit_button_title)} ${button.id}")
            .setView(dialogView)
            .apply {
                if (!isBuiltIn) {
                    setNeutralButton(R.string.delete) { dialog, _ ->
                        AlertDialog.Builder(this@ButtonsCustomizerActivity)
                            .setTitle(R.string.delete_button_title)
                            .setMessage(R.string.delete_button_confirm)
                            .setPositiveButton(R.string.delete) { _, _ ->
                                items.removeAt(position)
                                adapter?.notifyItemRemoved(position)
                                updateAddButtonsSection()
                                adapter?.notifyDataSetChanged()
                                updateSaveButtonState()
                                dialog.dismiss()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                val customText = textInput.text?.toString()?.trim()?.ifEmpty { null }
                val customLabel = if (isBuiltIn) null else labelInput?.text?.toString()?.trim()?.ifEmpty { null }

                val newId = idInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: button.id
                // Validate ID
                val reservedIds = setOf("more", "input_method_options")
                if (newId.isEmpty()) {
                    Toast.makeText(this@ButtonsCustomizerActivity, R.string.edit_button_id_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newId in reservedIds) {
                    Toast.makeText(this@ButtonsCustomizerActivity, R.string.edit_button_id_reserved, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newId != button.id) {
                    val duplicate = items.filterIsInstance<ListItem.ButtonItem>()
                        .filterIndexed { idx, _ -> idx != position }
                        .any { it.button.id == newId }
                    if (duplicate) {
                        Toast.makeText(this@ButtonsCustomizerActivity, R.string.edit_button_id_duplicate, Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }

                val updatedButton = ConfigurableButton(
                    id = newId,
                    icon = iconPath,
                    label = customLabel,
                    text = customText,
                    macroSteps = currentSteps
                )

                items[position] = ListItem.ButtonItem(updatedButton, section)
                adapter?.notifyItemChanged(position)
                updateSaveButtonState()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun generateCustomButtonId(): String {
        val existingCustomIds = items.filterIsInstance<ListItem.ButtonItem>()
            .map { it.button.id }
            .filter { it !in builtInButtonIds }
        var idx = 1
        while ("custom_$idx" in existingCustomIds) idx++
        return "custom_$idx"
    }

    private fun saveConfig() {
        // Extract buttons for each section
        val kawaiiBarButtons = items.filterIsInstance<ListItem.ButtonItem>()
            .filter { it.section == Section.KawaiiBar }
            .map { it.button }

        val statusAreaButtons = items.filterIsInstance<ListItem.ButtonItem>()
            .filter { it.section == Section.StatusArea }
            .map { it.button }

        // Save unified config
        val buttonsLayoutFile = provider.buttonsLayoutConfigFile()
        if (buttonsLayoutFile != null) {
            saveUnifiedConfigToFile(buttonsLayoutFile, kawaiiBarButtons, statusAreaButtons)
        }

        originalItems = items.toList()
        updateSaveButtonState()
    }

    private fun saveUnifiedConfigToFile(file: File, kawaiiBarButtons: List<ConfigurableButton>, statusAreaButtons: List<ConfigurableButton>) {
        try {
            // Ensure config directory exists
            file.parentFile?.mkdirs()

            // Create unified config, normalizing custom icon paths to the portable
            // relative form so exports remain valid across build variants.
            val config = ButtonsLayoutConfig(
                kawaiiBarButtons = kawaiiBarButtons.map { it.normalizedIcon() },
                statusAreaButtons = statusAreaButtons.map { it.normalizedIcon() }
            )

            val jsonContent = prettyJson.encodeToString(config) + "\n"
            file.writeText(jsonContent)
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.save_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateSaveButtonState() {
        saveMenuItem?.isEnabled = items != originalItems
    }

    private val VIEW_TYPE_BUTTON_ITEM = 1
    private val VIEW_TYPE_ADD_BUTTON_ITEM = 2
    private val VIEW_TYPE_ADD_PLACEHOLDER = 3

    private inner class CombinedAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ListItem.ButtonItem -> VIEW_TYPE_BUTTON_ITEM
                is ListItem.AddButtonItem -> VIEW_TYPE_ADD_BUTTON_ITEM
                is ListItem.AddButtonPlaceholder, is ListItem.StatusAreaAddButtonPlaceholder -> VIEW_TYPE_ADD_PLACEHOLDER
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_BUTTON_ITEM -> createButtonViewHolder(parent)
                VIEW_TYPE_ADD_BUTTON_ITEM -> createAddButtonViewHolder(parent)
                VIEW_TYPE_ADD_PLACEHOLDER -> createAddPlaceholderViewHolder(parent)
                else -> throw IllegalArgumentException("Unknown view type: $viewType")
            }
        }

        private fun createAddPlaceholderViewHolder(parent: ViewGroup): AddPlaceholderViewHolder {
            val buttonEntryUi = ButtonEntryUi(this@ButtonsCustomizerActivity, theme, "", 0)
            return AddPlaceholderViewHolder(buttonEntryUi)
        }

        private fun createButtonViewHolder(parent: ViewGroup): ButtonViewHolder {
            val buttonEntryUi = ButtonEntryUi(this@ButtonsCustomizerActivity, theme, "", 0)
            return ButtonViewHolder(buttonEntryUi)
        }

        private fun createAddButtonViewHolder(parent: ViewGroup): AddButtonViewHolder {
            val buttonEntryUi = ButtonEntryUi(this@ButtonsCustomizerActivity, theme, "", 0)
            return AddButtonViewHolder(buttonEntryUi)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // Reset any drag visual feedback that may have been applied
            holder.itemView.alpha = 1.0f
            holder.itemView.translationZ = 0f
            holder.itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            
            val item = items[position]
            when (holder) {
                is AddPlaceholderViewHolder -> {
                    val isKawaiiBar = item is ListItem.AddButtonPlaceholder
                    // Use empty label, "+" as circle text
                    holder.ui.setButton("", 0, "+")
                    holder.ui.root.setOnClickListener {
                        // Show add button dialog
                        val currentIds = items.filterIsInstance<ListItem.ButtonItem>().map { it.button.id }.toSet()
                        val availableIds = availableButtons.filter { button ->
                            button.id !in currentIds
                        }.map { it.id }
                        
                        // Show a popup menu with available buttons
                        val popup = android.widget.PopupMenu(this@ButtonsCustomizerActivity, holder.ui.root)
                        availableIds.forEach { id ->
                            val buttonDef = availableButtons.find { it.id == id }
                            popup.menu.add(buttonDef?.let { getString(it.labelRes) } ?: id)
                        }
                        val customLabel = getString(R.string.edit_button_new_custom)
                        popup.menu.add(customLabel)
                        popup.setOnMenuItemClickListener { menuItem ->
                            if (menuItem.title == customLabel) {
                                val customId = generateCustomButtonId()
                                val targetSection = if (isKawaiiBar) Section.KawaiiBar else Section.StatusArea
                                val newButton = ConfigurableButton(
                                    id = customId,
                                    text = null,
                                    macroSteps = listOf(MacroStep.Text(""))
                                )
                                items.add(position, ListItem.ButtonItem(newButton, targetSection))
                                adapter?.notifyItemInserted(position)
                                updateAddButtonsSection()
                                adapter?.notifyDataSetChanged()
                                updateSaveButtonState()
                                // Open editor immediately so user can configure
                                recyclerView.post {
                                    openButtonEditor(newButton, position, targetSection)
                                }
                            } else {
                                val buttonDef = availableButtons.find { getString(it.labelRes) == menuItem.title }
                                if (buttonDef != null) {
                                    val targetSection = if (isKawaiiBar) Section.KawaiiBar else Section.StatusArea
                                    val newButton = ConfigurableButton(
                                        id = buttonDef.id,
                                        icon = null,
                                        label = null,
                                    )
                                    items.add(position, ListItem.ButtonItem(newButton, targetSection))
                                    adapter?.notifyItemInserted(position)
                                    updateAddButtonsSection()
                                    adapter?.notifyDataSetChanged()
                                    updateSaveButtonState()
                                }
                            }
                            true
                        }
                        popup.show()
                    }
                }
                is ButtonViewHolder -> {
                    val buttonItem = item as ListItem.ButtonItem
                    val b = buttonItem.button
                    val buttonDef = availableButtons.find { it.id == b.id }
                    val label = b.label ?: buttonDef?.let { getString(it.labelRes) } ?: b.id
                    val displayIconRes = when {
                        !b.text.isNullOrEmpty() -> 0
                        b.icon != null && b.icon.startsWith("file:") -> 0
                        b.icon != null -> {
                            val resId = resources.getIdentifier(b.icon, "drawable", packageName)
                            if (resId != 0) resId else (buttonDef?.iconRes ?: 0)
                        }
                        else -> (buttonDef?.iconRes ?: 0)
                    }
                    val drawable = if (b.icon != null && b.icon.startsWith("file:")) {
                        ButtonIconFile.loadDrawable(b.icon)
                    } else null

                    holder.ui.setButton(label, displayIconRes, b.text, drawable)
                    holder.ui.root.setOnClickListener {
                        openButtonEditor(buttonItem.button, position, buttonItem.section)
                    }
                    holder.ui.root.setOnLongClickListener(null)
                }
                is AddButtonViewHolder -> {
                    val addItem = item as ListItem.AddButtonItem
                    holder.ui.setButton(getString(addItem.buttonDef.labelRes), addItem.buttonDef.iconRes)
                    holder.ui.root.setOnClickListener {
                        openAddButtonDialog(addItem.buttonDef)
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class AddPlaceholderViewHolder(val ui: ButtonEntryUi) : RecyclerView.ViewHolder(ui.root)
    private class ButtonViewHolder(val ui: ButtonEntryUi) : RecyclerView.ViewHolder(ui.root)
    private class AddButtonViewHolder(val ui: ButtonEntryUi) : RecyclerView.ViewHolder(ui.root)
}

/**
 * UI for a button entry in the buttons customizer, similar to StatusAreaEntryUi.
 */
class ButtonEntryUi(
    override val ctx: android.content.Context,
    private val theme: Theme,
    private var label: String,
    private var iconRes: Int,
    private var circleText: String? = null,
    private var customDrawable: android.graphics.drawable.Drawable? = null
) : Ui {

    private val bkgDrawable = ShapeDrawable(OvalShape())

    val bkg = frameLayout {
        background = bkgDrawable
        layoutParams = android.view.ViewGroup.LayoutParams(ctx.dp(48), ctx.dp(48))
    }

    val icon = imageView {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    val textIcon = view(::AutoScaleTextView) {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 20f)
        gravity = android.view.Gravity.CENTER
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            typeface = Typeface.create(typeface, 600, false)
        } else {
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    val labelView = textView {
        textSize = 12f
        gravity = gravityCenter
        setTextColor(ctx.styledColor(android.R.attr.textColorPrimary))
        text = label
        visibility = if (label.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }

    override val root: android.view.View = run {
        val content = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.view.ViewGroup.LayoutParams(matchParent, matchParent)

            addView(bkg)
            addView(labelView, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(6)
            })
        }

        android.widget.FrameLayout(ctx).apply {
            addView(content, android.view.ViewGroup.LayoutParams(matchParent, matchParent))
            layoutParams = android.view.ViewGroup.LayoutParams(ctx.dp(80), ctx.dp(96))
        }
    }

    init {
        updateColors()
        updateIcon()
    }

    fun setButton(newLabel: String, newIconRes: Int, newCircleText: String? = null, newDrawable: android.graphics.drawable.Drawable? = null) {
        label = newLabel
        iconRes = newIconRes
        circleText = newCircleText
        customDrawable = newDrawable
        labelView.text = label
        labelView.visibility = if (label.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        updateColors()
        updateIcon()
    }

    private fun updateColors() {
        // Use system theme colors for better visibility in light/dark mode
        val contentColor = ctx.styledColor(android.R.attr.textColorPrimary)
        val bgColor = ctx.styledColor(android.R.attr.colorPrimary)

        bkgDrawable.paint.color = bgColor
        labelView.setTextColor(contentColor)
        textIcon.setTextColor(contentColor)
    }

    private fun updateIcon() {
        bkg.removeAllViews()
        val contentColor = ctx.styledColor(android.R.attr.textColorPrimary)

        if (customDrawable != null) {
            icon.visibility = android.view.View.VISIBLE
            textIcon.visibility = android.view.View.GONE
            icon.setImageDrawable(customDrawable)
            bkg.addView(icon, android.widget.FrameLayout.LayoutParams(ctx.dp(32), ctx.dp(32)).apply {
                gravity = android.view.Gravity.CENTER
            })
        } else if (iconRes != 0) {
            icon.visibility = android.view.View.VISIBLE
            textIcon.visibility = android.view.View.GONE
            icon.setImageDrawable(ctx.getDrawable(iconRes))
            // Apply tint to icon (similar to StatusAreaEntryUi)
            icon.imageDrawable?.setTint(contentColor)
            bkg.addView(icon, android.widget.FrameLayout.LayoutParams(ctx.dp(32), ctx.dp(32)).apply {
                gravity = android.view.Gravity.CENTER
            })
        } else {
            icon.visibility = android.view.View.GONE
            textIcon.visibility = android.view.View.VISIBLE
            // Use circleText if provided, otherwise use first character of label
            textIcon.text = circleText ?: label.firstOrNull()?.toString() ?: ""
            textIcon.setTextColor(contentColor)
            bkg.addView(textIcon, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            })
        }
    }
}

private const val MENU_SAVE_ID = 3001
