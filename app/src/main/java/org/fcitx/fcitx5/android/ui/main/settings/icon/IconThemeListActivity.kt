/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.icon

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.IconTheme
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.JsonFileQrShareManager
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrBitmapUtil
import org.fcitx.fcitx5.android.ui.main.settings.behavior.share.LayoutQrTransferCodec
import splitties.dimensions.dp
import splitties.resources.styledColor

class IconThemeListActivity : AppCompatActivity() {
    private companion object {
        private const val MENU_IMPORT_JSON = 2
        private const val MENU_IMPORT_QR_IMAGE = 3
        private const val MENU_SHARE_ICON_THEME = 4
    }

    private val themes get() = IconThemeManager.iconThemes
    private val builtinDefault = IconTheme.default()
    private val adapter = ThemeAdapter()

    private val onListChangeListener = IconThemeManager.OnIconThemeListChangeListener { refreshThemes() }
    private val onThemeChangeListener = IconThemeManager.OnIconThemeChangeListener { refreshThemes() }

    private val jsonImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importJson(it) } }

    private val qrImageImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importFromQrImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        IconThemeManager.addOnListChangeListener(onListChangeListener)
        IconThemeManager.addOnChangedListener(onThemeChangeListener)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val toolbar = Toolbar(this).apply {
            title = getString(R.string.icon_theme)
            setBackgroundColor(styledColor(android.R.attr.colorPrimary))
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        root.addView(toolbar)

        val recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@IconThemeListActivity, 3)
            adapter = this@IconThemeListActivity.adapter
        }
        root.addView(recyclerView, LinearLayout.LayoutParams(matchParent, 0, 1f))

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            toolbar.updatePadding(top = statusTop)
            insets
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        IconThemeManager.removeOnListChangeListener(onListChangeListener)
        IconThemeManager.removeOnChangedListener(onThemeChangeListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_IMPORT_JSON, Menu.NONE, getString(R.string.icon_theme_import_json))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_IMPORT_QR_IMAGE, Menu.NONE, getString(R.string.icon_theme_import_qr_image))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, MENU_SHARE_ICON_THEME, Menu.NONE, getString(R.string.icon_theme_share_title))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_IMPORT_JSON -> { jsonImportLauncher.launch("application/json"); true }
        MENU_IMPORT_QR_IMAGE -> { qrImageImportLauncher.launch("image/*"); true }
        MENU_SHARE_ICON_THEME -> { promptShareTheme(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun importJson(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            if (json != null) {
                val theme = IconThemeManager.importTheme(json)
                Toast.makeText(this, getString(R.string.icon_theme_imported, theme.name), Toast.LENGTH_SHORT).show()
                refreshThemes()
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.icon_theme_import_json_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFromQrImage(uri: Uri) {
        try {
            val chunks = JsonFileQrShareManager.decodeQrChunksFromImage(this, uri)
            if (chunks.isEmpty()) {
                Toast.makeText(this, getString(R.string.icon_theme_no_qr_found), Toast.LENGTH_SHORT).show()
                return
            }
            val json = LayoutQrTransferCodec.decodeChunksToJson(chunks)
            val theme = if (IconThemeQrTransferCodec.detectSchema(json) == IconThemeQrTransferCodec.SCHEMA) {
                IconThemeQrTransferCodec.decodeIconThemeFromJson(json)
            } else {
                IconThemeManager.importTheme(json)
                return
            }
            IconThemeManager.saveTheme(theme)
            Toast.makeText(this, getString(R.string.icon_theme_imported, theme.name), Toast.LENGTH_SHORT).show()
            refreshThemes()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.icon_theme_decode_qr_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshThemes() {
        adapter.notifyDataSetChanged()
    }

    private inner class ThemeAdapter : RecyclerView.Adapter<ThemeAdapter.VH>() {
        private val NEW_ITEM = 0
        private val THEME_OFFSET = 1

        override fun getItemCount() = themes.size + THEME_OFFSET

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ThemeThumbnailUi(parent.context, this@IconThemeListActivity))
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            when (position) {
                NEW_ITEM -> holder.ui.bindNew()
                else -> {
                    val theme = themes[position - THEME_OFFSET]
                    val isActive = theme.name == IconThemeManager.activeTheme.name
                    val isDefault = theme.name == builtinDefault.name
                    holder.ui.bind(theme, isActive, isDefault)
                    holder.ui.setEditAction(
                        visible = !isDefault,
                        onClick = {
                            startActivity(
                                Intent(this@IconThemeListActivity, IconThemeEditorActivity::class.java)
                                    .putExtra("icon_theme_name", theme.name)
                            )
                        }
                    )
                }
            }
            holder.ui.root.setOnClickListener {
                when (position) {
                    NEW_ITEM -> showCreateThemeMenu()
                    else -> {
                        val theme = themes[position - THEME_OFFSET]
                        IconThemeManager.activeTheme = theme
                        refreshThemes()
                    }
                }
            }
            holder.ui.root.setOnLongClickListener(null)
        }

        inner class VH(val ui: ThemeThumbnailUi) : RecyclerView.ViewHolder(ui.root)
    }

    private fun showCreateThemeMenu() {
        val actions = arrayOf(
            getString(R.string.new_icon_theme),
            getString(R.string.icon_theme_duplicate)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.icon_theme_new))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, IconThemeEditorActivity::class.java))
                    1 -> promptDuplicateTheme()
                }
            }
            .show()
    }

    private fun promptDuplicateTheme() {
        val customThemes = themes.drop(1)
        if (customThemes.isEmpty()) {
            Toast.makeText(this, getString(R.string.icon_theme_no_custom_theme), Toast.LENGTH_SHORT).show()
            return
        }
        val names = customThemes.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.icon_theme_duplicate))
            .setItems(names) { _, which ->
                val source = customThemes[which]
                val duplicated = source.copy(name = generateCopyName(source.name))
                IconThemeManager.saveTheme(duplicated)
                refreshThemes()
                Toast.makeText(this, getString(R.string.icon_theme_duplicated), Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(this, IconThemeEditorActivity::class.java)
                        .putExtra("icon_theme_name", duplicated.name)
                )
            }
            .show()
    }

    private fun promptShareTheme() {
        val customThemes = themes.drop(1)
        if (customThemes.isEmpty()) {
            Toast.makeText(this, getString(R.string.icon_theme_no_custom_theme), Toast.LENGTH_SHORT).show()
            return
        }
        val names = customThemes.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.icon_theme_share_title))
            .setItems(names) { _, which ->
                exportAsQrLongImage(customThemes[which])
            }
            .show()
    }

    private fun exportAsQrLongImage(theme: IconTheme) {
        lifecycleScope.launch {
            try {
                val bundle = withContext(Dispatchers.Default) { IconThemeQrTransferCodec.encodeIconThemeToChunks(theme) }
                val labels = JsonFileQrShareManager.buildChunkLabels(bundle, getString(R.string.qr_payload_type_icon_theme), theme.name)
                val image = withContext(Dispatchers.Default) {
                    LayoutQrBitmapUtil.composeLongImageStreamingWithPreview(
                        bundle.chunks.map { it.encode() }, labels, null
                    )
                }
                val uri = JsonFileQrShareManager.saveLongImageToShareCache(
                    this@IconThemeListActivity, image, "icon_theme_${theme.name}")
                if (!image.isRecycled) image.recycle()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.icon_theme_share_title)))
            } catch (e: Exception) {
                Toast.makeText(this@IconThemeListActivity,
                    getString(R.string.icon_theme_export_qr_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateCopyName(name: String): String {
        val base = "$name (copy)"
        if (themes.none { it.name == base }) return base
        var i = 2
        while (themes.any { it.name == "$base $i" }) i++
        return "$base $i"
    }

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
}

class ThemeThumbnailUi(private val context: android.content.Context, private val activity: IconThemeListActivity) {
    val root: FrameLayout = FrameLayout(context).apply {
        setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
        minimumHeight = context.dp(88)
    }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(context.dp(8), context.dp(12), context.dp(8), context.dp(12))
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private val previewIcon = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(36), context.dp(36))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val nameText = TextView(context).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        maxLines = 2
        setPadding(context.dp(4), context.dp(4), context.dp(4), 0)
        setTextColor(styledColor(android.R.attr.textColorPrimary))
    }

    private val activeIndicator = TextView(context).apply {
        textSize = 10f
        gravity = Gravity.CENTER
        setTextColor(styledColor(android.R.attr.textColorSecondary))
    }

    private val editButton = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(context.dp(36), context.dp(36), Gravity.TOP or Gravity.END)
        setPadding(context.dp(10), context.dp(10), context.dp(6), context.dp(6))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_baseline_edit_24)
        imageTintList = ColorStateList.valueOf(context.styledColor(android.R.attr.textColorSecondary))
        contentDescription = activity.getString(R.string.edit_icon_theme)
    }

    private val checkMark = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(context.dp(52), context.dp(52), Gravity.CENTER)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(R.drawable.ic_baseline_check_24)
        visibility = android.view.View.GONE
    }

    init {
        content.addView(previewIcon)
        content.addView(nameText)
        content.addView(activeIndicator)
        root.addView(content)
        root.addView(checkMark)
        root.addView(editButton)
    }

    private fun applySelectionBackground(isActive: Boolean) {
        val accent = context.styledColor(android.R.attr.colorAccent)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(12f)
            if (isActive) {
                setColor((0x22 shl 24) or (accent and 0x00FFFFFF))
            } else {
                setColor(0x00000000)
            }
        }
        content.background = background
        checkMark.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.GONE
        checkMark.imageTintList = ColorStateList.valueOf(accent)
    }

    fun bindNew() {
        val accent = context.styledColor(android.R.attr.colorAccent)
        previewIcon.setImageResource(android.R.drawable.ic_input_add)
        previewIcon.imageTintList = android.content.res.ColorStateList.valueOf(accent)
        nameText.text = activity.getString(R.string.icon_theme_new)
        nameText.setTextColor(accent)
        activeIndicator.visibility = android.view.View.GONE
        applySelectionBackground(isActive = false)
        setEditAction(visible = false, onClick = null)
    }

    fun bind(theme: IconTheme, isActive: Boolean, isDefault: Boolean) {
        val accent = context.styledColor(android.R.attr.colorAccent)
        val primary = context.styledColor(android.R.attr.textColorPrimary)
        val thumbnailSvg = theme.thumbnailSvg
            ?.takeIf { it.isNotBlank() }
            ?: IconThemeManager.buildThemeThumbnailSvg(theme.icons)
        val thumbnailDrawable = thumbnailSvg
            ?.let { SlotRowUi.renderSvgPreview(context, it, 36) }
        if (thumbnailDrawable != null) {
            previewIcon.setImageDrawable(thumbnailDrawable)
            previewIcon.imageTintList = null
        } else {
            val fallback = AppCompatResources.getDrawable(context, R.drawable.ic_icon_theme_24)?.mutate()
            val fallbackSize = context.dp(36)
            fallback?.setBounds(0, 0, fallbackSize, fallbackSize)
            previewIcon.setImageDrawable(fallback)
            previewIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isActive) accent else primary
            )
        }
        nameText.text = if (isDefault) activity.getString(R.string.default_) else theme.name
        nameText.setTextColor(if (isActive) accent else primary)
        activeIndicator.visibility = android.view.View.GONE
        applySelectionBackground(isActive)
        setEditAction(visible = !isDefault, onClick = null)
    }

    fun setEditAction(visible: Boolean, onClick: (() -> Unit)?) {
        editButton.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        if (visible && onClick != null) {
            editButton.setOnClickListener { onClick() }
        } else {
            editButton.setOnClickListener(null)
        }
    }
}
