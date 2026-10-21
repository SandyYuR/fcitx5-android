/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.im

import com.google.android.material.textfield.TextInputEditText
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import com.google.android.material.appbar.MaterialToolbar as Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import java.io.File

/** Imports into a new profile. Existing directories remain available in the directory manager. */
class RimeImportActivity : SettingsUiActivity() {
    private val connection by lazy { FcitxDaemon.connect(javaClass.name) }
    private lateinit var status: TextView
    private lateinit var choose: MaterialButton
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            choose.isEnabled = false
            status.setText(R.string.direct_rime_importing)
            val folder = File(cacheDir, "rime-import").apply { mkdirs() }
            val archive = File.createTempFile("profile-", ".zip", folder)
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { input ->
                        archive.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytes = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= 512L * 1024 * 1024) { getString(R.string.direct_rime_archive_too_large) }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                connection.runOnReady {
                    setAddonSubConfig("rime", "import-profile", RawConfig(arrayOf(RawConfig("Archive", archive.absolutePath))))
                }
                status.setText(R.string.direct_rime_imported)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { status.text = getString(R.string.direct_rime_import_failed, e.message.orEmpty()) }
            finally { archive.delete(); choose.isEnabled = true }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = Toolbar(this).apply {
            id = R.id.toolbar
            setTitle(R.string.direct_rime_import)
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply { setText(R.string.direct_rime_import_description); textSize = 16f })
        choose = MaterialButton(this).apply {
            setText(R.string.direct_rime_choose_zip)
            setOnClickListener { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
        }
        content.addView(choose)
        status = TextView(this).apply { textSize = 15f; setPadding(0, padding, 0, padding) }
        content.addView(status)
        content.addView(TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.direct_rime_test_input)
            addView(TextInputEditText(context).apply {
                id = R.id.direct_rime_test_editor
                minLines = 3
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }, LinearLayout.LayoutParams(-1, -2))
        })
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
        setSupportActionBar(toolbar)
    }
    override fun onDestroy() {
        super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
