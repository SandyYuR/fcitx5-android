/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.update

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.method.LinkMovementMethod
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.R
import java.util.Locale

enum class AssetStatus {
    IDLE,
    DOWNLOADING,
    FINISHED,
    FAILED,
    CANCELED
}

data class AssetUiState(
    val asset: ReleaseAsset,
    val status: AssetStatus = AssetStatus.IDLE,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = asset.sizeBytes,
    val speedBytesPerSec: Long = 0L,
    val downloadId: Long? = null,
    val installUri: android.net.Uri? = null,
    val error: String? = null
)

class UpdateAssetAdapter(
    private val onItemClick: (AssetUiState) -> Unit,
    private val onActionClick: (AssetUiState) -> Unit
) : RecyclerView.Adapter<UpdateAssetAdapter.VH>() {
    private companion object {
        const val VIEW_TYPE_ASSET = 0
        const val VIEW_TYPE_RELEASE_NOTES = 1
    }

    private val items = mutableListOf<AssetUiState>()
    private var releaseNotes: CharSequence = ""

    fun submitList(newItems: List<AssetUiState>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setReleaseNotes(notes: CharSequence) {
        releaseNotes = notes
        notifyDataSetChanged()
    }

    fun currentList(): List<AssetUiState> = items.toList()

    override fun getItemViewType(position: Int): Int {
        return if (position < items.size) VIEW_TYPE_ASSET else VIEW_TYPE_RELEASE_NOTES
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == VIEW_TYPE_ASSET) {
            R.layout.item_update_asset
        } else {
            R.layout.item_update_release_notes
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size + if (releaseNotes.isBlank()) 0 else 1

    override fun onBindViewHolder(holder: VH, position: Int) {
        if (position < items.size) {
            holder.bindAsset(items[position])
        } else {
            holder.bindReleaseNotes(releaseNotes)
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView? = view.findViewById(R.id.assetTitle)
        private val subtitle: TextView? = view.findViewById(R.id.assetSubtitle)
        private val progress: ProgressBar? = view.findViewById(R.id.assetProgress)
        private val action: TextView? = view.findViewById(R.id.assetAction)
        private val releaseNotesText: TextView? = view.findViewById(R.id.releaseNotesText)

        fun bindAsset(item: AssetUiState) {
            val title = title ?: return
            val subtitle = subtitle ?: return
            val progress = progress ?: return
            val action = action ?: return
            title.text = item.asset.name
            subtitle.text = when (item.status) {
                AssetStatus.IDLE -> itemView.context.getString(
                    R.string.update_asset_size,
                    formatSize(item.asset.sizeBytes)
                )
                AssetStatus.DOWNLOADING -> itemView.context.getString(
                    R.string.update_asset_progress,
                    item.progressPercent,
                    formatSpeed(item.speedBytesPerSec)
                )
                AssetStatus.FINISHED -> itemView.context.getString(R.string.update_asset_finished)
                AssetStatus.FAILED -> item.error ?: itemView.context.getString(R.string.update_asset_failed)
                AssetStatus.CANCELED -> itemView.context.getString(R.string.update_asset_canceled)
            }
            progress.progress = item.progressPercent
            progress.visibility = if (item.status == AssetStatus.DOWNLOADING) View.VISIBLE else View.GONE

            val bgColor = when (item.status) {
                AssetStatus.IDLE -> android.R.color.transparent
                AssetStatus.DOWNLOADING -> R.color.update_asset_downloading_bg
                AssetStatus.FINISHED -> R.color.update_asset_finished_bg
                AssetStatus.FAILED, AssetStatus.CANCELED -> R.color.update_asset_failed_bg
            }
            itemView.setBackgroundColor(ContextCompat.getColor(itemView.context, bgColor))

            when (item.status) {
                AssetStatus.DOWNLOADING -> {
                    action.visibility = View.VISIBLE
                    action.text = "🗑"
                    action.contentDescription = itemView.context.getString(R.string.cancel)
                }
                AssetStatus.FINISHED -> {
                    action.visibility = View.VISIBLE
                    action.text = "✅"
                    action.contentDescription = itemView.context.getString(R.string.update_install_action)
                }
                else -> {
                    action.visibility = View.GONE
                }
            }

            itemView.setOnClickListener { onItemClick(item) }
            action.setOnClickListener { onActionClick(item) }
        }

        fun bindReleaseNotes(notes: CharSequence) {
            val text = releaseNotesText ?: return
            text.text = notes
            text.movementMethod = LinkMovementMethod.getInstance()
            text.linksClickable = true
            itemView.setOnClickListener(null)
        }

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            var value = bytes.toDouble()
            var i = 0
            while (value >= 1024 && i < units.lastIndex) {
                value /= 1024.0
                i++
            }
            return String.format(Locale.US, "%.1f %s", value, units[i])
        }

        private fun formatSpeed(bytesPerSec: Long): String {
            return "${formatSize(bytesPerSec)}/s"
        }
    }
}
