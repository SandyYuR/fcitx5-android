/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import splitties.views.dsl.core.Ui
import kotlin.math.sign

abstract class ThemeListAdapter : RecyclerView.Adapter<ThemeListAdapter.ViewHolder>() {
    class ViewHolder(val ui: Ui) : RecyclerView.ViewHolder(ui.root)

    val entries = mutableListOf<Theme>()

    private var activeIndex = -1
    private var lightIndex = -1
    private var darkIndex = -1

    private fun entryAt(position: Int) = entries.getOrNull(position - OFFSET)

    private fun positionOf(theme: Theme? = null): Int {
        if (theme == null) return -1
        return positionOf(theme.name)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (holder.ui is ThemeThumbnailUi) {
            holder.ui.cleanup()
        }
    }

    fun setThemes(themes: List<Theme>) {
        updateThemes(themes)
    }

    private fun updateThemes(themes: List<Theme>, renamedFrom: String? = null, renamedTo: String? = null) {
        fun selectedName(index: Int) = entryAt(index)?.name?.let { if (it == renamedFrom) renamedTo else it }
        val activeName = selectedName(activeIndex)
        val lightName = selectedName(lightIndex)
        val darkName = selectedName(darkIndex)
        val previous = entries.toList()
        val updated = themes.toList()
        val difference = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size + OFFSET
            override fun getNewListSize() = updated.size + OFFSET
            override fun areItemsTheSame(old: Int, new: Int): Boolean =
                if (old == 0 || new == 0) old == new else previous[old - OFFSET].name == updated[new - OFFSET].name
            override fun areContentsTheSame(old: Int, new: Int): Boolean =
                if (old == 0 || new == 0) old == new else previous[old - OFFSET] == updated[new - OFFSET]
        })
        entries.clear()
        entries.addAll(updated)
        activeIndex = positionOf(activeName)
        lightIndex = positionOf(lightName)
        darkIndex = positionOf(darkName)
        difference.dispatchUpdatesTo(this)
    }

    private fun positionOf(themeName: String?): Int {
        if (themeName == null) return -1
        val index = entries.indexOfFirst { it.name == themeName }
        return if (index < 0) -1 else index + OFFSET
    }

    fun setSelectedThemes(active: Theme, light: Theme? = null, dark: Theme? = null) {
        val oldActive = entryAt(activeIndex)
        if (oldActive != active) {
            if (activeIndex >= OFFSET) notifyItemChanged(activeIndex)
            activeIndex = positionOf(active)
            if (activeIndex >= OFFSET) notifyItemChanged(activeIndex)
        }
        val oldLight = entryAt(lightIndex)
        if (oldLight != light) {
            if (lightIndex >= OFFSET) notifyItemChanged(lightIndex)
            lightIndex = positionOf(light)
            if (lightIndex >= OFFSET) {
                notifyItemChanged(lightIndex)
            }
        }
        val oldDark = entryAt(darkIndex)
        if (oldDark != dark) {
            if (darkIndex >= OFFSET) notifyItemChanged(darkIndex)
            darkIndex = positionOf(dark)
            if (darkIndex >= OFFSET) {
                notifyItemChanged(darkIndex)
            }
        }
    }

    fun prependTheme(it: Theme) {
        updateThemes(listOf(it) + entries)
    }

    fun removeTheme(name: String) {
        updateThemes(entries.filterNot { it.name == name })
    }

    fun replaceTheme(theme: Theme) {
        replaceTheme(theme.name, theme)
    }

    fun replaceTheme(oldName: String, theme: Theme) {
        updateThemes(listOf(theme) + entries.filterNot { it.name == oldName || it.name == theme.name }, oldName, theme.name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(
            when (viewType) {
                ADD_THEME -> NewThemeEntryUi(parent.context)
                THEME -> ThemeThumbnailUi(parent.context)
                else -> throw IllegalArgumentException(INVALID_TYPE + viewType)
            }
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (val it = getItemViewType(position)) {
            ADD_THEME -> holder.ui.root.setOnClickListener { onAddNewTheme() }
            THEME -> (holder.ui as ThemeThumbnailUi).apply {
                val theme = entryAt(position)!!
                setTheme(theme)
                setChecked(
                    when (position) {
                        darkIndex -> ThemeThumbnailUi.State.DarkMode
                        lightIndex -> ThemeThumbnailUi.State.LightMode
                        activeIndex -> ThemeThumbnailUi.State.Selected
                        else -> ThemeThumbnailUi.State.Normal
                    }
                )
                val isRandom = ThemeManager.isRandomTheme(theme)
                root.setOnClickListener {
                    onSelectTheme(theme)
                }
                root.setOnLongClickListener {
                    if (theme is Theme.Custom && !isRandom) {
                        onExportTheme(theme)
                        true
                    } else if (theme is Theme.Monet) {
                        onExportTheme(theme.toCustom())
                        true
                    } else false
                }
                editButton.setOnClickListener {
                    when {
                        isRandom -> onEditRandomTheme(theme as Theme.Custom)
                        theme is Theme.Custom -> onEditTheme(theme)
                        theme is Theme.Monet -> onEditMonetTheme(theme)
                    }
                }
                exportButton.setOnClickListener {
                    when {
                        theme is Theme.Custom && !isRandom -> onExportTheme(theme)
                        theme is Theme.Monet -> onExportTheme(theme.toCustom())
                    }
                }
                setRandomAction(if (isRandom) ({ onRandomizeTheme() }) else null)
            }
            else -> throw IllegalArgumentException(INVALID_TYPE + it)
        }
    }

    override fun getItemCount() = entries.size + 1

    override fun getItemViewType(position: Int) = if (position == 0) ADD_THEME else THEME

    abstract fun onAddNewTheme()

    abstract fun onSelectTheme(theme: Theme)

    abstract fun onEditTheme(theme: Theme.Custom)

    abstract fun onEditMonetTheme(theme: Theme.Monet)

    abstract fun onEditRandomTheme(theme: Theme.Custom)

    abstract fun onRandomizeTheme()

    abstract fun onExportTheme(theme: Theme.Custom)

    companion object {
        const val OFFSET = 1

        const val ADD_THEME = 0
        const val THEME = 1

        const val INVALID_TYPE = "Invalid ItemView Type: "
    }
}
