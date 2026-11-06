/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.UserDataRestoreRuntime
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.utils.settingsColor

/** Recovery can style resources, but must never read preferences being replaced by the transaction. */
internal fun restoreUiContext(context: Context): Context = ContextThemeWrapper(context, R.style.Theme_FcitxSettings_System)

/** Minimal isolated process: no preferences, clipboard database or native engine is initialized. */
class UserDataRestoreActivity : ComponentActivity() {
    private val materialContext by lazy { restoreUiContext(this) }
    private val ui = MainScope()
    private var report = ""
    private var working = true
    private val restoreBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = Unit
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, restoreBackCallback)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val themed = materialContext
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        setContentView(FrameLayout(themed).apply {
            setBackgroundColor(themed.settingsColor(com.google.android.material.R.attr.colorSurfaceContainer))
            addView(MaterialCardView(themed).apply {
                radius = dp(20).toFloat(); cardElevation = 0f; strokeWidth = 0
                setCardBackgroundColor(themed.settingsColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
                addView(LinearLayout(themed).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(24), dp(32), dp(24), dp(32))
                    addView(CircularProgressIndicator(themed).apply { isIndeterminate = true },
                        LinearLayout.LayoutParams(dp(48), dp(48)))
                    addView(MaterialTextView(themed).apply {
                        setText(R.string.loading)
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                        setTextColor(themed.settingsColor(com.google.android.material.R.attr.colorOnSurface))
                        gravity = Gravity.CENTER
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
                })
            }, FrameLayout.LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(48), dp(420)),
                -2, Gravity.CENTER))
        })
        val token = intent.getStringExtra("restore_token")
        val pid = intent.getIntExtra("restore_main_pid", -1)
        val application = applicationContext
        val key = token ?: (intent.getStringExtra("recovery_run") ?: java.util.UUID.randomUUID().toString().also {
            intent.putExtra("recovery_run", it)
        })
        val operation = synchronized(companionLock) {
            if (active != null && (activeToken == key || active?.isActive == true)) checkNotNull(active)
            else {
                check(active?.isActive != true) { "A restore operation is already running" }
                background.async {
                    if (token != null) UserDataRestoreRuntime.execute(application, token, pid)
                    else try { UserDataRestoreRuntime.retryRecovery(application) }
                    catch (e: Exception) { UserDataRestoreRuntime.Outcome(false, e.toString()) }
                }.also { active = it; activeToken = key }
            }
        }
        ui.launch {
            val result = try { operation.await() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { UserDataRestoreRuntime.Outcome(false, e.toString()) }
            working = false
            restoreBackCallback.isEnabled = false
            report = listOfNotNull(intent.getStringExtra("restore_error"), result.message).joinToString("\n\n").take(64000)
            if (result.success) {
                MaterialAlertDialogBuilder(materialContext).setTitle(R.string.restore_data_ready)
                    .setMessage(report).setCancelable(false).setPositiveButton(android.R.string.ok) { _, _ -> restart() }.show()
            } else showRecoveryError()
        }
    }
    private fun showRecoveryError() {
        MaterialAlertDialogBuilder(materialContext).setTitle(R.string.restore_recovery_title)
            .setMessage(getString(R.string.restore_recovery_explanation) + "\n\n" + report)
            .setPositiveButton(R.string.restore_retry) { _, _ ->
                startActivity(Intent(this, UserDataRestoreActivity::class.java).putExtra("restore_error", report)); finish()
            }.setNeutralButton(R.string.restore_save_diagnostics) { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "rime-restore-recovery.txt")
                }, 100)
            }.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }.setCancelable(false).show()
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            ui.launch {
                try {
                    withContext(Dispatchers.IO) {
                        (contentResolver.openOutputStream(uri) ?: throw java.io.IOException("Cannot open diagnostics document"))
                            .use { it.write(report.toByteArray()) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { report += "\n\n${e}" }
                showRecoveryError()
            }
        } else if (requestCode == 100) showRecoveryError()
    }
    private fun restart() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
    override fun onDestroy() { ui.cancel(); super.onDestroy() }
    companion object {
        private val companionLock = Any()
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var activeToken: String? = null
        private var active: Deferred<UserDataRestoreRuntime.Outcome>? = null
    }
}
