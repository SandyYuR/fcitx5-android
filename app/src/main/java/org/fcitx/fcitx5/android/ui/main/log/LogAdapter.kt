/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R

class LogAdapter(private val context: Context) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    // 存储原始字符串和格式化的 CharSequence
    private data class LogEntry(val raw: String, val line: CharSequence)

    private val allEntries = mutableListOf<LogEntry>()
    private val visibleEntries = mutableListOf<LogEntry>()
    private val expandedItems = mutableSetOf<Int>()

    private var filterQuery: String = ""
    private var levelFilter: Set<Char> = setOf('V', 'D', 'I', 'W', 'E', 'F')
    private var contentFilter: String = "" // 内容过滤（如 "rime"）

    private val highlightColor = ContextCompat.getColor(context, R.color.log_search_highlight)

    // 级别颜色按 level 缓存，避免每个条目重复解析主题属性
    private val levelColors = HashMap<Char?, Int>()

    // 斑马纹覆盖色：只算一次，避免每次 bind 都解析主题背景色
    private val stripeColor: Int = run {
        val background = context.logColorOrDefault(
            android.R.attr.colorBackground,
            ContextCompat.getColor(context, android.R.color.transparent)
        )
        val luminance = (Color.red(background) * 0.299 +
            Color.green(background) * 0.587 +
            Color.blue(background) * 0.114) / 255.0
        if (luminance > 0.5) Color.argb(10, 0, 0, 0) else Color.argb(10, 255, 255, 255)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val colorBar: View = view.findViewById(R.id.colorBar)
        val levelIcon: ImageView = view.findViewById(R.id.levelIcon)
        val textView: TextView = view.findViewById(R.id.logText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_log_entry, parent, false)
        return ViewHolder(view)
    }

    private fun levelColorOf(level: Char?): Int = levelColors.getOrPut(level) {
        context.logLevelColor(logLevelStyle(level))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = visibleEntries[position]
        val level = logLevelOf(entry.raw)
        val style = logLevelStyle(level)
        val color = levelColorOf(level)

        // 级别色同时落在色条、图标和正文上：任何单一元素被主题或布局吃掉时仍能辨认级别
        holder.colorBar.setBackgroundColor(color)
        holder.levelIcon.setImageResource(style.iconRes)
        ImageViewCompat.setImageTintList(holder.levelIcon, ColorStateList.valueOf(color))

        // 明暗交替背景（类似 Foxy）
        holder.itemView.setBackgroundColor(if (position % 2 == 0) Color.TRANSPARENT else stripeColor)

        holder.textView.setTextColor(color)
        holder.textView.text = decorateEntry(entry, level, color)

        // 展开/收起逻辑
        val isExpanded = expandedItems.contains(position)
        holder.textView.maxLines = if (isExpanded) Int.MAX_VALUE else 2

        // 点击切换展开状态
        holder.itemView.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) {
                if (expandedItems.contains(adapterPosition)) {
                    expandedItems.remove(adapterPosition)
                } else {
                    expandedItems.add(adapterPosition)
                }
                notifyItemChanged(adapterPosition)
            }
        }
    }

    /**
     * 生成带级别色的正文。保留 [LogEntry.line] 已有的 Span（例如 LogView 传入的整行着色），
     * 再叠加搜索高亮；有级别的行重新按级别着色并把级别字母加粗，色觉差异之外还能靠字形识别。
     */
    private fun decorateEntry(entry: LogEntry, level: Char?, color: Int): CharSequence {
        val builder = SpannableStringBuilder(entry.line)
        if (builder.isEmpty()) return builder

        if (level != null) {
            builder.setSpan(
                ForegroundColorSpan(color),
                0,
                builder.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            logLevelRegex.find(entry.line)?.groups?.get(1)?.range?.let { range ->
                if (range.last < builder.length) {
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD),
                        range.first,
                        range.last + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        if (filterQuery.isNotEmpty()) {
            applyHighlight(builder, filterQuery)
        }
        return builder
    }

    override fun getItemCount(): Int = visibleEntries.size

    // 旧接口：接受两个参数
    fun append(raw: String, line: CharSequence) {
        appendEntries(listOf(LogEntry(raw, line)))
    }

    /**
     * 批量追加，只发一次 notifyItemRangeInserted。
     *
     * 打开日志页要灌入整个持久历史（可能几十万行），逐条 notify 会把主线程压死；
     * 调用方按块喂进来即可，块与块之间由调用方 yield。
     */
    fun appendAll(entries: List<Pair<String, CharSequence>>) {
        if (entries.isEmpty()) return
        appendEntries(entries.map { LogEntry(it.first, it.second) })
    }

    private fun appendEntries(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        val firstInserted = visibleEntries.size
        var inserted = 0
        for (entry in entries) {
            allEntries.add(entry)
            if (matches(entry)) {
                visibleEntries.add(entry)
                inserted++
            }
        }
        if (inserted > 0) {
            notifyItemRangeInserted(firstInserted, inserted)
        }
    }

    fun clear() {
        allEntries.clear()
        visibleEntries.clear()
        expandedItems.clear()
        notifyDataSetChanged()
    }

    // 旧接口：方法形式
    fun setFilterQuery(query: String) {
        val normalized = query.trim()
        if (normalized == filterQuery) return
        filterQuery = normalized
        updateVisibleEntries()
    }

    fun currentFilterQuery() = filterQuery

    fun setLevelFilter(levels: Set<Char>) {
        if (levels == levelFilter) return
        levelFilter = levels
        updateVisibleEntries()
    }

    fun setContentFilter(filter: String) {
        if (filter == contentFilter) return
        contentFilter = filter
        updateVisibleEntries()
    }

    fun currentLevelFilter() = levelFilter

    /**
     * 导出用的行快照。只允许主线程取，导出流程在 UI 线程冻结快照后再交给后台写文件；
     * 否则可能在遍历集合时被 logcat 回调并发修改。
     */
    fun snapshotLines(): List<String> {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Log snapshots must be captured on the main thread"
        }
        return visibleEntries.map { it.raw }
    }

    // 旧接口：返回 Pair<Int, Int> (总数, 可见数)
    fun getFilterStatus(): Pair<Int, Int> {
        return Pair(allEntries.size, visibleEntries.size)
    }

    private fun matches(entry: LogEntry): Boolean {
        // 级别过滤
        val level = logLevelOf(entry.raw)
        if (level != null && level !in levelFilter) {
            return false
        }

        // 内容过滤（如 Rime 引擎日志）
        if (contentFilter.isNotEmpty() && !entry.raw.contains(contentFilter, ignoreCase = true)) {
            return false
        }

        // 搜索过滤
        if (filterQuery.isNotEmpty() && !entry.raw.contains(filterQuery, ignoreCase = true)) {
            return false
        }

        return true
    }

    private fun updateVisibleEntries() {
        expandedItems.clear()
        visibleEntries.clear()
        visibleEntries.addAll(allEntries.filter { matches(it) })
        notifyDataSetChanged()
    }

    private fun applyHighlight(builder: SpannableStringBuilder, query: String) {
        val text = builder.toString()
        var startIndex = 0
        while (true) {
            val index = text.indexOf(query, startIndex, ignoreCase = true)
            if (index == -1) break
            builder.setSpan(
                BackgroundColorSpan(highlightColor),
                index,
                index + query.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            startIndex = index + query.length
        }
    }
}
