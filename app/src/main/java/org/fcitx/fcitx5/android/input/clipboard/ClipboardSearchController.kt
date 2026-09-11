/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry

/**
 * 剪贴板历史搜索的会话状态。
 *
 * 参考 SyncClipboard 历史搜索的匹配语义：实时输入、大小写不敏感的子串匹配。
 * 搜索会话激活期间，键盘输入被拦截为查询文本（见 FcitxInputMethodService），
 * 不再写入目标编辑器；匹配结果复用预编辑上方的辅助选择栏展示。
 */
object ClipboardSearchController {

    /**
     * 辅助选择栏最多展示的条目数。辅助栏不是虚拟化列表，截断可避免一次性
     * 构建过多卡片；结果本身已按时间倒序排列，截断只丢弃最旧的条目。
     */
    private const val MAX_RESULTS = 50

    var isActive = false
        private set

    var results: List<ClipboardEntry> = emptyList()
        private set

    val committedText: String get() = committedQuery
    val preeditText: String get() = preedit
    val queryText: String get() = committedQuery + preedit

    private var committedQuery = ""
    private var preedit = ""
    private var searchJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 状态/查询/结果变化时回调，由 InputView 安装并负责刷新工具栏搜索框与辅助栏。
     * 为避免后台常驻引用，InputView 在 onDetachedFromWindow 时必须置空。
     */
    var onStateChanged: (() -> Unit)? = null

    fun start() {
        if (isActive) return
        isActive = true
        committedQuery = ""
        preedit = ""
        searchJob?.cancel()
        searchJob = null
        results = emptyList()
        notifyChanged()
    }

    fun stop() {
        if (!isActive) return
        isActive = false
        committedQuery = ""
        preedit = ""
        searchJob?.cancel()
        searchJob = null
        results = emptyList()
        notifyChanged()
    }

    /** 由 FcitxInputMethodService.commitText 调用；返回 true 表示已被搜索消费。 */
    fun onCommitText(text: String): Boolean {
        if (!isActive || text.isEmpty()) return false
        committedQuery += text
        // 提交后引擎随后会下发空 preedit 事件补齐，这里先清零，避免中间态把
        // 旧 preedit 与新提交拼成错误的查询。
        preedit = ""
        runSearch()
        return true
    }

    /** 虚拟退格键；会话激活期间一律由查询消费，绝不触碰目标编辑器。 */
    fun onBackspace(): Boolean {
        if (!isActive) return false
        // preedit 非空时引擎内部已消费了这次退格，直接吞掉即可。
        if (committedQuery.isNotEmpty() && preedit.isEmpty()) {
            committedQuery = committedQuery.dropLast(1)
            runSearch()
        }
        return true
    }

    /**
     * 来自模拟物理键盘路径的字符（数字键等走 sendSimulatedKeyEvent，经
     * forwardKeyEvent 直达引擎，unicode 字符由 App 端 Qwerty 翻译）。
     *
     * 返回 true 表示调用方不应再把该事件透传给目标编辑器。
     */
    fun onPhysicalChar(char: Char): Boolean {
        if (!isActive) return false
        if (char == '\u0000') return true
        committedQuery += char
        // 直达字符不经过 composing，直接追加到已提交查询即可。
        runSearch()
        return true
    }

    /** 来自模拟物理键盘路径的退格；行为与虚拟退格一致。 */
    fun onPhysicalBackspace(): Boolean {
        if (!isActive) return false
        onBackspace()
        return true
    }

    /** ClientPreeditEvent；消费后编辑器不会收到 composing，返回是否消费。 */
    fun onPreeditChanged(text: String): Boolean {
        if (!isActive) return false
        if (preedit == text) return true
        preedit = text
        runSearch()
        return true
    }

    private fun runSearch() {
        notifyChanged()
        val query = committedQuery + preedit
        searchJob?.cancel()
        if (query.isEmpty()) {
            results = emptyList()
            notifyChanged()
            return
        }
        searchJob = scope.launch {
            val found = withContext(Dispatchers.Default) {
                runCatching { ClipboardManager.searchEntries(query) }.getOrDefault(emptyList())
            }
            if (!isActive) return@launch
            results = found.take(MAX_RESULTS)
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        onStateChanged?.invoke()
    }
}
