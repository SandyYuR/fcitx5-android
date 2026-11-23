/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.use
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

    private val levelRegex = Regex("""^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+([VDIWEF])\s""")
    private val highlightColor = Color.parseColor("#80FFFF00")

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

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = visibleEntries[position]
        val level = levelRegex.find(entry.raw)?.groupValues?.get(1)?.firstOrNull() ?: 'I'
        
        // 设置级别颜色和图标
        val (colorAttr, iconRes) = when (level) {
            'V' -> R.attr.colorLogVerbose to R.drawable.ic_log_verbose
            'D' -> R.attr.colorLogDebug to R.drawable.ic_log_debug
            'I' -> R.attr.colorLogInfo to R.drawable.ic_log_info
            'W' -> R.attr.colorLogWarning to R.drawable.ic_log_warning
            'E' -> R.attr.colorLogError to R.drawable.ic_log_error
            'F' -> R.attr.colorLogFatal to R.drawable.ic_log_fatal
            else -> R.attr.colorLogInfo to R.drawable.ic_log_info
        }
        
        context.theme.obtainStyledAttributes(intArrayOf(colorAttr)).use { ta ->
            val color = ta.getColor(0, 0)
            holder.colorBar.setBackgroundColor(color)
            holder.levelIcon.setImageResource(iconRes)
            ImageViewCompat.setImageTintList(
                holder.levelIcon,
                android.content.res.ColorStateList.valueOf(color)
            )
        }
        
        // 明暗交替背景（类似 Foxy）
        val backgroundColor = if (position % 2 == 0) {
            android.graphics.Color.TRANSPARENT
        } else {
            // 使用半透明黑色/白色创建subtle差异
            context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).use { ta ->
                val bgColor = ta.getColor(0, android.graphics.Color.WHITE)
                // 根据背景亮度选择覆盖色
                val luminance = (android.graphics.Color.red(bgColor) * 0.299 +
                                 android.graphics.Color.green(bgColor) * 0.587 +
                                 android.graphics.Color.blue(bgColor) * 0.114) / 255.0
                if (luminance > 0.5) {
                    // 亮色主题：添加淡灰色
                    android.graphics.Color.argb(10, 0, 0, 0)
                } else {
                    // 暗色主题：添加淡白色
                    android.graphics.Color.argb(10, 255, 255, 255)
                }
            }
        }
        holder.itemView.setBackgroundColor(backgroundColor)
        
        // 设置文本（带高亮）
        val displayText = if (filterQuery.isNotEmpty()) {
            highlightMatches(entry.line.toString(), filterQuery)
        } else {
            entry.line
        }
        holder.textView.text = displayText
        
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

    override fun getItemCount(): Int = visibleEntries.size

    // 旧接口：接受两个参数
    fun append(raw: String, line: CharSequence) {
        val entry = LogEntry(raw, line)
        allEntries.add(entry)
        if (matches(entry)) {
            visibleEntries.add(entry)
            notifyItemInserted(visibleEntries.size - 1)
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

    fun snapshotLines(): List<String> = visibleEntries.map { it.raw }

    // 旧接口：返回 Pair<Int, Int> (总数, 可见数)
    fun getFilterStatus(): Pair<Int, Int> {
        return Pair(allEntries.size, visibleEntries.size)
    }

    private fun matches(entry: LogEntry): Boolean {
        // 级别过滤
        val level = levelRegex.find(entry.raw)?.groupValues?.get(1)?.firstOrNull()
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

    private fun highlightMatches(text: String, query: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder(text)
        var startIndex = 0
        while (true) {
            val index = text.indexOf(query, startIndex, ignoreCase = true)
            if (index == -1) break
            builder.setSpan(
                BackgroundColorSpan(highlightColor),
                index,
                index + query.length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            startIndex = index + query.length
        }
        return builder
    }
}