/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.fcitx.fcitx5.android.utils.Logcat
import splitties.views.bottomPadding
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.verticalLayoutManager

class LogView @JvmOverloads constructor(context: Context, attributeSet: AttributeSet? = null) :
    FrameLayout(context, attributeSet) {

    private var logcat: Logcat? = null
    private var logcatCollectionJob: Job? = null
    var onEntriesChanged: (() -> Unit)? = null
    private var changePending = false
    private val changeNotifier = Runnable {
        changePending = false
        onEntriesChanged?.invoke()
    }

    private val logAdapter = LogAdapter(context)

    private val rv = recyclerView {
        adapter = logAdapter
        layoutManager = verticalLayoutManager()
    }

    init {
        add(rv, lParams(matchParent, matchParent))
    }

    override fun onDetachedFromWindow() {
        logcat?.shutdownLogFlow()
        removeCallbacks(changeNotifier)
        changePending = false
        super.onDetachedFromWindow()
    }

    fun append(content: String) {
        val textColor = context.logDefaultTextColor()
        logAdapter.append(content, buildSpannedString { color(textColor) { append(content) } })
        notifyEntriesChanged()
    }

    /**
     * 批量追加历史行。逐条 append 会把每条都变成一次 notifyItemInserted，
     * 历史量大时主线程直接卡死；这里整块只发一次插入通知。
     */
    fun appendAll(lines: List<String>) {
        if (lines.isEmpty()) return
        val defaultColor = context.logDefaultTextColor()
        logAdapter.appendAll(lines.map { line ->
            val level = logLevelOf(line)
            val textColor = if (level == null) defaultColor else context.logLevelColor(logLevelStyle(level))
            line to buildSpannedString { color(textColor) { append(line) } }
        })
        notifyEntriesChanged()
    }

    /**
     * 接上实时 logcat，并从这个 reader 续上订阅。
     *
     * [since] 是 epoch 毫秒起点：列表里已有到那一刻为止的行，reader 从它之后开始，
     * 既不重放 logd 积压（同一事件会第二次出现，且旧行被排到新行之后），
     * 也不漏掉列表载入之后才写下的日志。
     */
    fun setLogcat(logcat: Logcat, since: Long? = null) {
        // 取消之前的收集
        logcatCollectionJob?.cancel()

        this.logcat = logcat
        logcat.initLogFlow(since)
        logcatCollectionJob = logcat.logFlow.onEach { line ->
            val level = logLevelOf(line)
            val textColor = if (level == null) {
                context.logDefaultTextColor()
            } else {
                context.logLevelColor(logLevelStyle(level))
            }
            logAdapter.append(line, buildSpannedString { color(textColor) { append(line) } })
            notifyEntriesChanged()
        }.launchIn(findViewTreeLifecycleOwner()!!.lifecycleScope)
    }

    fun snapshotLines(): List<String> = logAdapter.snapshotLines()

    fun clear() {
        logAdapter.clear()
        notifyEntriesChanged()
    }

    fun setFilterQuery(query: String) {
        logAdapter.setFilterQuery(query)
        notifyEntriesChanged()
    }

    fun currentFilterQuery() = logAdapter.currentFilterQuery()

    fun setLevelFilter(levels: Set<Char>) {
        logAdapter.setLevelFilter(levels)
        notifyEntriesChanged()
    }

    fun setContentFilter(filter: String) {
        logAdapter.setContentFilter(filter)
        notifyEntriesChanged()
    }

    fun currentLevelFilter() = logAdapter.currentLevelFilter()

    fun getFilterStatus(): Pair<Int, Int> = logAdapter.getFilterStatus()

    fun setBottomPadding(padding: Int) {
        rv.clipToPadding = false
        rv.bottomPadding = padding
    }

    private fun notifyEntriesChanged() {
        if (onEntriesChanged == null || changePending) return
        changePending = true
        post(changeNotifier)
    }
}
