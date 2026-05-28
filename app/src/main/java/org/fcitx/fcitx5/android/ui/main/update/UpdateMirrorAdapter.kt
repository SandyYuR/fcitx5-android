/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R

class UpdateMirrorAdapter(
    private val onClick: (MirrorRule) -> Unit
) : RecyclerView.Adapter<UpdateMirrorAdapter.VH>() {
    private val items = mutableListOf<MirrorRule>()

    fun submitList(list: List<MirrorRule>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_update_mirror, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.mirrorTitle)
        private val summary: TextView = view.findViewById(R.id.mirrorSummary)

        fun bind(item: MirrorRule) {
            title.text = item.name
            summary.text = "${item.pattern} -> ${item.replacement}"
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
