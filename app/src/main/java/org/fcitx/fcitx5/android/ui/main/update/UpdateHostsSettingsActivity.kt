/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.formatDateTime
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import timber.log.Timber

class UpdateHostsSettingsActivity : AppCompatActivity() {
    private lateinit var toolbar: Toolbar
    private lateinit var sourceInput: EditText
    private lateinit var enableSwitch: SwitchCompat
    private lateinit var updateButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_update_hosts)
        toolbar = findViewById(R.id.toolbar)
        toolbar.backgroundColor = styledColor(android.R.attr.colorPrimary)
        toolbar.elevation = dp(4f)
        sourceInput = findViewById(R.id.hostsSourceInput)
        enableSwitch = findViewById(R.id.hostsEnableSwitch)
        updateButton = findViewById(R.id.hostsUpdateButton)
        statusText = findViewById(R.id.hostsStatusText)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.update_hosts_settings_title)
        val toolbarBaseTopPadding = toolbar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = toolbarBaseTopPadding + statusTop)
            insets
        }
        ViewCompat.requestApplyInsets(toolbar)

        val config = UpdatePrefs.loadHostsConfig(this)
        sourceInput.setText(config.sourceUrl)
        enableSwitch.isChecked = config.enabled
        renderStatus(config)
        enableSwitch.setOnCheckedChangeListener { _, checked ->
            UpdateRepository.saveHostsEnabled(this, checked)
        }

        updateButton.setOnClickListener {
            val source = sourceInput.text?.toString().orEmpty().trim()
            if (source.isEmpty()) {
                Toast.makeText(this, getString(R.string.update_hosts_source_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!enableSwitch.isChecked) {
                Toast.makeText(this, getString(R.string.update_hosts_enable_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            UpdateRepository.saveHostsSource(this, source)
            lifecycleScope.launch {
                try {
                    val count = UpdateRepository.updateHostsMapping(this@UpdateHostsSettingsActivity)
                    renderStatus(UpdatePrefs.loadHostsConfig(this@UpdateHostsSettingsActivity))
                    Toast.makeText(
                        this@UpdateHostsSettingsActivity,
                        getString(R.string.update_hosts_updated_count, count),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (t: Throwable) {
                    Timber.w(t, "Failed to update hosts mappings")
                    Toast.makeText(
                        this@UpdateHostsSettingsActivity,
                        getString(R.string.update_hosts_update_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val source = sourceInput.text?.toString().orEmpty().trim()
        UpdateRepository.saveHostsEnabled(this, enableSwitch.isChecked)
        UpdateRepository.saveHostsSource(this, source)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun renderStatus(config: HostsConfig) {
        if (config.updatedAt <= 0L) {
            statusText.text = getString(R.string.update_hosts_status_never)
            return
        }
        statusText.text = getString(
            R.string.update_hosts_status_line,
            formatDateTime(config.updatedAt),
            config.mapping.size
        )
    }
}
