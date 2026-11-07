/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.keyboardtest

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuItemCompat
import androidx.core.view.MenuProvider
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.utils.settingsColor

internal object KeyboardTestMenu {
    fun add(menu: Menu, activity: SettingsUiActivity) {
        if (menu.findItem(R.id.keyboard_test_entry) != null) return
        menu.add(Menu.NONE, R.id.keyboard_test_entry, 100, R.string.keyboard_test_title).apply {
            icon = activity.getDrawable(R.drawable.ic_baseline_keyboard_24)?.mutate()?.apply {
                setTint(activity.settingsColor(com.google.android.material.R.attr.colorOnSurface))
            }
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            MenuItemCompat.setContentDescription(this, activity.getString(R.string.keyboard_test_title))
            MenuItemCompat.setTooltipText(this, activity.getString(R.string.keyboard_test_title))
            setOnMenuItemClickListener { activity.showKeyboardTest(); true }
        }
    }

    fun attach(toolbar: Toolbar, activity: SettingsUiActivity) {
        // Page-owned Toolbar providers survive another provider rebuilding its menu.
        toolbar.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) = add(menu, activity)
            override fun onMenuItemSelected(menuItem: MenuItem) = false
        })
    }
}
