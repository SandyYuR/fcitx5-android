package org.fcitx.fcitx5.android.clipboardsync.ui

import android.os.Bundle
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import com.google.android.material.appbar.MaterialToolbar as Toolbar
import org.fcitx.fcitx5.android.R

class ClipboardSyncSettingsActivity : SettingsUiActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clipboard_sync_settings)

        findViewById<Toolbar>(R.id.toolbar).also {
            setSupportActionBar(it)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsActivity.SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            finish()
        }
        return true
    }
}
