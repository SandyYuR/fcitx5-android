/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.rimeupdate

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.RimeSchemeTask
import org.fcitx.fcitx5.android.ui.common.OperationStatusView
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangDownloadProgress
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import java.util.UUID

/** Saved task list and progress stay separate; recreation never restarts a download. */
class RimeSchemeUpdateActivity : SettingsUiActivity() {
    private val connectionName = "RimeCustomPage-${UUID.randomUUID()}"
    private val connection by lazy { FcitxDaemon.connect(connectionName) }
    private lateinit var form: RimeSchemeUpdateForm
    private var tasks = emptyList<RimeSchemeTask>()
    private var target: WanxiangTarget? = null
    private var work: WorkInfo? = null
    private var workTarget: WanxiangTarget? = null
    private var submitting = false
    private var readingTarget = false
    private var targetRead: Job? = null
    private var targetGeneration = 0
    private var localError: String? = null
    private var configError: String? = null
    private var message: String? = null
    private var editor: RimeSchemeTaskEditor? = null
    private var editDialog: AlertDialog? = null
    private var pendingFileTask: String? = null
    private val localFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val editing = editor?.takeIf { it.taskId == pendingFileTask }
        pendingFileTask = null
        if (uri != null && editing != null) lifecycleScope.launch {
            val previousSource = editing.source.text.toString()
            val saveButton = editDialog?.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton?.isEnabled = false
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val name = withContext(Dispatchers.IO) {
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }
                if (editor === editing && editing.source.text.toString() == previousSource) {
                    editing.selectedFile(uri.toString(), name ?: RimeSchemeTask.suggestedFileName(uri.toString()))
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (editor === editing) editing.showError(error.message) }
            finally { saveButton?.isEnabled = true }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingFileTask = savedInstanceState?.getString("pendingFileTask")
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.scheme_update_title); setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
        }
        form = RimeSchemeUpdateForm(this).apply {
            onAdd = { if (configError == null) edit(RimeSchemeTask()) }
            onEdit = ::edit
            onSelect = { task, checked -> save(tasks.map { if (it.id == task.id) it.copy(selected = checked) else it }) }
            onRunOne = { confirmRun(listOf(it)) }
            onExecute = { confirmRun(tasks.filter { it.selected }) }
            onDelete = {
                val ids = tasks.filter { it.selected }.map { it.id }.toSet()
                MaterialAlertDialogBuilder(this@RimeSchemeUpdateActivity).setTitle(R.string.scheme_update_delete_selected)
                    .setMessage(getString(R.string.scheme_update_delete_confirm, ids.size))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> save(tasks.filterNot { it.id in ids }) }.show()
            }
            onRetry = { loadTasks(); refreshTarget() }
            onCancel = { command {
                withContext(Dispatchers.IO) { WorkManager.getInstance(applicationContext).cancelUniqueWork(RimeSchemeUpdates.WORK).result.get() }
            } }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(form, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        setSupportActionBar(toolbar)
        form.showPath(savedInstanceState?.getBoolean("showPath", false) ?: false)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RimeSchemeUpdates.observe(applicationContext).collect { list ->
                    val next = list.firstOrNull { !it.state.isFinished } ?: list.lastOrNull()
                    if (next?.id != work?.id) workTarget = null
                    work = next
                    render()
                    if (next != null) {
                        val encoded = next.progress.getString(RimeSchemeUpdateWorker.TARGET)
                            ?: next.outputData.getString(RimeSchemeUpdateWorker.TARGET)
                        workTarget = encoded?.let { runCatching { Json.decodeFromString<WanxiangTarget>(it) }.getOrNull() }
                            ?: workTarget ?: withContext(Dispatchers.IO) {
                                runCatching { RimeSchemeUpdates.request(applicationContext, next.id).target }.getOrNull()
                            }
                        render()
                    }
                }
            }
        }
        savedInstanceState?.getString("draft")?.let { encoded ->
            runCatching { Json.decodeFromString<RimeSchemeTask>(encoded) }.onSuccess { edit(it) }
        }
    }

    override fun onStart() { super.onStart(); loadTasks(); refreshTarget() }
    override fun onDestroy() { FcitxDaemon.disconnect(connectionName); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        editor?.let { outState.putString("draft", Json.encodeToString(it.draft())) }
        pendingFileTask?.let { outState.putString("pendingFileTask", it) }
        outState.putBoolean("showPath", form.target.visibility == View.VISIBLE)
        super.onSaveInstanceState(outState)
    }

    private fun loadTasks() {
        try { tasks = RimeSchemeUpdates.tasks(this); configError = null }
        catch (error: Exception) { configError = error.message ?: error.javaClass.simpleName; tasks = emptyList() }
        form.showTasks(tasks); render()
    }
    private fun refreshTarget() {
        val generation = ++targetGeneration
        targetRead?.cancel(); target = null; localError = null; readingTarget = true; render()
        targetRead = lifecycleScope.launch {
            try {
                val raw = connection.runOnReady { getAddonSubConfig("rime", "profile-manager") }
                val next = WanxiangTarget.from(this@RimeSchemeUpdateActivity, raw)
                if (generation == targetGeneration) target = next
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == targetGeneration) localError = error.message ?: error.javaClass.simpleName }
            finally { if (generation == targetGeneration) { readingTarget = false; render() } }
        }
    }
    private fun command(block: suspend () -> Unit) {
        if (submitting) return
        submitting = true; localError = null; message = null; render()
        lifecycleScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { localError = error.message ?: error.javaClass.simpleName }
            finally { submitting = false; render() }
        }
    }
    private fun save(next: List<RimeSchemeTask>) = command {
        try { withContext(Dispatchers.IO) { RimeSchemeUpdates.save(applicationContext, next) } }
        catch (error: Exception) { form.showTasks(tasks); throw error }
        tasks = next; configError = null; form.showTasks(tasks)
        message = getString(R.string.scheme_update_saved)
    }
    private fun edit(task: RimeSchemeTask) {
        if (submitting || work?.state?.isFinished == false) return
        val fields = RimeSchemeTaskEditor(this, task) { pendingFileTask = task.id; localFile.launch(arrayOf("*/*")) }
        editor = fields
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.scheme_update_edit)
            .setView(ScrollView(this).apply { addView(fields) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.scheme_update_save, null).create()
        editDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = try { fields.draft().validated() } catch (error: Exception) { fields.showError(error.message); return@setOnClickListener }
                val list = if (tasks.any { it.id == next.id }) tasks.map { if (it.id == next.id) next else it } else tasks + next
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { RimeSchemeUpdates.save(applicationContext, list) }
                        tasks = list; configError = null; form.showTasks(tasks); message = getString(R.string.scheme_update_saved)
                        dialog.dismiss(); render()
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { fields.showError(error.message) }
                    finally { dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true }
                }
            }
        }
        dialog.setOnDismissListener { if (editor === fields) { editor = null; editDialog = null } }
        dialog.show()
    }
    private fun confirmRun(chosen: List<RimeSchemeTask>) {
        val current = target ?: return
        if (chosen.isEmpty() || submitting || work?.state?.isFinished == false) return
        val snapshot = RimeSchemeRequest(current, chosen)
        MaterialAlertDialogBuilder(this).setTitle(R.string.scheme_update_title)
            .setMessage(getString(R.string.scheme_update_confirm, chosen.size, current.profile, current.path))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.scheme_update_run_one) { _, _ -> command { RimeSchemeUpdates.enqueue(applicationContext, snapshot) } }.show()
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.scheme_update_reset).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(1)?.isEnabled = !submitting && work?.state?.isFinished != false
        return super.onPrepareOptionsMenu(menu)
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != 1) return super.onOptionsItemSelected(item)
        MaterialAlertDialogBuilder(this).setTitle(R.string.scheme_update_reset).setMessage(R.string.scheme_update_reset_hint)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ -> command {
                withContext(Dispatchers.IO) { RimeSchemeUpdates.reset(applicationContext) }
                loadTasks()
            } }.show()
        return true
    }

    private fun render() {
        if (!::form.isInitialized) return
        val info = work
        val active = info?.state?.isFinished == false
        val data = info?.progress
        val phase = data?.getString(RimeSchemeUpdateWorker.PHASE)
        form.profile.text = target?.profile ?: getString(R.string.wanxiang_loading_directory)
        form.target.text = target?.path.orEmpty()
        form.setControls(submitting || active, target != null && configError == null && !readingTarget, active && !submitting && phase != "install")
        form.cancel.visibility = if (active) View.VISIBLE else View.GONE
        form.add.isEnabled = !submitting && !active && configError == null
        val error = configError ?: localError
        val bound = workTarget?.let { getString(R.string.scheme_update_job_target, it.profile, it.path) }.orEmpty()
        when {
            error != null -> form.status.show(getString(R.string.scheme_update_failed), error, OperationStatusView.State.ERROR)
            submitting -> form.status.show(getString(R.string.scheme_update_preparing), state = OperationStatusView.State.WORKING)
            active && info != null -> {
                val bytes = data?.getLong(RimeSchemeUpdateWorker.BYTES, 0) ?: 0
                val total = data?.getLong(RimeSchemeUpdateWorker.TOTAL, 0) ?: 0
                val speed = data?.getLong(RimeSchemeUpdateWorker.SPEED, 0) ?: 0
                val downloading = phase == "download" || phase == "local"
                val progress = if (downloading) WanxiangDownloadProgress.percent(bytes, total) else null
                val title = if (info.state == WorkInfo.State.ENQUEUED) getString(R.string.scheme_update_waiting)
                    else getString(RimeSchemeUpdateWorker.phaseLabel(phase))
                val name = data?.getString(RimeSchemeUpdateWorker.NAME).orEmpty()
                val count = data?.getInt(RimeSchemeUpdateWorker.COUNT, 0) ?: 0
                val index = data?.getInt(RimeSchemeUpdateWorker.INDEX, 0) ?: 0
                val amount = if (!downloading) "" else buildString {
                    append(Formatter.formatShortFileSize(this@RimeSchemeUpdateActivity, bytes))
                    if (total > 0) append(" / ").append(Formatter.formatShortFileSize(this@RimeSchemeUpdateActivity, total))
                    if (progress != null) append(" · ").append(progress).append('%')
                    if (speed > 0) append(" · ").append(Formatter.formatShortFileSize(this@RimeSchemeUpdateActivity, speed)).append("/s")
                }
                form.status.show(title, listOf(bound, if (count > 0) "$index / $count · $name" else name, amount)
                    .filter(String::isNotEmpty).joinToString("\n"), OperationStatusView.State.WORKING, progress)
            }
            readingTarget -> form.status.show(getString(R.string.wanxiang_loading_directory), state = OperationStatusView.State.WORKING)
            message != null -> form.status.show(message!!)
            info?.state == WorkInfo.State.SUCCEEDED -> form.status.show(getString(R.string.scheme_update_done),
                bound + "\n" + getString(R.string.scheme_update_result, info.outputData.getInt(RimeSchemeUpdateWorker.FILES, 0)), OperationStatusView.State.SUCCESS)
            info?.state == WorkInfo.State.FAILED -> form.status.show(getString(R.string.scheme_update_failed),
                bound + "\n" + info.outputData.getString(RimeSchemeUpdateWorker.ERROR).orEmpty(), OperationStatusView.State.ERROR)
            info?.state == WorkInfo.State.CANCELLED -> form.status.show(getString(R.string.scheme_update_cancelled), bound)
            else -> form.status.show(getString(R.string.scheme_update_ready), getString(R.string.scheme_update_scope))
        }
        invalidateOptionsMenu()
    }
}
