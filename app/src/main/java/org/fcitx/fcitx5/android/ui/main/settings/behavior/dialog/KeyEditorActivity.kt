/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.addCallback
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.utils.settingsColor
import splitties.dimensions.dp

class KeyEditorActivity : SettingsUiActivity() {
    private val results = KeyEditorResultHost(this)
    private lateinit var form: KeyEditorForm
    private var saveItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        form = results.createForm(this, intent, savedInstanceState)
        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@KeyEditorActivity).apply {
                setPadding(dp(16), dp(12), dp(16), dp(16))
                clipToPadding = false
                addView(form.view)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = form.title
            subtitle = form.subtitle
        }
        form.onChange = { saveItem?.isEnabled = form.hasChanges() }
        results.bind(form)
        onBackPressedDispatcher.addCallback(this) { attemptExit() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (form.canDelete) menu.add(0, 2, 0, R.string.delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        saveItem = menu.add(0, 1, 1, R.string.layout_editor_done).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            isEnabled = form.hasChanges()
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { attemptExit(); true }
        1 -> { save(); true }
        2 -> {
            MaterialAlertDialogBuilder(this).setTitle(R.string.delete)
                .setMessage(R.string.text_keyboard_layout_delete_key_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    setResult(RESULT_OK, form.resultIntent(RESULT_ACTION_DELETE)); finish()
                }.setNegativeButton(android.R.string.cancel, null).show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun save() {
        val key = form.validatedDraft() ?: return
        setResult(RESULT_OK, form.resultIntent(RESULT_ACTION_SAVE, key))
        finish()
    }

    private fun attemptExit() {
        if (!form.hasChanges()) { finish(); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.layout_editor_discard_key_title)
            .setMessage(R.string.layout_editor_discard_key_message)
            .setPositiveButton(R.string.layout_editor_done) { _, _ -> save() }
            .setNegativeButton(R.string.layout_editor_continue, null)
            .setNeutralButton(R.string.layout_editor_discard) { _, _ -> finish() }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        form.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_SECTION = KeyEditorForm.EXTRA_SECTION
        const val EXTRA_KEY_DATA = KeyEditorForm.EXTRA_KEY_DATA
        const val EXTRA_ROW_INDEX = KeyEditorForm.EXTRA_ROW_INDEX
        const val EXTRA_KEY_INDEX = KeyEditorForm.EXTRA_KEY_INDEX
        const val EXTRA_IS_EDITING_SUBMODE_LAYOUT = KeyEditorForm.EXTRA_IS_EDITING_SUBMODE_LAYOUT
        const val EXTRA_CURRENT_SUBMODE_LABEL = KeyEditorForm.EXTRA_CURRENT_SUBMODE_LABEL
        const val EXTRA_HAS_MULTI_SUBMODE_SUPPORT = KeyEditorForm.EXTRA_HAS_MULTI_SUBMODE_SUPPORT
        const val EXTRA_AVAILABLE_LAYOUT_TARGETS = KeyEditorForm.EXTRA_AVAILABLE_LAYOUT_TARGETS
        const val EXTRA_LOCK_TYPE_SELECTION = KeyEditorForm.EXTRA_LOCK_TYPE_SELECTION
        const val EXTRA_DISABLE_WEIGHT_EDITING = KeyEditorForm.EXTRA_DISABLE_WEIGHT_EDITING
        const val EXTRA_COMPOSE_OVERRIDE_EDITOR_MODE = KeyEditorForm.EXTRA_COMPOSE_OVERRIDE_EDITOR_MODE
        const val EXTRA_HAS_INITIAL_COMPOSE_OVERRIDE = KeyEditorForm.EXTRA_HAS_INITIAL_COMPOSE_OVERRIDE
        const val EXTRA_FIXED_TYPE = KeyEditorForm.EXTRA_FIXED_TYPE
        const val EXTRA_TITLE_OVERRIDE = KeyEditorForm.EXTRA_TITLE_OVERRIDE
        const val EXTRA_RESULT_ACTION = KeyEditorForm.EXTRA_RESULT_ACTION
        const val EXTRA_RESULT_KEY_DATA = KeyEditorForm.EXTRA_RESULT_KEY_DATA
        const val RESULT_ACTION_SAVE = KeyEditorForm.RESULT_ACTION_SAVE
        const val RESULT_ACTION_DELETE = KeyEditorForm.RESULT_ACTION_DELETE
        @JvmStatic fun toSerializableMap(input: Map<String, Any?>) = KeyEditorForm.toSerializableMap(input)
    }
}
