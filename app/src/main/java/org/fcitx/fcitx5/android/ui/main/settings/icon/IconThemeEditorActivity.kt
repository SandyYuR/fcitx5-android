/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.icon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.input.config.ButtonIconFile
import splitties.resources.color
import splitties.dimensions.dp
import splitties.resources.styledColor

class IconThemeEditorActivity : AppCompatActivity() {
    private companion object {
        private const val MENU_DELETE = 1
        private const val MENU_RENAME = 2
        private const val MENU_SAVE = 3
    }

    private var theme: IconTheme = IconTheme.default()
    private var themeName = ""
    private var isEditingExisting = false
    private var originalName = ""
    private var saveMenuItem: MenuItem? = null

    private val adapter = SlotAdapter()
    private val mutableIcons = mutableMapOf<String, String>()

    private val iconPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        handlePickedSvg(uri)
    }

    private var pendingSlot: String? = null
    private var currentDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        val name = intent.getStringExtra("icon_theme_name")
        if (name != null && name != IconTheme.default().name) {
            val existing = IconThemeManager.iconThemes.drop(1).find { it.name == name }
            if (existing != null) {
                theme = existing
                themeName = existing.name
                originalName = existing.name
                isEditingExisting = true
            }
        }
        if (!isEditingExisting) {
            themeName = generateDefaultName()
        }
        mutableIcons.putAll(theme.icons)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val toolbar = Toolbar(this).apply {
            setBackgroundColor(styledColor(android.R.attr.colorPrimary))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(matchParent, wrapContent))
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = themeName

        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@IconThemeEditorActivity)
            adapter = this@IconThemeEditorActivity.adapter
            layoutParams = LinearLayout.LayoutParams(matchParent, 0, 1f)
        }
        root.addView(recyclerView, LinearLayout.LayoutParams(matchParent, 0, 1f))

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            toolbar.updatePadding(top = statusTop)
            insets
        }
        refreshSaveButtonState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val normalIconTint = ColorStateList.valueOf(styledColor(android.R.attr.colorControlNormal))
        if (isEditingExisting) {
            menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, getString(R.string.delete))
                .setIcon(R.drawable.ic_baseline_delete_24)
                .setIconTintList(ColorStateList.valueOf(color(R.color.red_400)))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        menu.add(Menu.NONE, MENU_RENAME, Menu.NONE, getString(R.string.theme_name))
            .setIcon(R.drawable.ic_baseline_edit_24)
            .setIconTintList(normalIconTint)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        saveMenuItem = menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, getString(R.string.save)).apply {
            setIcon(R.drawable.ic_baseline_check_24)
            iconTintList = normalIconTint
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        refreshSaveButtonState()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_DELETE -> { promptDeleteTheme(); true }
        MENU_RENAME -> { promptRenameTheme(); true }
        MENU_SAVE -> { saveTheme(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun generateDefaultName(): String {
        val existing = IconThemeManager.iconThemes.map { it.name }.toSet()
        var i = 1
        while ("Untitled $i" in existing) i++
        return "Untitled $i"
    }

    private fun saveTheme() {
        if (themeName.isBlank()) {
            Toast.makeText(this, getString(R.string.icon_theme_name_empty), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasUnsavedChanges()) {
            finish()
            return
        }
        val existing = IconThemeManager.iconThemes.drop(1).find { it.name == themeName }
        if (existing != null && themeName != originalName) {
            Toast.makeText(this, getString(R.string.icon_theme_name_exists), Toast.LENGTH_SHORT).show()
            return
        }
        val customizedIcons = mutableIcons.filterValues { it.isNotEmpty() }
        val generatedThumbnail = IconThemeManager.buildThemeThumbnailSvg(customizedIcons)
        val updated = IconTheme(
            name = themeName,
            author = theme.author,
            version = theme.version + 1,
            thumbnailSvg = generatedThumbnail ?: theme.thumbnailSvg,
            icons = customizedIcons.toMap()
        )
        val wasActive = isEditingExisting && IconThemeManager.activeTheme.name == originalName
        IconThemeManager.saveTheme(updated)
        if (isEditingExisting && themeName != originalName) {
            IconThemeManager.deleteTheme(originalName)
            if (wasActive) {
                IconThemeManager.activeTheme = updated
            }
        }
        Toast.makeText(this, getString(R.string.icon_theme_saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun handlePickedSvg(uri: Uri?) {
        val slot = pendingSlot ?: return
        pendingSlot = null
        if (uri == null) return
        try {
            val valueToStore = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                reader.readText().trim()
            } ?: run {
                Toast.makeText(this, getString(R.string.icon_theme_read_svg_failed), Toast.LENGTH_SHORT).show()
                return
            }
            if (SlotRowUi.isSvgContent(valueToStore) || SlotRowUi.isDrawableXmlContent(valueToStore)) {
                mutableIcons[slot] = valueToStore
                notifySlotChanged(slot)
                currentDialog?.dismiss()
            } else {
                Toast.makeText(this, getString(R.string.icon_theme_invalid_svg), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.icon_theme_read_svg_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun notifySlotChanged(slot: String) {
        val pos = allFlattenedSlots.indexOf(slot)
        if (pos >= 0) adapter.notifyItemChanged(pos)
        refreshSaveButtonState()
    }

    private fun hasUnsavedChanges(): Boolean {
        if (!isEditingExisting) return true
        if (themeName != originalName) return true
        return mutableIcons.filterValues { it.isNotEmpty() } != theme.icons.filterValues { it.isNotEmpty() }
    }

    private fun refreshSaveButtonState() {
        val enabled = hasUnsavedChanges()
        saveMenuItem?.isEnabled = enabled
        saveMenuItem?.icon?.alpha = if (enabled) 255 else 90
    }

    private fun promptRenameTheme() {
        val input = EditText(this).apply {
            setText(themeName)
            setSelection(text.length)
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.theme_name)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) {
                    Toast.makeText(this, getString(R.string.icon_theme_name_empty), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val nameClashes = IconThemeManager.iconThemes.drop(1).any { installed ->
                    installed.name == newName && installed.name != originalName
                }
                if (nameClashes) {
                    Toast.makeText(this, getString(R.string.icon_theme_name_exists), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (themeName != newName) {
                    themeName = newName
                    supportActionBar?.title = themeName
                    refreshSaveButtonState()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun promptDeleteTheme() {
        val targetName = originalName.ifBlank { themeName }
        if (targetName.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.icon_theme_delete_confirm, targetName))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                IconThemeManager.deleteTheme(targetName)
                finish()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private val groupedSections: List<Pair<String, List<String>>> by lazy {
        listOf(
            getString(R.string.icon_theme_keyboard_keys) to IconTheme.KEY_SLOTS,
            getString(R.string.icon_theme_toolbar_buttons) to IconTheme.TOOLBAR_SLOTS,
            getString(R.string.icon_theme_system_buttons) to IconTheme.SYSTEM_SLOTS
        )
    }

    private val allFlattenedSlots: List<Any> by lazy {
        val result = mutableListOf<Any>()
        for ((title, slots) in groupedSections) {
            result.add(SectionHeader(title, slots.count { mutableIcons[it]?.isNotEmpty() == true }))
            result.addAll(slots)
        }
        result
    }

    private inner class SlotAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val VIEW_HEADER = 0
        private val VIEW_SLOT = 1

        override fun getItemViewType(position: Int): Int =
            if (allFlattenedSlots[position] is SectionHeader) VIEW_HEADER else VIEW_SLOT

        override fun getItemCount(): Int = allFlattenedSlots.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_HEADER -> {
                    val tv = TextView(parent.context).apply {
                        setPadding(dp(16), dp(12), dp(16), dp(4))
                        textSize = 14f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(styledColor(android.R.attr.colorAccent))
                        layoutParams = RecyclerView.LayoutParams(matchParent, wrapContent)
                    }
                    object : RecyclerView.ViewHolder(tv) {}
                }
                else -> {
                    val ui = SlotRowUi(parent.context)
                    ui.root.layoutParams = RecyclerView.LayoutParams(matchParent, wrapContent)
                    SlotViewHolder(ui)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = allFlattenedSlots[position]
            when {
                item is SectionHeader -> {
                    val tv = holder.itemView as TextView
                    tv.text = getString(R.string.icon_theme_customized, item.title, item.customCount)
                }
                holder is SlotViewHolder -> {
                    holder.bind(item as String)
                }
            }
        }
    }

    private inner class SlotViewHolder(val ui: SlotRowUi) : RecyclerView.ViewHolder(ui.root) {
        fun bind(slot: String) {
            ui.bind(slot, mutableIcons[slot], getDefaultIconResForSlot(slot))
            ui.root.setOnClickListener { showIconSelector(slot) }
        }
    }

    private fun showIconSelector(slot: String) {
        val supportsText = IconTheme.supportsTextInput(slot)
        val currentValue = mutableIcons[slot] ?: ""

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val secondaryTextColor = styledColor(android.R.attr.textColorSecondary)
        val primaryTextColor = styledColor(android.R.attr.textColorPrimary)

        val compareRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val builtinColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        }
        val customColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f)
        }

        val builtinLabel = TextView(this).apply {
            text = getString(R.string.icon_theme_builtin_short)
            textSize = 12f
            setTextColor(secondaryTextColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        val builtinPreview = ImageView(this).apply {
            val defaultRes = getDefaultIconResForSlot(slot)
            if (defaultRes != 0) {
                setImageResource(defaultRes)
                imageTintList = android.content.res.ColorStateList.valueOf(primaryTextColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        builtinColumn.addView(builtinLabel)
        builtinColumn.addView(builtinPreview)

        val customLabel = TextView(this).apply {
            text = getString(R.string.icon_theme_custom_resource)
            textSize = 12f
            setTextColor(secondaryTextColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        val customPreviewDrawable = SlotRowUi.renderIconPreview(this, currentValue, 28)
        if (customPreviewDrawable != null) {
            val customPreviewImage = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(customPreviewDrawable)
            }
            customColumn.addView(customLabel)
            customColumn.addView(customPreviewImage)
        } else {
            val customPreviewText = TextView(this).apply {
                text = if (currentValue.isNotEmpty()) currentValue.take(16) else getString(R.string.icon_theme_current_default)
                textSize = 13f
                setTextColor(if (currentValue.isNotEmpty()) primaryTextColor else secondaryTextColor)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(8), dp(4), dp(8))
                minWidth = dp(36)
            }
            customColumn.addView(customLabel)
            customColumn.addView(customPreviewText)
        }

        compareRow.addView(builtinColumn)
        compareRow.addView(customColumn)
        dialogView.addView(compareRow)

        var emojiInput: EditText? = null

        if (supportsText) {
            val emojiLabel = TextView(this).apply {
                text = getString(R.string.icon_theme_emoji_or_text)
                textSize = 12f
                setTextColor(secondaryTextColor)
            }
            dialogView.addView(emojiLabel)

            val input = EditText(this).apply {
                val isXml = SlotRowUi.isSvgContent(currentValue) || SlotRowUi.isDrawableXmlContent(currentValue)
                setText(if (!isXml) currentValue else "")
                hint = getString(R.string.icon_theme_emoji_hint)
            }
            emojiInput = input
            dialogView.addView(input, LinearLayout.LayoutParams(matchParent, wrapContent))
        }

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent).apply { topMargin = dp(12) }
        }

        var dialogRef: AlertDialog? = null

        val clearAction = TextView(this).apply {
            text = getString(R.string.icon_theme_clear)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.colorAccent))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                mutableIcons.remove(slot)
                notifySlotChanged(slot)
                dialogRef?.dismiss()
            }
        }
        actionsRow.addView(clearAction, LinearLayout.LayoutParams(0, wrapContent, 1f))

        val selectAction = TextView(this).apply {
            text = getString(R.string.icon_theme_select)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.colorAccent))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                pendingSlot = slot
                iconPickerLauncher.launch(arrayOf("image/svg+xml", "text/xml", "application/xml"))
            }
        }
        actionsRow.addView(selectAction, LinearLayout.LayoutParams(0, wrapContent, 1f))

        val cancelAction = TextView(this).apply {
            text = getString(R.string.cancel)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.colorAccent))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { dialogRef?.dismiss() }
        }
        actionsRow.addView(cancelAction, LinearLayout.LayoutParams(0, wrapContent, 1f))

        val confirmAction = TextView(this).apply {
            text = getString(android.R.string.ok)
            textSize = 13f
            setTextColor(styledColor(android.R.attr.colorAccent))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), 0, dp(8))
            setOnClickListener {
                if (supportsText) {
                    val emojiText = emojiInput?.text?.toString()?.trim()
                    if (!emojiText.isNullOrEmpty()) {
                        mutableIcons[slot] = emojiText
                        notifySlotChanged(slot)
                    }
                }
                dialogRef?.dismiss()
            }
        }
        actionsRow.addView(confirmAction, LinearLayout.LayoutParams(0, wrapContent, 1f))
        dialogView.addView(actionsRow)

        val dialog = AlertDialog.Builder(this)
            .setTitle(slot.replace("keys.", "Key: ").replace("toolbar.", "Toolbar: ").replace("system.", "System: "))
            .setView(dialogView)
            .create()
        dialog.show()
        dialogRef = dialog
        currentDialog = dialog
    }

    // ... (getDefaultIconResForSlot remains unchanged)

    private fun getDefaultIconResForSlot(slot: String): Int = when (slot) {
        "keys.capslock.none" -> R.drawable.ic_capslock_none
        "keys.capslock.once" -> R.drawable.ic_capslock_once
        "keys.capslock.lock" -> R.drawable.ic_capslock_lock
        "keys.backspace" -> R.drawable.ic_baseline_backspace_24
        "keys.return.default" -> R.drawable.ic_baseline_keyboard_return_24
        "keys.return.go" -> R.drawable.ic_baseline_arrow_forward_24
        "keys.return.search" -> R.drawable.ic_baseline_search_24
        "keys.return.send" -> R.drawable.ic_baseline_send_24
        "keys.return.next" -> R.drawable.ic_baseline_keyboard_tab_24
        "keys.return.previous" -> R.drawable.ic_baseline_keyboard_tab_reverse_24
        "keys.return.done" -> R.drawable.ic_baseline_done_24
        "keys.language" -> R.drawable.ic_baseline_language_24
        "keys.quickphrase" -> R.drawable.ic_baseline_format_quote_24
        "keys.space" -> R.drawable.ic_baseline_space_bar_24
        "keys.numpad" -> R.drawable.ic_number_pad
        "keys.emoji" -> R.drawable.ic_baseline_tag_faces_24
        "keys.symbols" -> R.drawable.ic_baseline_emoji_symbols_24
        "keys.unicode" -> R.drawable.ic_logo_unicode
        "keys.pageup" -> R.drawable.ic_baseline_arrow_upward_24
        "keys.pagedown" -> R.drawable.ic_baseline_arrow_downward_24
        "toolbar.undo" -> R.drawable.ic_baseline_undo_24
        "toolbar.redo" -> R.drawable.ic_baseline_redo_24
        "toolbar.cursor_move" -> R.drawable.ic_cursor_move
        "toolbar.floating_toggle" -> R.drawable.ic_floating_toggle_24
        "toolbar.clipboard" -> R.drawable.ic_clipboard
        "toolbar.more" -> R.drawable.ic_baseline_more_horiz_24
        "toolbar.language_switch" -> R.drawable.ic_baseline_language_24
        "toolbar.theme" -> R.drawable.ic_baseline_palette_24
        "toolbar.icon_theme" -> R.drawable.ic_icon_theme_24
        "toolbar.input_method_options" -> R.drawable.ic_baseline_language_24
        "toolbar.reload_config" -> R.drawable.ic_baseline_sync_24
        "toolbar.virtual_keyboard" -> R.drawable.ic_baseline_keyboard_24
        "toolbar.one_handed_keyboard" -> R.drawable.ic_baseline_keyboard_tab_24
        "toolbar.browse_user_data" -> R.drawable.ic_baseline_more_horiz_24
        "toolbar.settings_global" -> R.drawable.ic_baseline_tune_24
        "toolbar.settings_ime" -> R.drawable.ic_baseline_language_24
        "toolbar.edit_layout" -> R.drawable.ic_baseline_keyboard_24
        "toolbar.edit_fontset" -> R.drawable.ic_baseline_text_format_24
        "system.toolbar_toggle" -> R.drawable.ic_baseline_expand_more_24
        "system.hide_keyboard" -> R.drawable.ic_baseline_arrow_drop_down_24
        "system.voice_input" -> R.drawable.ic_baseline_keyboard_voice_24
        else -> 0
    }

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT
    data class SectionHeader(val title: String, val customCount: Int)
}

class SlotRowUi(private val ctx: android.content.Context) {
    val root: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(48)
    }

    // ── Left column: builtin icon + name (⅔ width) ──
    private val leftColumn = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
    }

    private val builtinIcon: ImageView = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        imageTintList = android.content.res.ColorStateList.valueOf(ctx.styledColor(android.R.attr.textColorSecondary))
    }

    private val nameLabel: TextView = TextView(ctx).apply {
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(10), 0, ctx.dp(4), 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    // ── Right column: custom value preview (⅓ width) ──
    private val rightColumn = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER or Gravity.END
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private val customIcon: ImageView = ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)).apply { marginEnd = ctx.dp(4) }
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val valueLabel: TextView = TextView(ctx).apply {
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }

    init {
        leftColumn.addView(builtinIcon)
        leftColumn.addView(nameLabel)
        rightColumn.addView(customIcon)
        rightColumn.addView(valueLabel)
        root.addView(leftColumn)
        root.addView(rightColumn, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
    }

    fun bind(slot: String, currentValue: String?, defaultRes: Int) {
        builtinIcon.setImageResource(if (defaultRes != 0) defaultRes else android.R.drawable.ic_menu_gallery)
        nameLabel.text = SlotRowUi.slotToDisplayName(slot)
        val previewDrawable = SlotRowUi.renderIconPreview(ctx, currentValue, 24)
        val isText = currentValue != null && previewDrawable == null && currentValue.isNotEmpty()
        val textColor = ctx.styledColor(android.R.attr.textColorPrimary)
        val dimColor = ctx.styledColor(android.R.attr.textColorTertiary)
        when {
            previewDrawable != null -> {
                customIcon.visibility = View.VISIBLE
                customIcon.setImageDrawable(previewDrawable)
                valueLabel.visibility = View.GONE
            }
            isText -> {
                customIcon.visibility = View.GONE
                valueLabel.visibility = View.VISIBLE
                valueLabel.text = currentValue
                valueLabel.setTextColor(textColor)
            }
            else -> {
                customIcon.visibility = View.GONE
                valueLabel.visibility = View.VISIBLE
                valueLabel.text = "--"
                valueLabel.setTextColor(dimColor)
            }
        }
    }

    companion object {
        private fun normalizeXmlContent(raw: String): String {
            return raw
                .removePrefix("\uFEFF")
                .replaceFirst(
                    Regex(
                        "^\\s*(?:<\\?xml\\b[^>]*\\?>|<!DOCTYPE\\b[^>]*(?:\\[[^\\]]*\\])?[^>]*>|<!--.*?-->)\\s*",
                        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
                    ),
                    ""
                )
                .replaceFirst(
                    Regex("^(\\s*<!--.*?-->\\s*)+", setOf(RegexOption.DOT_MATCHES_ALL)),
                    ""
                )
                .trimStart()
        }

        fun slotToDisplayName(slot: String): String = slot
            .removePrefix("keys.").removePrefix("toolbar.").removePrefix("system.")
            .replace(".", " / ")
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        fun isSvgContent(s: String?): Boolean = s != null && IconThemeManager.isInlineSvg(s)

        fun isDrawableXmlContent(s: String?): Boolean {
            if (s.isNullOrBlank()) return false
            if (ButtonIconFile.isFileIcon(s)) return ButtonIconFile.loadDrawable(s) != null
            val raw = normalizeXmlContent(s)
            if (!raw.startsWith("<")) return false
            if (isSvgContent(raw)) return false
            return IconThemeManager.loadInlineDrawableXml(raw) != null
        }

        fun renderSvgPreview(ctx: android.content.Context, svgContent: String, previewSizeDp: Int = 24): Drawable? {
            return try {
                var clean = svgContent
                    .removePrefix("\uFEFF")
                    .replaceFirst(Regex("^\\s*<\\?xml[^>]*\\?>\\s*"), "")
                    .replaceFirst(
                        Regex("^(\\s*<!--.*?-->\\s*)+", setOf(RegexOption.DOT_MATCHES_ALL)),
                        ""
                    )
                if (clean.contains("xlink:") && !clean.contains("xmlns:xlink")) {
                    clean = clean.replaceFirst(
                        Regex("<svg\\b([^>]*)>", RegexOption.IGNORE_CASE),
                        "<svg$1 xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                    )
                }
                val svg = com.caverock.androidsvg.SVG.getFromString(clean)
                val size = ctx.dp(previewSizeDp)
                val picture = svg.renderToPicture()
                val w = picture.width.coerceAtLeast(1)
                val h = picture.height.coerceAtLeast(1)
                if (w <= 1 || h <= 1) {
                    val bitmap = Bitmap.createBitmap(size.coerceAtLeast(1), size.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    svg.renderToCanvas(canvas, android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()))
                    BitmapDrawable(ctx.resources, bitmap)
                } else {
                    val scale = minOf(size.toFloat() / w, size.toFloat() / h)
                    val bw = (w * scale).toInt().coerceAtLeast(1)
                    val bh = (h * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.scale(scale, scale)
                    canvas.drawPicture(picture)
                    BitmapDrawable(ctx.resources, bitmap)
                }
            } catch (_: Exception) { null }
        }

        fun renderDrawableXmlPreview(ctx: android.content.Context, xmlContent: String, previewSizeDp: Int = 24): Drawable? {
            return try {
                val clean = normalizeXmlContent(xmlContent)
                val drawable = IconThemeManager.loadInlineDrawableXml(clean)
                    ?: return null
                drawable.mutate().apply {
                    val size = ctx.dp(previewSizeDp)
                    setBounds(0, 0, size, size)
                    setTint(ctx.styledColor(android.R.attr.textColorPrimary))
                }
            } catch (_: Exception) { null }
        }

        fun renderIconPreview(ctx: android.content.Context, value: String?, previewSizeDp: Int = 24): Drawable? {
            if (value.isNullOrBlank()) return null
            if (ButtonIconFile.isFileIcon(value)) {
                return ButtonIconFile.loadDrawable(value)?.mutate()?.apply {
                    val size = ctx.dp(previewSizeDp)
                    setBounds(0, 0, size, size)
                    if (ButtonIconFile.shouldTintIcon(value)) {
                        setTint(ctx.styledColor(android.R.attr.textColorPrimary))
                    } else {
                        setTintList(null)
                    }
                }
            }
            return when {
                isSvgContent(value) -> renderSvgPreview(ctx, value, previewSizeDp)?.mutate()?.apply {
                    if (IconThemeManager.shouldTintInlineSvg(value)) {
                        setTint(ctx.styledColor(android.R.attr.textColorPrimary))
                    } else {
                        setTintList(null)
                    }
                }
                isDrawableXmlContent(value) -> renderDrawableXmlPreview(ctx, value, previewSizeDp)
                else -> null
            }
        }
    }
}
