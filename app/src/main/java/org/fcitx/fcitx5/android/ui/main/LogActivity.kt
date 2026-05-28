/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.BuildConfig
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.ActivityLogBinding
import org.fcitx.fcitx5.android.ui.main.log.LogView
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

    private fun registerLauncher() {
        launcher = registerForActivityResult(CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)!!.use { stream ->
                            stream.bufferedWriter().use { writer ->
                                writer.write(DeviceInfo.get(this@LogActivity))
                                writer.write(logView.currentLog)
                            }
                        }
                    }
                }.let { toast(it) }
            }
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
            setSupportActionBar(toolbar)
            this@LogActivity.logView = logView
            logView.setFilterQuery(logFilterQuery)
            if (intent.hasExtra(FROM_CRASH)) {
                fromCrash = true
                supportActionBar!!.setTitle(R.string.crash_logs)
                AlertDialog.Builder(this@LogActivity)
                    .setTitle(R.string.app_crash)
                    .setMessage(R.string.app_crash_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                logView.append("--------- Crash stacktrace")
                logView.append(intent.getStringExtra(CRASH_STACK_TRACE) ?: "<empty>")
                logView.setLogcat(Logcat(FcitxApplication.getLastPid()))
            } else {
                supportActionBar!!.apply {
                    setDisplayHomeAsUpEnabled(true)
                    setTitle(R.string.real_time_logs)
                }
                logView.setLogcat(Logcat())
            }
        }
        registerLauncher()
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
        menu.item(R.string.export, R.drawable.ic_baseline_save_24, iconTint, true) {
            launcher.launch("${BuildConfig.APPLICATION_ID}-${iso8601UTCDateTime()}.txt")
        }
        return true
    }

    companion object {
        const val FROM_CRASH = "from_crash"
        const val CRASH_STACK_TRACE = "crash_stack_trace"
        private const val STATE_LOG_FILTER_QUERY = "state_log_filter_query"
    }
}
