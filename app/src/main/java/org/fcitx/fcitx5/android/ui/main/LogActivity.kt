/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.ActivityLogBinding
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.log.LogExportViewModel
import org.fcitx.fcitx5.android.ui.main.log.LogView
import org.fcitx.fcitx5.android.utils.DeviceInfo
import org.fcitx.fcitx5.android.utils.Logcat
import org.fcitx.fcitx5.android.utils.LogFileManager
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.toast

class LogActivity : SettingsUiActivity() {

    private var fromCrash = false

    private lateinit var launcher: ActivityResultLauncher<String>
    private lateinit var logView: LogView
    private lateinit var searchView: SearchView
    private lateinit var levelSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnExport: ImageButton
    private lateinit var btnClear: ImageButton
    
    private val exports by viewModels<LogExportViewModel>()
    private lateinit var logcat: Logcat

    companion object {
        const val FROM_CRASH = "real_crash"
        const val CRASH_STACK_TRACE = "crash_stack_trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 应用日志颜色主题
        setTheme(R.style.ThemeOverlay_FcitxSettings_Log)
        
        val binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // 初始化视图
        logView = binding.logView
        searchView = binding.searchView
        levelSpinner = binding.levelSpinner
        statusText = binding.statusText
        btnRefresh = binding.btnRefresh
        btnExport = binding.btnExport
        btnClear = binding.btnClear
        
        // 初始化导出器
        launcher = registerForActivityResult(CreateDocument("text/plain")) { uri ->
            exports.destination(uri)  // 无论是否为 null 都调用
        }
        
        // 检查是否是崩溃报告并初始化 logcat
        fromCrash = intent.getBooleanExtra(FROM_CRASH, false)
        logcat = if (fromCrash) {
            // 崩溃日志：使用崩溃进程的 PID 获取 logcat
            try {
                Logcat(org.fcitx.fcitx5.android.FcitxApplication.getLastPid())
            } catch (e: Exception) {
                // 如果获取失败，回退到默认
                Logcat.default
            }
        } else {
            Logcat.default
        }
        
        // 设置级别 Spinner
        setupLevelSpinner()
        
        // 设置搜索框
        setupSearchView()
        
        // 设置按钮
        setupButtons()
        
        // 加载日志
        if (fromCrash) {
            // 崩溃日志：异步加载文件，再加载 logcat，最后添加崩溃堆栈
            lifecycleScope.launch(Dispatchers.IO) {
                val history = LogFileManager.readAllLogs(this@LogActivity)
                withContext(Dispatchers.Main) {
                    history.forEach { logView.append(it) }
                    logView.setLogcat(logcat)
                    intent.getStringExtra(CRASH_STACK_TRACE)?.also {
                        logView.append("")
                        logView.append("--------- Crash stacktrace")
                        logView.append(it)
                    }
                }
            }
        } else {
            // 正常日志：异步加载文件，再实时 logcat
            lifecycleScope.launch(Dispatchers.IO) {
                val history = LogFileManager.readAllLogs(this@LogActivity)
                withContext(Dispatchers.Main) {
                    history.forEach { logView.append(it) }
                    logView.setLogcat(logcat)
                }
            }
        }
        
        // 监听导出状态
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                exports.state.collect { state ->
                    when (state.phase) {
                        LogExportViewModel.Phase.READY -> {
                            if (exports.launchPicker()) {
                                try {
                                    launcher.launch("${BuildConfig.APPLICATION_ID}-${iso8601UTCDateTime()}.txt")
                                } catch (e: Exception) {
                                    exports.pickerFailed(e)
                                }
                            }
                        }
                        LogExportViewModel.Phase.SUCCESS -> {
                            exports.consumeResult()
                            toast(R.string.export_log)
                        }
                        LogExportViewModel.Phase.FAILURE -> {
                            exports.consumeResult()
                            state.error?.let { toast(it) }
                        }
                        else -> {}
                    }
                }
            }
        }
        
        // 更新状态
        updateStatus()
    }
    
    private fun setupLevelSpinner() {
        val levels = listOf(
            "全部级别",
            "V (Verbose)",
            "D (Debug)",
            "I (Info)",
            "W (Warning)",
            "E (Error)",
            "F (Fatal)",
            "I/W/E (重要)",
            "W/E/F (警告及错误)",
            "Rime 引擎日志"
        )
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels)
        levelSpinner.adapter = adapter
        
        levelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLevels = when (position) {
                    0 -> setOf('V', 'D', 'I', 'W', 'E', 'F')
                    1 -> setOf('V')
                    2 -> setOf('D')
                    3 -> setOf('I')
                    4 -> setOf('W')
                    5 -> setOf('E')
                    6 -> setOf('F')
                    7 -> setOf('I', 'W', 'E')
                    8 -> setOf('W', 'E', 'F')
                    9 -> setOf('V', 'D', 'I', 'W', 'E', 'F') // Rime 引擎日志：显示所有级别
                    else -> setOf('V', 'D', 'I', 'W', 'E', 'F')
                }
                logView.setLevelFilter(selectedLevels)
                
                // Rime 引擎日志：内容过滤
                if (position == 9) {
                    logView.setContentFilter("rime")
                } else {
                    logView.setContentFilter("")
                }
                updateStatus()
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun setupSearchView() {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return true
            }
            
            override fun onQueryTextChange(newText: String?): Boolean {
                logView.setFilterQuery(newText ?: "")
                updateStatus()
                return true
            }
        })
    }
    
    private fun setupButtons() {
        // 刷新按钮
        btnRefresh.setOnClickListener {
            // 停止当前流
            logcat.shutdownLogFlow()
            // 清空
            logView.clear()
            // 异步重新加载
            lifecycleScope.launch(Dispatchers.IO) {
                val history = LogFileManager.readAllLogs(this@LogActivity)
                withContext(Dispatchers.Main) {
                    history.forEach { logView.append(it) }
                    logView.setLogcat(logcat)
                    updateStatus()
                    toast(R.string.refresh)
                }
            }
        }
        
        // 导出按钮（带分享选项）
        btnExport.setOnClickListener {
            showExportOptions()
        }
        
        // 清空按钮
        btnClear.setOnClickListener {
            logcat.clearLog()
            logView.clear()
            // 清除持久化的日志文件和崩溃报告
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // 删除所有日志文件
                    LogFileManager.getAllLogFiles(this@LogActivity).forEach { it.delete() }
                    // 删除崩溃报告
                    val crashFile = File(this@LogActivity.noBackupFilesDir, "crash-reports/last-app-crash.txt")
                    crashFile.delete()
                } catch (e: Exception) {
                    // 忽略清理错误
                }
            }
            updateStatus()
            toast(R.string.clear_log)
        }
    }
    
    private fun showExportOptions() {
        val options = arrayOf(
            getString(R.string.export_log),
            getString(R.string.share)
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.export_log))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportToFile()
                    1 -> shareLog()
                }
            }
            .show()
    }
    
    private fun exportToFile() {
        val header = buildExportHeader()
        val lines = logView.snapshotLines()
        exports.prepare(header, lines)
    }
    
    private fun shareLog() {
        val header = buildExportHeader()
        val content = logView.snapshotLines()
        val fullText = header + "\n" + content.joinToString("\n")
        
        try {
            // 写入临时文件避免 Binder 事务限制
            val tempFile = File(cacheDir, "shared-log-${System.currentTimeMillis()}.txt")
            tempFile.writeText(fullText)
            
            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                tempFile
            )
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "${getString(R.string.app_name)} - ${getString(R.string.real_time_logs)}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (e: Exception) {
            toast("Failed to share log: ${e.message}")
        }
    }
    
    private fun buildExportHeader(): String {
        val deviceInfo = DeviceInfo.get(this)
        val (total, visible) = logView.getFilterStatus()
        val currentFilter = logView.currentLevelFilter()
        val currentQuery = logView.currentFilterQuery()
        
        val filterDesc = if (currentFilter.size == 6) {
            "All levels"
        } else {
            currentFilter.sorted().joinToString("/")
        }
        
        return """
            |===========================================
            |   Fcitx5 for Android - Log Export
            |===========================================
            |$deviceInfo
            |
            |Format: logcat threadtime (MM-DD HH:mm:ss.SSS PID TID LEVEL TAG: message)
            |Scope: ${if (fromCrash) "Crash Report" else "Runtime Logs"}
            |Total entries: $total
            |Visible entries: $visible
            |Level filter: $filterDesc
            |Search query: ${if (currentQuery.isNotEmpty()) currentQuery else "none"}
            |Dropped: Logs may be truncated due to logcat buffer limits
            |===========================================
            |
        """.trimMargin()
    }
    
    private fun updateStatus() {
        val (total, visible) = logView.getFilterStatus()
        val currentFilter = logView.currentLevelFilter()
        
        statusText.text = if (currentFilter.size == 6) {
            getString(R.string.log_status_format, visible)
        } else {
            val levels = currentFilter.sorted().joinToString("/")
            getString(R.string.log_status_filtered_format, visible, levels)
        }
    }
    
    override fun onDestroy() {
        logcat.shutdownLogFlow()
        super.onDestroy()
    }
}