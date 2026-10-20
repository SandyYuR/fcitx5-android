/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import com.google.android.material.dialog.MaterialAlertDialogBuilder

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.ActivityLogBinding
import org.fcitx.fcitx5.android.ui.main.log.LogView
import org.fcitx.fcitx5.android.ui.main.log.LogExportViewModel
import org.fcitx.fcitx5.android.utils.DeviceInfo
import org.fcitx.fcitx5.android.utils.Logcat
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.toast
import splitties.resources.styledColor
import splitties.views.topPadding

class LogActivity : AppCompatActivity() {

    private var fromCrash = false
    private var logFilterQuery = ""

    private lateinit var launcher: ActivityResultLauncher<String>
    private lateinit var logView: LogView
    private val exports by viewModels<LogExportViewModel>()
    private var exportItem: MenuItem? = null

    private fun registerLauncher() {
        launcher = registerForActivityResult(CreateDocument("text/plain")) { uri ->
            exports.destination(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logFilterQuery = savedInstanceState?.getString(STATE_LOG_FILTER_QUERY).orEmpty()
        enableEdgeToEdge()
        val binding = ActivityLogBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            binding.logView.setBottomPadding(navBars.bottom)
            windowInsets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        with(binding) {
            this@LogActivity.logView = logView
            logView.setFilterQuery(logFilterQuery)
            if (intent.hasExtra(FROM_CRASH)) {
                fromCrash = true
                supportActionBar!!.setTitle(R.string.crash_logs)
                MaterialAlertDialogBuilder(this@LogActivity)
                    .setTitle(R.string.app_crash)
                    .setMessage(R.string.app_crash_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                logView.append("--------- Crash stacktrace")
                logView.append(intent.getStringExtra(CRASH_STACK_TRACE) ?: "<empty>")
                logView.setLogcat(Logcat(FcitxApplication.getLastPid()))
            } else {
                supportActionBar!!.setDisplayHomeAsUpEnabled(true)
                supportActionBar!!.setTitle(R.string.real_time_logs)
                logView.setLogcat(Logcat())
            }
        }
        registerLauncher()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                exports.state.collect { state ->
                    exportItem?.isEnabled = !state.busy
                    when (state.phase) {
                        LogExportViewModel.Phase.READY -> if (exports.launchPicker()) {
                            try {
                                launcher.launch("${BuildConfig.APPLICATION_ID}-${iso8601UTCDateTime()}.txt")
                            } catch (e: Exception) {
                                exports.pickerFailed(e)
                            }
                        }
                        LogExportViewModel.Phase.SUCCESS -> {
                            exports.consumeResult()
                            toast(Result.success(Unit))
                        }
                        LogExportViewModel.Phase.FAILURE -> {
                            exports.consumeResult()
                            state.error?.let { toast(it) }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_LOG_FILTER_QUERY, logView.currentFilterQuery())
        super.onSaveInstanceState(outState)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        val searchItem = menu.item(R.string.search, R.drawable.ic_baseline_search_24, iconTint, true)
        val searchView = SearchView(this).apply {
            queryHint = getString(R.string.search)
            isSubmitButtonEnabled = false
            setQuery(logView.currentFilterQuery(), false)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String): Boolean {
                    clearFocus()
                    return true
                }

                override fun onQueryTextChange(newText: String): Boolean {
                    logView.setFilterQuery(newText)
                    logFilterQuery = newText
                    return true
                }
            })
        }
        searchItem.setShowAsAction(
            MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
        )
        searchItem.actionView = searchView
        if (logView.currentFilterQuery().isNotBlank()) {
            searchItem.expandActionView()
        }
        if (!fromCrash) {
            menu.item(R.string.clear, R.drawable.ic_baseline_delete_24, iconTint, true) {
                logView.clear()
            }
        }
        exportItem = menu.item(R.string.export, R.drawable.ic_baseline_save_24, iconTint, true) {
            if (!exports.state.value.busy) {
                // Freeze UI-owned state BEFORE dispatching IO or opening the destination.
                exports.prepare(DeviceInfo.get(this), logView.snapshotLines())
            }
        }.apply { isEnabled = !exports.state.value.busy }
        return true
    }

    companion object {
        const val FROM_CRASH = "from_crash"
        const val CRASH_STACK_TRACE = "crash_stack_trace"
        private const val STATE_LOG_FILTER_QUERY = "state_log_filter_query"
    }
}
