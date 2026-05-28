/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor

class UpdateMirrorListActivity : AppCompatActivity() {
    private lateinit var toolbar: Toolbar
    private lateinit var addButton: ImageButton
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: UpdateMirrorAdapter

    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            renderList()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_update_mirror_list)
        toolbar = findViewById(R.id.toolbar)
        toolbar.backgroundColor = styledColor(android.R.attr.colorPrimary)
        toolbar.elevation = dp(4f)
        addButton = findViewById(R.id.addMirrorRuleButton)
        recycler = findViewById(R.id.mirrorRecycler)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.update_mirror_settings_title)
        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        adapter = UpdateMirrorAdapter { mirror ->
            editorLauncher.launch(
                Intent(this, UpdateMirrorEditActivity::class.java).apply {
                    putExtra(UpdateMirrorEditActivity.EXTRA_MIRROR_ID, mirror.id)
                }
            )
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        addButton.setOnClickListener {
            editorLauncher.launch(Intent(this, UpdateMirrorEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderList() {
        adapter.submitList(UpdatePrefs.loadMirrorRules(this))
    }
}
