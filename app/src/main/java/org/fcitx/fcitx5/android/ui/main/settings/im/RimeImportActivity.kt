/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import java.io.File

/** Imports into a new profile. Existing directories remain available in the directory manager. */
class RimeImportActivity : AppCompatActivity() {
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
        if (!BuildConfig.DIRECT_RIME) { finish(); return }
        connection
        val foreground = MaterialColors.getColor(this, android.R.attr.textColorPrimary, android.graphics.Color.BLACK)
        val accent = MaterialColors.getColor(this, android.R.attr.colorAccent, 0xff008577.toInt())
        val onAccent = if (androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.WHITE, accent) >= 4.5)
            android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val padding = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = Toolbar(this).apply {
            setTitle(R.string.direct_rime_import)
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            navigationIcon?.mutate()?.setTint(foreground)
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
            backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
            setTextColor(onAccent)
            setOnClickListener { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
        }
        content.addView(choose)
        status = TextView(this).apply { textSize = 15f; setPadding(0, padding, 0, padding) }
        content.addView(status)
        content.addView(EditText(this).apply {
            id = R.id.direct_rime_test_editor
            setHint(R.string.direct_rime_test_input)
            minLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        })
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = system.top, bottom = system.bottom, left = system.left, right = system.right)
            insets
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.DIRECT_RIME) FcitxDaemon.disconnect(javaClass.name)
    }
}
