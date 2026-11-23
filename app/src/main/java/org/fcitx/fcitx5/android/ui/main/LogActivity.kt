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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.ActivityLogBinding
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.log.CrashReportStore
import org.fcitx.fcitx5.android.ui.main.log.LogExportViewModel
import org.fcitx.fcitx5.android.ui.main.log.LogView
import org.fcitx.fcitx5.android.utils.DeviceInfo
import org.fcitx.fcitx5.android.utils.Logcat
import org.fcitx.fcitx5.android.utils.LogFileManager
import org.fcitx.fcitx5.android.utils.LogSink
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.toast

class LogActivity : SettingsUiActivity() {

    private var fromCrash = false
    private var crashStackTrace: String? = null
    private var crashDialog: AlertDialog? = null

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

    /** 当前正在进行的历史加载任务；刷新会取消它，避免两次加载同时 setLogcat */
    private var reloadJob: Job? = null

    companion object {
        const val FROM_CRASH = "from_crash"
        const val CRASH_STACK_TRACE = "crash_stack_trace"

        /** 每次向列表发布的行数，避免一次性 append 几十万行卡住首帧 */
        private const val PUBLISH_CHUNK_SIZE = 500
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 日志级别色由 Theme.FcitxSettings.Base 提供（colorLog*），无需额外 overlay：
        // super.onCreate 之后再 setTheme 不会作用到已创建的窗口，反而让级别色依赖一套脆弱的覆盖。
        super.onCreate(savedInstanceState)

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
        fromCrash = intent.hasExtra(FROM_CRASH)
        crashStackTrace = intent.getStringExtra(CRASH_STACK_TRACE)
        logcat = if (fromCrash) {
            supportActionBar?.setTitle(R.string.crash_logs)
            if (savedInstanceState == null) {
                crashDialog = MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.app_crash)
                    .setMessage(R.string.app_crash_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            // 崩溃日志：读崩溃进程的 PID，进程已死但 logcat 缓冲区还在
            Logcat(FcitxApplication.getLastPid())
        } else {
            supportActionBar?.setTitle(R.string.real_time_logs)
            Logcat.default
        }

        // 设置级别 Spinner
        setupLevelSpinner()

        // 设置搜索框
        setupSearchView()

        // 设置按钮
        setupButtons()

        // 加载日志
        reloadLogs(announce = false)

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

    /**
     * 重新载入持久化历史并重新接上实时 logcat。
     *
     * 同一时刻只允许一个加载任务：刷新时取消上一个（包括 onCreate 起的首次加载），
     * 否则两次加载都会走到 setLogcat，而 Logcat.initLogFlow 发现旧 reader 还活着会抛
     * exception_logcat_created。
     */
    private fun reloadLogs(announce: Boolean) {
        reloadJob?.cancel()
        // 旧 reader 必须先停，新 reader 才允许创建
        logcat.shutdownLogFlow()
        logView.clear()
        btnRefresh.isEnabled = false
        reloadJob = lifecycleScope.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    LogFileManager.readAllLogs(this@LogActivity)
                }
                ensureActive()
                publishHistory(history)
                crashStackTrace?.takeIf { fromCrash }?.let {
                    logView.append("")
                    logView.append("--------- Crash stacktrace")
                    logView.append(it)
                }
                ensureActive()
                logView.setLogcat(logcat)
                updateStatus()
                if (announce) toast(R.string.refresh)
            } finally {
                btnRefresh.isEnabled = true
            }
        }
    }

    /**
     * 分批把历史行喂给列表并在批次之间让出主线程，避免几十 MB 日志阻塞首帧。
     */
    private suspend fun publishHistory(lines: List<String>) {
        var index = 0
        while (index < lines.size) {
            currentCoroutineContext().ensureActive()
            val end = minOf(index + PUBLISH_CHUNK_SIZE, lines.size)
            logView.appendAll(lines.subList(index, end))
            index = end
            yield()
        }
    }

    override fun onDestroy() {
        reloadJob?.cancel()
        reloadJob = null
        logcat.shutdownLogFlow()
        crashDialog?.dismiss()
        crashDialog = null
        super.onDestroy()
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
                    1 -> setOf('V')
                    2 -> setOf('D')
                    3 -> setOf('I')
                    4 -> setOf('W')
                    5 -> setOf('E')
                    6 -> setOf('F')
                    7 -> setOf('I', 'W', 'E')
                    8 -> setOf('W', 'E', 'F')
                    // 0 与 9（Rime 引擎日志）都显示全部级别，仅内容过滤不同
                    else -> setOf('V', 'D', 'I', 'W', 'E', 'F')
                }
                logView.setLevelFilter(selectedLevels)

                // Rime 引擎日志：内容过滤
                logView.setContentFilter(if (position == 9) "rime" else "")
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
            reloadLogs(announce = true)
        }

        // 导出按钮（带分享选项）
        btnExport.setOnClickListener {
            showExportOptions()
        }

        // 清空按钮
        btnClear.setOnClickListener {
            // 加载中的历史必须在清空前取消：它会继续把清空前的快照 append 回来，
            // 于是刚清掉的行重新出现在列表里，还能被导出，而底层文件其实已经删除。
            val loadWasPending = reloadJob?.isActive == true
            reloadJob?.cancel()
            reloadJob = null
            logcat.clearLog()
            logView.clear()
            // 持久历史必须通过与 LogSink 同一个 writer 串行清理：
            // 直接删文件会留下仍指向已 unlink 文件的 writer，之后整段诊断全部丢失。
            LogSink.clear()
            lifecycleScope.launch(Dispatchers.IO) {
                CrashReportStore(FcitxApplication.getInstance().directBootAwareContext.noBackupFilesDir)
                    .delete()
            }
            if (loadWasPending) {
                // 被取消的加载不会走到 setLogcat，实时 reader 需要在这里补接，
                // 否则清空之后整段日志都不会再进来，直到用户手动刷新。
                logcat.shutdownLogFlow()
                logView.setLogcat(logcat)
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

        AlertDialog.Builder(this)
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
            // 写入临时文件避免 Binder 事务限制。
            // 目录与 authority 必须匹配：share.fileprovider 只暴露 cacheDir/shared/。
            val shareDir = File(cacheDir, "shared").apply { mkdirs() }
            val tempFile = File(shareDir, "log-${System.currentTimeMillis()}.txt")
            tempFile.writeText(fullText)

            val uri = FileProvider.getUriForFile(
                this,
                "${BuildConfig.APPLICATION_ID}.share.fileprovider",
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
        val (_, visible) = logView.getFilterStatus()
        val currentFilter = logView.currentLevelFilter()

        statusText.text = if (currentFilter.size == 6) {
            getString(R.string.log_status_format, visible)
        } else {
            val levels = currentFilter.sorted().joinToString("/")
            getString(R.string.log_status_filtered_format, visible, levels)
        }
    }
}
