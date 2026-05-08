/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

class LayoutNameInputActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_LABEL = "label"
        const val EXTRA_HINT = "hint"
        const val EXTRA_INITIAL_VALUE = "initial_value"
        const val EXTRA_COPY_SOURCE_OPTIONS = "copy_source_options"
        const val EXTRA_COPY_SOURCE_DEFAULT = "copy_source_default"
        const val EXTRA_RESULT_LAYOUT_NAME = "result_layout_name"
        const val EXTRA_RESULT_COPY_SOURCE = "result_copy_source"

        private const val MENU_SAVE_ID = 9101
    }

    private val toolbar by lazy {
        Toolbar(this).apply {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private lateinit var input: AppCompatEditText
    private var copySourceSpinner: Spinner? = null
    private var copySources: List<String> = emptyList()

    private val content by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)

            addView(TextView(this@LayoutNameInputActivity).apply {
                text = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank {
                    getString(R.string.text_keyboard_layout_layout_name)
                }
                textSize = 13f
                setTextColor(styledColor(android.R.attr.textColorSecondary))
            })

            input = AppCompatEditText(this@LayoutNameInputActivity).apply {
                hint = intent.getStringExtra(EXTRA_HINT).orEmpty().ifBlank {
                    getString(R.string.text_keyboard_layout_layout_name_hint)
                }
                setText(intent.getStringExtra(EXTRA_INITIAL_VALUE).orEmpty())
                setSelection(text?.length ?: 0)
            }
            addView(input, LinearLayout.LayoutParams(matchParent, wrapContent))

            copySources = intent.getStringArrayListExtra(EXTRA_COPY_SOURCE_OPTIONS)?.toList().orEmpty()
            if (copySources.isNotEmpty()) {
                addView(TextView(this@LayoutNameInputActivity).apply {
                    text = getString(R.string.text_keyboard_layout_copy_from)
                    textSize = 13f
                    setTextColor(styledColor(android.R.attr.textColorSecondary))
                    setPadding(0, dp(12), 0, 0)
                })
                val adapter = ArrayAdapter(
                    this@LayoutNameInputActivity,
                    android.R.layout.simple_spinner_item,
                    copySources
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                copySourceSpinner = Spinner(this@LayoutNameInputActivity).apply {
                    this.adapter = adapter
                    val defaultValue = intent.getStringExtra(EXTRA_COPY_SOURCE_DEFAULT).orEmpty()
                    val defaultIndex = copySources.indexOf(defaultValue).takeIf { it >= 0 } ?: 0
                    setSelection(defaultIndex)
                }
                addView(copySourceSpinner, LinearLayout.LayoutParams(matchParent, wrapContent))
            }
        }
    }

    private val root by lazy {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(matchParent, wrapContent))
            addView(content, LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
            getString(R.string.text_keyboard_layout_add_layout)
        }

        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_SAVE_ID, Menu.NONE, getString(R.string.save))
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_SAVE_ID -> {
                val name = input.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, getString(R.string.text_keyboard_layout_name_empty), Toast.LENGTH_SHORT).show()
                    return true
                }
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_RESULT_LAYOUT_NAME, name)
                    copySourceSpinner?.selectedItem?.toString()?.let {
                        putExtra(EXTRA_RESULT_COPY_SOURCE, it)
                    }
                })
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
