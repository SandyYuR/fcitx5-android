/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.UserDataRestoreRuntime
import org.fcitx.fcitx5.android.ui.main.MainActivity

/** Minimal isolated process: no preferences, clipboard database or native engine is initialized. */
class UserDataRestoreActivity : Activity() {
    private val ui = MainScope()
    private var report = ""
    private var working = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(ProgressBar(context))
            addView(TextView(context).apply { setText(R.string.loading) })
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
            report = listOfNotNull(intent.getStringExtra("restore_error"), result.message).joinToString("\n\n").take(64000)
            if (result.success) {
                AlertDialog.Builder(this@UserDataRestoreActivity).setTitle(R.string.restore_data_ready)
                    .setMessage(report).setCancelable(false).setPositiveButton(android.R.string.ok) { _, _ -> restart() }.show()
            } else showRecoveryError()
        }
    }
    private fun showRecoveryError() {
        AlertDialog.Builder(this).setTitle(R.string.restore_recovery_title)
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
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!working) super.onBackPressed() }
    override fun onDestroy() { ui.cancel(); super.onDestroy() }
    companion object {
        private val companionLock = Any()
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var activeToken: String? = null
        private var active: Deferred<UserDataRestoreRuntime.Outcome>? = null
    }
}
