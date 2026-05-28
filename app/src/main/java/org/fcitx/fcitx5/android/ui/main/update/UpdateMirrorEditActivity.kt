/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import java.util.regex.PatternSyntaxException

class UpdateMirrorEditActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MIRROR_ID = "mirror_id"
        private const val MENU_DELETE = 1
        private const val MENU_SAVE = 2
    }

    private lateinit var toolbar: Toolbar
    private lateinit var nameInput: EditText
    private lateinit var patternInput: EditText
    private lateinit var replacementInput: EditText
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_update_mirror_edit)
        toolbar = findViewById(R.id.toolbar)
        toolbar.backgroundColor = styledColor(android.R.attr.colorPrimary)
        toolbar.elevation = dp(4f)
        nameInput = findViewById(R.id.mirrorNameInput)
        patternInput = findViewById(R.id.mirrorPatternInput)
        replacementInput = findViewById(R.id.mirrorReplacementInput)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.update_mirror_edit_title)
        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        editingId = intent.getStringExtra(EXTRA_MIRROR_ID)
        if (editingId != null) {
            val item = UpdatePrefs.loadMirrorRules(this).firstOrNull { it.id == editingId }
            if (item != null) {
                nameInput.setText(item.name)
                patternInput.setText(item.pattern)
                replacementInput.setText(item.replacement)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (editingId != null) {
            menu.add(Menu.NONE, MENU_DELETE, Menu.NONE, "🗑")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        }
        menu.add(Menu.NONE, MENU_SAVE, Menu.NONE, "💾")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_DELETE -> {
                deleteMirror()
                true
            }
            MENU_SAVE -> {
                saveMirror()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveMirror() {
        val name = nameInput.text?.toString().orEmpty().trim()
        val pattern = patternInput.text?.toString().orEmpty().trim()
        val replacement = replacementInput.text?.toString().orEmpty().trim()
        if (name.isEmpty() || pattern.isEmpty() || replacement.isEmpty()) {
            Toast.makeText(this, getString(R.string.update_mirror_fields_required), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            Regex(pattern)
        } catch (_: PatternSyntaxException) {
            Toast.makeText(this, getString(R.string.update_mirror_pattern_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        val list = UpdatePrefs.loadMirrorRules(this).toMutableList()
        val id = editingId
        if (id == null) {
            list += UpdatePrefs.newMirrorRule(name, pattern, replacement)
        } else {
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = MirrorRule(id, name, pattern, replacement)
            } else {
                list += MirrorRule(id, name, pattern, replacement)
            }
        }
        UpdatePrefs.saveMirrorRules(this, list)
        setResult(RESULT_OK)
        finish()
    }

    private fun deleteMirror() {
        val id = editingId ?: return
        val list = UpdatePrefs.loadMirrorRules(this).toMutableList()
        val changed = list.removeAll { it.id == id }
        if (changed) {
            UpdatePrefs.saveMirrorRules(this, list)
            val selected = UpdatePrefs.loadSelectedMirrorId(this)
            if (selected == id) {
                UpdatePrefs.saveSelectedMirrorId(this, null)
            }
        }
        setResult(RESULT_OK)
        finish()
    }
}
