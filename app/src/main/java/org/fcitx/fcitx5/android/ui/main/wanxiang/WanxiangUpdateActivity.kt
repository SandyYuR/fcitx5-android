/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.text.format.Formatter
import android.widget.LinearLayout
import android.text.InputType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.ui.common.OperationStatusView.State
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity

class WanxiangUpdateActivity : SettingsUiActivity() {
    private val connectionName = "WanxiangUpdatePage-${java.util.UUID.randomUUID()}"
    private val connection by lazy { FcitxDaemon.connect(connectionName) }
    private lateinit var form: WanxiangUpdateForm
    private var target: WanxiangTarget? = null
    private var work: WorkInfo? = null
    private var submitting = false
    private var localError: String? = null
    private var localMessage: String? = null
    private var configurationTitle: Int? = null
    private var readingTarget = false
    private var targetReadGeneration = 0
    private var targetReadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = savedInstanceState?.getString("selection")?.let {
            runCatching { Json.decodeFromString<WanxiangSelection>(it).also { selection -> selection.packages() } }.getOrNull()
        } ?: WanxiangUpdates.selection(this)
        form = WanxiangUpdateForm(this, initial)
        form.onSelectionChanged = { selection ->
            WanxiangUpdates.preferences(this).edit().putString("selection", Json.encodeToString(selection)).apply()
        }
        form.update.setOnClickListener { startUpdate() }
        form.retryDirectory.setOnClickListener { refreshTarget() }
        form.onAdvanced = { startActivity(android.content.Intent(this, WanxiangAdvancedActivity::class.java)) }
        form.onProtection = ::editProtection
        form.onToken = ::editToken
        form.onGithubCheck = {
            configure(R.string.wanxiang_github_check) {
                val result = withContext(Dispatchers.IO) { WanxiangGithub.check(applicationContext) }
                localMessage = getString(R.string.wanxiang_github_ok, result)
            }
        }
        form.onExtra = { chooseExtras(false) }
        form.onExtraRefresh = { chooseExtras(true) }
        form.cancel.setOnClickListener {
            form.cancel.isEnabled = false
            lifecycleScope.launch {
                try { withContext(Dispatchers.IO) { WorkManager.getInstance(applicationContext).cancelUniqueWork(WanxiangUpdates.WORK).result.get() } }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { localError = error.stackTraceToString(); render() }
            }
        }
        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.wanxiang_update)
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(form, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        setSupportActionBar(toolbar)
        // ToolbarActionBar installs its own navigation listener; attach ours afterwards.
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WanxiangUpdates.observe(applicationContext).collect { list ->
                    work = list.firstOrNull { !it.state.isFinished } ?: list.lastOrNull()
                    render()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        form.setTokenConfigured(WanxiangGithub.configured(this))
        refreshTarget()
    }

    private fun refreshTarget() {
        val generation = ++targetReadGeneration
        targetReadJob?.cancel()
        target = null
        localError = null
        localMessage = null
        readingTarget = true
        render()
        targetReadJob = lifecycleScope.launch {
            try {
                val current = readTarget()
                if (generation == targetReadGeneration) target = current
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == targetReadGeneration) localError = error.stackTraceToString()
            } finally {
                if (generation == targetReadGeneration) { readingTarget = false; render() }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, ADVANCED_MENU, 0, R.string.wanxiang_advanced_open)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressedDispatcher.onBackPressed(); return true }
        if (item.itemId == ADVANCED_MENU) {
            form.onAdvanced()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(ADVANCED_MENU)?.isEnabled = !submitting && !readingTarget && work?.state?.isFinished != false
        return super.onPrepareOptionsMenu(menu)
    }

    private fun configure(title: Int, action: suspend () -> Unit) {
        if (submitting || work?.state?.isFinished == false) return
        submitting = true; localError = null; localMessage = null; configurationTitle = title; render()
        lifecycleScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { localError = error.message ?: error.javaClass.simpleName }
            finally { submitting = false; configurationTitle = null; render() }
        }
    }

    private fun inputBox(label: Int, value: String, secret: Boolean = false): Pair<TextInputLayout, TextInputEditText> {
        val input = TextInputEditText(this).apply {
            isSaveEnabled = !secret
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = if (secret) 1 else 6; maxLines = 14; setText(value)
        }
        val box = TextInputLayout(this).apply {
            hint = getString(label); addView(input)
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        return box to input
    }

    private fun editProtection() {
        val (box, input) = inputBox(R.string.wanxiang_protection_rules, form.protectionText())
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)); addView(box) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.wanxiang_protection)
            .setView(container).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.wanxiang_protection_default) { _, _ -> form.setProtection(org.fcitx.fcitx5.android.data.WanxiangProtection.DEFAULT) }
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            runCatching { form.setProtection(input.text.toString()) }.onSuccess { dialog.dismiss() }.onFailure { box.error = it.message }
        } }
        dialog.show()
    }

    private fun editToken() {
        lifecycleScope.launch {
            val value = try { withContext(Dispatchers.IO) { WanxiangGithub.token(applicationContext) } }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { "" }
            val (box, input) = inputBox(R.string.wanxiang_token, value, secret = true)
            val container = LinearLayout(this@WanxiangUpdateActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)); addView(box) }
            val dialog = MaterialAlertDialogBuilder(this@WanxiangUpdateActivity).setTitle(R.string.wanxiang_token)
                .setView(container).setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.wanxiang_token_clear) { _, _ ->
                    configure(R.string.wanxiang_token) { withContext(Dispatchers.IO) { WanxiangGithub.save(applicationContext, "") }; form.setTokenConfigured(false) }
                }.setPositiveButton(android.R.string.ok, null).create()
            dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                lifecycleScope.launch {
                    val button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    button.isEnabled = false; input.isEnabled = false
                    try {
                        val token = WanxiangGithub.normalize(input.text.toString())
                        withContext(Dispatchers.IO) { WanxiangGithub.save(applicationContext, token) }
                        form.setTokenConfigured(WanxiangGithub.configured(applicationContext)); dialog.dismiss()
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { box.error = error.message }
                    finally { button.isEnabled = true; input.isEnabled = true }
                }
            } }
            dialog.show()
        }
    }

    private fun chooseExtras(refresh: Boolean) {
        configure(R.string.wanxiang_extra_loading) {
            val catalog = withContext(Dispatchers.IO) {
                val cached = WanxiangExtraDictionaries.cached(applicationContext)
                if (refresh || cached.isEmpty()) WanxiangExtraDictionaries.refresh(applicationContext) else cached
            }
            val selected = form.selection().extraDictionaries.toMutableSet()
            MaterialAlertDialogBuilder(this).setTitle(R.string.wanxiang_extra)
                .setMultiChoiceItems(catalog.map { it.name }.toTypedArray(), catalog.map { it.id in selected }.toBooleanArray()) { _, index, checked ->
                    if (checked) selected += catalog[index].id else selected -= catalog[index].id
                }.setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> form.setExtras(catalog.filter { it.id in selected }.map { it.id }) }.show()
        }
    }

    private suspend fun readTarget(): WanxiangTarget {
        val raw = connection.runOnReady { getAddonSubConfig("rime", "profile-manager") }
        return withContext(Dispatchers.IO) { WanxiangTarget.from(applicationContext, raw) }
    }

    private fun startUpdate() {
        if (submitting || work?.state?.isFinished == false) return
        val expected = target ?: return
        val selection = form.selection()
        submitting = true
        localError = null
        localMessage = null
        render()
        lifecycleScope.launch {
            try {
                val actual = readTarget()
                target = actual
                require(actual == expected) { getString(R.string.wanxiang_target_changed) }
                WanxiangUpdates.enqueue(applicationContext, selection, actual)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { localError = error.stackTraceToString() }
            finally { submitting = false; render() }
        }
    }

    private fun render() {
        invalidateOptionsMenu()
        val active = work?.takeIf { !it.state.isFinished }
        val data = active?.progress
        val running = active?.state == WorkInfo.State.RUNNING
        val downloading = running && data?.getString(WanxiangUpdateWorker.PHASE) == "download"
        val installing = running && data?.getString(WanxiangUpdateWorker.PHASE) == "install"
        val workTarget = data?.getString(WanxiangUpdateWorker.TARGET)?.let {
            runCatching { Json.decodeFromString<WanxiangTarget>(it) }.getOrNull()
        }
        form.target.text = (if (active != null) workTarget else target)?.let { "${it.profile}\n${it.path}" }
            ?: getString(if (active != null) R.string.wanxiang_waiting_directory else R.string.wanxiang_loading_directory)
        form.setBusy(submitting || readingTarget || active != null, target != null,
            canCancel = active != null && !submitting && !installing)
        when {
            localError != null -> form.status.show(getString(R.string.wanxiang_failed), localError!!, State.ERROR)
            submitting -> form.status.show(getString(configurationTitle ?: R.string.wanxiang_preparing), state = State.WORKING)
            active != null -> {
                val title = if (running) data?.getString(WanxiangUpdateWorker.TITLE) ?: getString(R.string.wanxiang_preparing)
                    else getString(R.string.wanxiang_waiting)
                val bytes = data?.getLong(WanxiangUpdateWorker.BYTES, 0) ?: 0
                val total = data?.getLong(WanxiangUpdateWorker.TOTAL, 0) ?: 0
                val percent = if (downloading) WanxiangDownloadProgress.percent(bytes, total) else null
                val details = buildString {
                    if (running) data?.getString(WanxiangUpdateWorker.NAME)?.let(::append)
                    if (downloading) {
                        if (isNotEmpty()) append('\n')
                        if (percent != null) append(getString(R.string.wanxiang_current_file_progress,
                            java.text.NumberFormat.getPercentInstance().format(percent / 100.0))).append(" · ")
                        append(Formatter.formatFileSize(this@WanxiangUpdateActivity, bytes.coerceAtLeast(0)))
                        if (percent != null) append(" / ").append(Formatter.formatFileSize(this@WanxiangUpdateActivity, total))
                        else append(" · ").append(getString(R.string.wanxiang_size_unknown))
                        val speed = data?.getLong(WanxiangUpdateWorker.SPEED, 0) ?: 0
                        if (speed > 0) append(" · ").append(getString(R.string.wanxiang_speed, Formatter.formatFileSize(this@WanxiangUpdateActivity, speed)))
                    }
                }
                form.status.show(title, details, State.WORKING, progressPercent = percent)
            }
            localMessage != null -> form.status.show(localMessage!!, state = State.SUCCESS)
            readingTarget -> form.status.show(getString(R.string.wanxiang_loading_directory), state = State.WORKING)
            work?.state == WorkInfo.State.SUCCEEDED && work?.outputData?.getBoolean(WanxiangUpdateWorker.NO_CHANGES, false) == true ->
                form.status.show(getString(R.string.wanxiang_nothing_changed), getString(R.string.wanxiang_nothing_changed_hint))
            work?.state == WorkInfo.State.SUCCEEDED -> form.status.show(getString(R.string.wanxiang_finished),
                work?.outputData?.getString(WanxiangUpdateWorker.TARGET)?.let {
                    runCatching { Json.decodeFromString<WanxiangTarget>(it).path }.getOrNull()
                }.orEmpty(), State.SUCCESS)
            work?.state == WorkInfo.State.FAILED -> form.status.show(getString(R.string.wanxiang_failed),
                work?.outputData?.getString(WanxiangUpdateWorker.ERROR).orEmpty(), State.ERROR)
            work?.state == WorkInfo.State.CANCELLED -> form.status.show(getString(R.string.ui_operation_cancelled))
            else -> form.status.show(getString(R.string.wanxiang_ready), target?.let {
                getString(R.string.wanxiang_current_target, it.profile)
            }.orEmpty())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selection", Json.encodeToString(form.selection()))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        FcitxDaemon.disconnect(connectionName)
        super.onDestroy()
    }
    private companion object { const val ADVANCED_MENU = 0x5741 }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
