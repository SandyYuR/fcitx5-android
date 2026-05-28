/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.textclassifier.TextClassifier
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import splitties.dimensions.dp
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent

class LogAdapter : RecyclerView.Adapter<LogAdapter.Holder>() {
    private data class LogEntry(val raw: String, val styled: CharSequence)

    private val allEntries = ArrayList<LogEntry>()
    private val visibleEntries = ArrayList<LogEntry>()

    private var filterQuery = ""

    inner class Holder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    private fun matches(entry: LogEntry) =
        filterQuery.isEmpty() || entry.raw.contains(filterQuery, ignoreCase = true)

    fun append(raw: String, line: CharSequence) {
        val entry = LogEntry(raw, line)
        allEntries.add(entry)
        if (matches(entry)) {
            val size = visibleEntries.size
            visibleEntries.add(entry)
            notifyItemInserted(size)
        }
    }

    fun clear() {
        val size = visibleEntries.size
        allEntries.clear()
        visibleEntries.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun setFilterQuery(query: String) {
        val normalized = query.trim()
        if (filterQuery == normalized) {
            return
        }
        filterQuery = normalized
        visibleEntries.clear()
        if (filterQuery.isEmpty()) {
            visibleEntries.addAll(allEntries)
        } else {
            allEntries.forEach { entry ->
                if (matches(entry)) {
                    visibleEntries.add(entry)
                }
            }
        }
        notifyDataSetChanged()
    }

    fun currentFilterQuery() = filterQuery

    fun fullLogString() = visibleEntries.joinToString("\n") { it.raw }

    override fun getItemCount() = visibleEntries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        parent.textView {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setTextClassifier(TextClassifier.NO_OP)
            }
            layoutParams = MarginLayoutParams(wrapContent, wrapContent).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.textView.text = visibleEntries[position].styled
    }
}
