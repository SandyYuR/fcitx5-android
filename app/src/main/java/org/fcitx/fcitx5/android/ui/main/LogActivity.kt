/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
import org.fcitx.fcitx5.android.ui.main.log.LogClear
import org.fcitx.fcitx5.android.ui.main.log.LogExportViewModel
import org.fcitx.fcitx5.android.ui.main.log.LogView
import org.fcitx.fcitx5.android.ui.main.log.logExportStore
import org.fcitx.fcitx5.android.ui.main.log.mergePersistedAndLogcat
import org.fcitx.fcitx5.android.ui.main.log.normalizePersistedWallClock
import org.fcitx.fcitx5.android.utils.DeviceInfo
import org.fcitx.fcitx5.android.utils.Logcat
import org.fcitx.fcitx5.android.utils.LogFileManager
import org.fcitx.fcitx5.android.utils.LogSink
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.settingsColor
import org.fcitx.fcitx5.android.utils.toast

class LogActivity : SettingsUiActivity() {

    private var fromCrash = false
    private var crashStackTrace: String? = null

    /**
     * 崩溃堆栈是否已经被用户清空。
     *
     * 必须跟着 saved state 走：进程被回收后从最近任务恢复时，系统会把原启动 Intent 原样
     * 再发一遍，光删本地 extra 拦不住它。
     */
    private var crashStackTraceCleared = false
    private var crashDialog: AlertDialog? = null

    private lateinit var launcher: ActivityResultLauncher<String>
    private lateinit var logView: LogView
    private lateinit var searchView: TextInputEditText
    private lateinit var searchInputLayout: TextInputLayout
    private lateinit var statusText: TextView
    private lateinit var pageTitle: TextView
    private lateinit var btnSearch: MaterialButton
    private lateinit var btnFilter: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnExport: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var emptyState: TextView
    private lateinit var loadingIndicator: View
    private var selectedFilter = 0
    private var searchExpanded = false
    private val searchBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSearchExpanded(false)
    }

    private val exports by viewModels<LogExportViewModel>()
    private lateinit var logcat: Logcat

    /** 当前正在进行的历史加载任务；刷新会取消它，避免两次加载同时 setLogcat */
    private var reloadJob: Job? = null

    /** 这一侧对清空事务的观察：事务本身跑在进程作用域上，页面销毁不会取消它 */
    private var clearJob: Job? = null

    /** 历史加载是否还在进行中；为 true 时刷新按钮不可用 */
    private var loading = false

    /**
     * 清空是否还在进行中。
     *
     * 读的是进程级事务而不是本地字段：清空进行中页面被重建时，新页面的清空状态必须是
     * 「进行中」，否则它会立刻把还没删掉的历史读回来。
     */
    private val clearing: Boolean
        get() = LogClear.shared.clearing

    companion object {
        const val FROM_CRASH = "from_crash"
        const val CRASH_STACK_TRACE = "crash_stack_trace"

        /** 每次向列表发布的行数，避免一次性 append 几十万行卡住首帧 */
        private const val PUBLISH_CHUNK_SIZE = 500
        private const val STATE_SEARCH_EXPANDED = "log_search_expanded"
        private const val STATE_SEARCH_QUERY = "log_search_query"
        private const val STATE_FILTER = "log_filter"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 日志级别色由 Theme.FcitxSettings.Base 提供（colorLog*），无需额外 overlay：
        // super.onCreate 之后再 setTheme 不会作用到已创建的窗口，反而让级别色依赖一套脆弱的覆盖。
        super.onCreate(savedInstanceState)

        val binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 初始化视图
        logView = binding.logView
        searchView = binding.searchView
        searchInputLayout = binding.searchInputLayout
        statusText = binding.statusText
        pageTitle = binding.pageTitle
        btnSearch = binding.btnSearch
        btnFilter = binding.btnFilter
        btnRefresh = binding.btnRefresh
        btnExport = binding.btnExport
        btnClear = binding.btnClear
        emptyState = binding.emptyState
        loadingIndicator = binding.loadingIndicator
        logView.onEntriesChanged = { updateStatus() }
        onBackPressedDispatcher.addCallback(this, searchBack)

        // 初始化导出器
        launcher = registerForActivityResult(CreateDocument("text/plain")) { uri ->
            exports.destination(uri)  // 无论是否为 null 都调用
        }

        // 检查是否是崩溃报告并初始化 logcat
        val restored = restoreCrashSession(savedInstanceState, intent)
        fromCrash = restored.fromCrash
        crashStackTrace = restored.stackTrace
        crashStackTraceCleared = restored.cleared
        logcat = if (fromCrash) {
            pageTitle.setText(R.string.crash_logs)
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
            pageTitle.setText(R.string.real_time_logs)
            Logcat.default
        }

        // 设置搜索框
        setupSearchView()
        selectedFilter = savedInstanceState?.getInt(STATE_FILTER, 0)?.coerceIn(0, filterLabels.lastIndex) ?: 0
        applyFilter(selectedFilter)
        searchView.setText(savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty())
        setSearchExpanded(savedInstanceState?.getBoolean(STATE_SEARCH_EXPANDED) == true, requestInput = false)

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
     *
     * 持久化历史和 logcat 是同一批事件的两个来源：Logx 每条日志都同时写文件和 logcat，
     * 而 logd 会把积压留着，reader 不带起点时先重放整段积压。所以这里取两者的时间窗
     * 并集，按事件字段消掉重叠的那一份，再让实时 reader 从快照之后接着读。
     *
     * 快照本身不设起点。logd 里可能存在早于持久化历史第一行、且没有持久化孪生的记录：
     * 首次运行、历史被清空，以及 Application 在 LogSink 初始化之后、startup 标记之前
     * 打下的诊断都属于这一类。把历史起点当成 logcat 覆盖的起点会把它们整段丢出页面和
     * 导出；重叠的那一份则由 [mergePersistedAndLogcat] 按字段身份消掉，不需要边界。
     */
    private fun reloadLogs(announce: Boolean) {
        reloadJob?.cancel()
        // 旧 reader 必须先停，新 reader 才允许创建
        logcat.shutdownLogFlow()
        logView.clear()
        loading = true
        updateButtonState()
        reloadJob = lifecycleScope.launch {
            try {
                // 清空还没落地时读到的是准备删掉的历史，这一轮必然把已清掉的行贴回来。
                // 等它落地再读，而不是直接跳过：跳过的话，在清空窗口里重建出来的页面
                // 会既看不到清空、又一直空着，直到用户手动刷新。
                LogClear.shared.await()
                val merged = withContext(Dispatchers.IO) {
                    // threadtime 只有当地墙上时间：按文件还原成当前时区下的同一时刻，
                    // 否则改过时区之后历史会被整体挪几个小时，最新的行甚至落在未来。
                    val history = normalizePersistedWallClock(
                        LogFileManager.readAllLogGroups(this@LogActivity),
                        System.currentTimeMillis()
                    )
                    // 不传 since：历史起点不是 logcat 覆盖的起点，早于它的记录仍要保留。
                    val snapshot = logcat.getLogAsync()
                        .await()
                        .getOrDefault(emptyList())
                    mergePersistedAndLogcat(history, snapshot)
                }
                ensureActive()
                publishHistory(merged.lines)
                crashStackTrace?.takeIf { fromCrash }?.let {
                    logView.append("")
                    logView.append("--------- Crash stacktrace")
                    logView.append(it)
                }
                ensureActive()
                logView.setLogcat(logcat, merged.resumeFrom)
                updateStatus()
                if (announce) toast(R.string.refresh)
            } finally {
                loading = false
                updateButtonState()
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
        clearJob?.cancel()
        clearJob = null
        logcat.shutdownLogFlow()
        crashDialog?.dismiss()
        crashDialog = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 进程回收后只剩这份状态能证明「崩溃堆栈已经清空」
        outState.putBoolean(STATE_CRASH_STACK_CLEARED, crashStackTraceCleared)
        outState.putBoolean(STATE_SEARCH_EXPANDED, searchExpanded)
        outState.putString(STATE_SEARCH_QUERY, searchView.text?.toString())
        outState.putInt(STATE_FILTER, selectedFilter)
    }

    // 日志页操作由自己的顶栏承载，跳过 SettingsUiActivity 的键盘测试菜单。
    override fun onPrepareOptionsMenu(menu: Menu): Boolean = true

    private val filterLabels = listOf(
        R.string.log_filter_all_levels,
        R.string.log_filter_level_verbose,
        R.string.log_filter_level_debug,
        R.string.log_filter_level_info,
        R.string.log_filter_level_warning,
        R.string.log_filter_level_error,
        R.string.log_filter_level_fatal,
        R.string.log_filter_important,
        R.string.log_filter_warnings_errors,
        R.string.log_filter_rime_engine
    )

    private fun applyFilter(position: Int) {
        selectedFilter = position
        val selectedLevels = when (position) {
            1 -> setOf('V')
            2 -> setOf('D')
            3 -> setOf('I')
            4 -> setOf('W')
            5 -> setOf('E')
            6 -> setOf('F')
            7 -> setOf('I', 'W', 'E')
            8 -> setOf('W', 'E', 'F')
            // 0 与 9（Rime 引擎日志）都显示全部级别，仅内容过滤不同。
            else -> setOf('V', 'D', 'I', 'W', 'E', 'F')
        }
        logView.setLevelFilter(selectedLevels)
        logView.setContentFilter(if (position == 9) "rime" else "")
        btnFilter.iconTint = ColorStateList.valueOf(settingsColor(if (position == 0)
            com.google.android.material.R.attr.colorOnSurface else androidx.appcompat.R.attr.colorPrimary))
        btnFilter.contentDescription = getString(R.string.log_filter_label) + ": " + getString(filterLabels[position])
        updateStatus()
    }

    private fun showFilterMenu() {
        PopupMenu(this, btnFilter, Gravity.END, 0, R.style.Widget_Fcitx_LogFilterMenu).apply {
            filterLabels.forEachIndexed { index, label ->
                menu.add(0, index + 1, index, label).isChecked = index == selectedFilter
            }
            menu.setGroupCheckable(0, true, true)
            setOnMenuItemClickListener { item ->
                applyFilter(item.itemId - 1)
                true
            }
            show()
        }
    }

    private fun setupSearchView() {
        searchView.doAfterTextChanged { text ->
            logView.setFilterQuery(text?.toString().orEmpty())
            updateStatus()
        }
    }

    private fun setSearchExpanded(expanded: Boolean, requestInput: Boolean = true) {
        searchExpanded = expanded
        searchBack.isEnabled = expanded
        searchInputLayout.visibility = if (expanded) View.VISIBLE else View.GONE
        btnSearch.iconTint = ColorStateList.valueOf(settingsColor(if (expanded)
            androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface))
        if (expanded && requestInput) {
            searchView.requestFocus()
            searchView.post {
                if (searchExpanded) getSystemService(InputMethodManager::class.java)
                    .showSoftInput(searchView, InputMethodManager.SHOW_IMPLICIT)
            }
        } else if (!expanded) {
            searchView.text?.clear()
            searchView.clearFocus()
            if (requestInput) getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(searchView.windowToken, 0)
        }
    }

    private fun setupButtons() {
        btnSearch.setOnClickListener { setSearchExpanded(!searchExpanded) }
        btnFilter.setOnClickListener { showFilterMenu() }
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
            if (clearing) return@setOnClickListener
            // 加载中的历史必须在清空前取消：它会继续把清空前的快照 append 回来，
            // 于是刚清掉的行重新出现在列表里，还能被导出，而底层文件其实已经删除。
            reloadJob?.cancel()
            reloadJob = null
            loading = false
            // 实时 reader 也要先停：否则它在清空窗口里读到的行会混进清空后的列表。
            logcat.shutdownLogFlow()
            logView.clear()
            // 崩溃堆栈也算日志的一部分：只清列表不碰字段和 Intent，刷新或旋转都会把它
            // 重新贴回来，导出与分享也会再次带上已经清掉的内容。
            clearCrashStackTrace()

            // 删除都排在各自的队列里（logcat 子进程、LogSink 的 writer），从这一刻到文件
            // 真正消失之间有一个窗口。事务跑在进程作用域上：窗口里页面被重建，删除仍会
            // 走完，新页面等它落地再读历史，不会把准备删掉的行贴回来。
            val transaction = LogClear.shared.start {
                Logcat.default.clearLog().join()
                // 持久历史必须通过与 LogSink 同一个 writer 串行清理：
                // 直接删文件会留下仍指向已 unlink 文件的 writer，之后整段诊断全部丢失。
                LogSink.clear()
                CrashReportStore(
                    FcitxApplication.getInstance().directBootAwareContext.noBackupFilesDir
                ).delete()
            }
            updateButtonState()

            clearJob = lifecycleScope.launch {
                try {
                    transaction.join()
                    // 被取消的加载不会走到 setLogcat，实时 reader 需要在这里补接，
                    // 否则清空之后整段日志都不会再进来，直到用户手动刷新。
                    logView.setLogcat(logcat)
                    updateStatus()
                    toast(R.string.clear_log)
                } finally {
                    clearJob = null
                    updateButtonState()
                }
            }
        }
    }

    /**
     * 清空还没落地时，刷新、导出、清空都不可用：此时重读历史拿到的是还没删掉的行，导出
     * 出来的内容与「已清空」的页面自相矛盾。清空一完成就把三个按钮一起放开。
     *
     * 加载中同样要关掉刷新：连续两次加载都会走到 setLogcat，而旧 reader 还活着时
     * Logcat.initLogFlow 会抛 exception_logcat_created。
     */
    private fun updateButtonState() {
        btnRefresh.isEnabled = !clearing && !loading
        btnExport.isEnabled = !clearing
        btnClear.isEnabled = !clearing
        updateStatus()
    }

    /**
     * 丢弃崩溃堆栈的三个副本：内存字段负责当前这一帧，Intent extra 负责同进程重建，
     * saved state 负责进程被回收后重新拉起的那一次。
     */
    private fun clearCrashStackTrace() {
        crashStackTrace = null
        crashStackTraceCleared = true
        intent.removeExtra(CRASH_STACK_TRACE)
    }

    private fun showExportOptions() {
        if (clearing) return
        val options = arrayOf(
            getString(R.string.export_log),
            getString(R.string.share)
        )

        MaterialAlertDialogBuilder(this)
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

    /**
     * 分享与导出必须同源：两者都走 [LogExportStore.render]，都会带上保留的崩溃报告。
     *
     * 应用崩溃后重开普通日志页再分享时，崩溃报告是唯一的现场记录——崩溃进程的 logcat
     * 早已滚掉，而崩溃处理器也不经过 LogSink 写文件。只序列化当前列表会把它漏掉。
     */
    private fun shareLog() {
        val header = buildExportHeader()
        // 列表快照只能在主线程取；读崩溃报告和写临时文件都在 IO 上做
        val lines = logView.snapshotLines()
        val store = logExportStore(application)
        lifecycleScope.launch {
            val uri = try {
                withContext(Dispatchers.IO) {
                    val fullText = store.render(header, lines)
                    // 写入临时文件避免 Binder 事务限制。
                    // 目录与 authority 必须匹配：share.fileprovider 只暴露 cacheDir/shared/。
                    val shareDir = File(cacheDir, "shared").apply { mkdirs() }
                    val tempFile = File(shareDir, "log-${System.currentTimeMillis()}.txt")
                    tempFile.writeText(fullText)
                    FileProvider.getUriForFile(
                        this@LogActivity,
                        "${BuildConfig.APPLICATION_ID}.share.fileprovider",
                        tempFile
                    )
                }
            } catch (e: Exception) {
                toast("Failed to share log: ${e.message}")
                return@launch
            }

            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        "${getString(R.string.app_name)} - ${getString(R.string.real_time_logs)}"
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.share)))
            } catch (e: Exception) {
                toast("Failed to share log: ${e.message}")
            }
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
        loadingIndicator.visibility = if (loading || clearing) View.VISIBLE else View.GONE
        emptyState.visibility = if (visible == 0) View.VISIBLE else View.GONE
        emptyState.setText(when {
            clearing -> R.string.log_clearing
            loading -> R.string.log_loading
            total == 0 -> R.string.log_empty
            else -> R.string.log_no_matches
        })
        statusText.text = if (clearing) {
            getString(R.string.log_clearing)
        } else if (loading) {
            getString(R.string.log_loading)
        } else if (selectedFilter == 0) {
            getString(R.string.log_status_format, visible)
        } else {
            getString(R.string.log_status_format, visible) + " · " + getString(filterLabels[selectedFilter])
        }
    }
}

/** 清空崩溃堆栈这件事也要跨进程传递，键名固定。 */
internal const val STATE_CRASH_STACK_CLEARED = "crash_stack_trace_cleared"

/** 崩溃页在 onCreate 时真正要用的三样东西。 */
internal data class RestoredCrashSession(
    val fromCrash: Boolean,
    val cleared: Boolean,
    val stackTrace: String?
)

/**
 * 从 saved state 与启动 Intent 里还原崩溃页。
 *
 * 进程被系统回收后从最近任务恢复时，system server 会把原启动 Intent 原样再发一遍——
 * [android.app.Activity.getIntent] 只读写本地字段，删 extra 拦不住服务端那份。所以清空
 * 状态必须走 saved state：只要它说清空过，这次启动就当作普通日志页，绝不再贴回旧堆栈。
 */
internal fun restoreCrashSession(
    savedInstanceState: Bundle?,
    launchIntent: Intent
): RestoredCrashSession {
    val cleared = savedInstanceState?.getBoolean(STATE_CRASH_STACK_CLEARED) == true
    if (cleared) {
        return RestoredCrashSession(fromCrash = false, cleared = true, stackTrace = null)
    }
    return RestoredCrashSession(
        fromCrash = launchIntent.hasExtra(LogActivity.FROM_CRASH),
        cleared = false,
        stackTrace = launchIntent.getStringExtra(LogActivity.CRASH_STACK_TRACE)
    )
}
