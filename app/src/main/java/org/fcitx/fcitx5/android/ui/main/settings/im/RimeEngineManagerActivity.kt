/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.im

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.engine.*
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.fcitx.fcitx5.android.ui.common.OperationStatusView
import org.fcitx.fcitx5.android.ui.common.SettingsUiActivity
import org.fcitx.fcitx5.android.ui.common.WrappingActionRow
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorUi
import org.fcitx.fcitx5.android.utils.settingsColor
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Manages the built-in engine, imported single-file engines and installed provider APKs. */
class RimeEngineManagerActivity : SettingsUiActivity() {
    private val repository by lazy { RimeEngineStore.repository(this) }
    private val connection by lazy { FcitxDaemon.connect(javaClass.name) }
    private lateinit var body: LinearLayout
    private lateinit var status: OperationStatusView
    private var screen: Screen? = null
    private var refreshing = false
    private var pendingImport: File? = null

    private data class Screen(
        val state: RimeEngineState,
        val directories: RimeDataDirectoryState,
        val plugins: List<RimePluginEngineCandidate>
    )

    private val enginePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) inspectImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(20)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            id = R.id.toolbar
            setTitle(R.string.rime_engine_variants_title)
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            setText(R.string.rime_engine_variants_description)
            textSize = 15f
        })
        status = OperationStatusView(this).apply { show(getString(R.string.rime_engine_loading)) }
        content.addView(status, block(top = 12, bottom = 12))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(body, LinearLayout.LayoutParams(-1, -2))
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        setSupportActionBar(toolbar)
        connection
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        status.show(getString(R.string.rime_engine_loading), state = OperationStatusView.State.WORKING)
        lifecycleScope.launch {
            try {
                val directories = connection.runOnReady {
                    RimeDataDirectoryState.fromConfig(
                        getAddonSubConfig("rime", "profile-manager"),
                        File(getExternalFilesDir(null), "data")
                    )
                }
                val loaded = withContext(Dispatchers.IO) {
                    Screen(repository.state(), directories, RimeEnginePlugins.discover(this@RimeEngineManagerActivity))
                }
                screen = loaded
                render(loaded)
                val fallback = loaded.state.lastFallbackReason
                if (fallback.isNullOrBlank()) status.show(getString(R.string.rime_engine_ready))
                else status.show(getString(R.string.rime_engine_fallback_title), fallback, OperationStatusView.State.ERROR)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status.show(getString(R.string.ui_operation_failed), error.message.orEmpty(), OperationStatusView.State.ERROR)
            } finally {
                refreshing = false
            }
        }
    }

    private fun render(value: Screen) {
        body.removeAllViews()
        sectionTitle(R.string.rime_engine_builtin_section)
        val builtinProfile = if (value.state.active == RimeEngineSelection.Builtin) {
            value.directories.current
        } else value.state.builtinProfile ?: value.directories.current
        body.addView(engineCard(
            title = getString(R.string.rime_engine_builtin_name),
            details = getString(R.string.rime_engine_builtin_details, builtinProfile),
            active = value.state.active == RimeEngineSelection.Builtin,
            actions = buildList {
                if (value.state.active != RimeEngineSelection.Builtin) add(
                    action(R.string.rime_engine_switch) {
                        confirmSwitch(RimeEngineSelection.Builtin, getString(R.string.rime_engine_builtin_name))
                    }
                )
            }
        ), block(bottom = 12))

        sectionTitle(R.string.rime_engine_imported_section)
        body.addView(EditorUi.button(this, R.string.rime_engine_import_so, tonal = true) {
            enginePicker.launch(arrayOf("application/octet-stream", "application/x-sharedlib", "*/*"))
        }, block(bottom = 8))
        if (value.state.imported.isEmpty()) emptyText(R.string.rime_engine_no_imported)
        value.state.imported.forEach { record ->
            val selection = RimeEngineSelection(RimeEngineKind.IMPORTED, record.id)
            val file = runCatching { repository.importedFile(record) }.getOrNull()
            val available = file?.isFile == true
            val details = buildString {
                append(record.runtimeInfo.librimeVersion).append(" · ").append(record.staticInfo.abi)
                append("\n").append(getString(R.string.rime_engine_profile_value, record.profile))
                append("\nSHA-256 ").append(record.staticInfo.sha256.take(16)).append('…')
                if (!available) append("\n").append(getString(R.string.rime_engine_payload_missing))
            }
            body.addView(engineCard(record.displayName, details, value.state.active == selection, buildList {
                if (available && value.state.active != selection) add(action(R.string.rime_engine_switch) {
                    confirmSwitch(selection, record.displayName)
                })
                add(action(R.string.rime_engine_choose_profile) {
                    chooseProfile(record.profile) { profile -> updateImportedProfile(record, profile) }
                })
                if (value.state.active != selection) add(action(R.string.delete) { confirmDeleteImported(record) })
            }), block(bottom = 8))
        }

        sectionTitle(R.string.rime_engine_plugin_section)
        if (value.plugins.isEmpty() && value.state.plugins.isEmpty()) emptyText(R.string.rime_engine_no_plugins)
        value.plugins.forEach { candidate ->
            val authorization = value.state.plugins.firstOrNull { it.packageName == candidate.packageName }
                ?.takeIf {
                    it.certificateSha256 == candidate.certificateSha256 &&
                        it.payloadSha256 == candidate.staticInfo.sha256 &&
                        it.soname == candidate.soname
                }
            val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, candidate.packageName)
            val details = buildString {
                append(candidate.declaredLibrimeVersion ?: authorization?.runtimeInfo?.librimeVersion ?: "librime")
                candidate.channel?.let { append(" · ").append(it) }
                append(" · ").append(candidate.staticInfo.abi)
                append("\n").append(candidate.packageName)
                append("\n").append(if (authorization != null) {
                    getString(R.string.rime_engine_plugin_authorized,
                        authorization.profile)
                } else getString(R.string.rime_engine_plugin_needs_authorization))
            }
            body.addView(engineCard(candidate.displayName, details, value.state.active == selection, buildList {
                if (authorization == null) add(action(R.string.rime_engine_authorize) { beginPluginAuthorization(candidate) })
                if (authorization != null && value.state.active != selection) add(action(R.string.rime_engine_switch) {
                    confirmSwitch(selection, candidate.displayName)
                })
                if (authorization != null) add(action(R.string.rime_engine_choose_profile) {
                    chooseProfile(authorization.profile) { profile -> updatePluginProfile(authorization, profile) }
                })
                if (authorization != null && value.state.active != selection) add(action(R.string.rime_engine_revoke) {
                    confirmRevokePlugin(authorization)
                })
            }), block(bottom = 8))
        }
        value.state.plugins.filter { saved -> value.plugins.none { it.packageName == saved.packageName } }.forEach { missing ->
            val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, missing.packageName)
            body.addView(engineCard(missing.displayName,
                getString(R.string.rime_engine_plugin_missing, missing.packageName),
                value.state.active == selection,
                if (value.state.active == selection) emptyList() else listOf(action(R.string.rime_engine_revoke) {
                    confirmRevokePlugin(missing)
                })), block(bottom = 8))
        }
    }

    private fun inspectImport(uri: Uri) {
        status.show(getString(R.string.rime_engine_reading), state = OperationStatusView.State.WORKING)
        lifecycleScope.launch {
            var staged: File? = null
            try {
                val directory = File(codeCacheDir, "rime-engine-import").apply {
                    check(isDirectory || mkdirs()) { "无法创建导入暂存目录" }
                }
                val target = File.createTempFile("engine-", ".so", directory)
                staged = target
                val sourceName = displayName(uri)
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= RimeEngineElf.MAX_ENGINE_BYTES) { "引擎文件超过 96 MiB" }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    } ?: error("无法读取所选文件")
                }
                val info = withContext(Dispatchers.IO) { RimeEngineElf.inspect(target, sourceName) }
                pendingImport?.delete()
                pendingImport = target
                showProbeAuthorization(target, info, null)
                status.show(getString(R.string.rime_engine_static_valid))
            } catch (error: CancellationException) {
                staged?.delete()
                throw error
            } catch (error: Exception) {
                staged?.delete()
                status.show(getString(R.string.rime_engine_rejected), error.message.orEmpty(), OperationStatusView.State.ERROR)
            }
        }
    }

    private fun beginPluginAuthorization(candidate: RimePluginEngineCandidate) {
        showProbeAuthorization(File(candidate.libraryPath), candidate.staticInfo, candidate)
    }

    /** First consent: allow code execution only inside the disposable probe process. */
    private fun showProbeAuthorization(
        file: File,
        info: RimeEngineStaticInfo,
        plugin: RimePluginEngineCandidate?
    ) {
        val message = staticDetails(info, plugin) + "\n\n" +
            getString(R.string.rime_engine_probe_warning)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rime_engine_static_valid)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> if (plugin == null) clearPendingImport() }
            .setPositiveButton(R.string.rime_engine_probe_action) { _, _ -> probe(file, info, plugin) }
            .setOnCancelListener { if (plugin == null) clearPendingImport() }
            .show()
    }

    private fun probe(file: File, info: RimeEngineStaticInfo, plugin: RimePluginEngineCandidate?) {
        status.show(getString(R.string.rime_engine_probing), state = OperationStatusView.State.WORKING)
        lifecycleScope.launch {
            try {
                val runtime = RimeEngineProbeService.probe(this@RimeEngineManagerActivity, file, info)
                chooseProfile(
                    selected = screen?.directories?.current,
                    onCancel = { if (plugin == null) clearPendingImport() }
                ) { profile -> if (plugin == null) confirmImported(file, info, runtime, profile)
                    else confirmPlugin(plugin, runtime, profile) }
                status.show(getString(R.string.rime_engine_probe_succeeded), runtimeDetails(runtime))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (plugin == null) clearPendingImport()
                status.show(getString(R.string.rime_engine_probe_failed), error.message.orEmpty(), OperationStatusView.State.ERROR)
            }
        }
    }

    /** Second consent: the runtime API is known and the user now authorizes persistent import. */
    private fun confirmImported(
        file: File,
        info: RimeEngineStaticInfo,
        runtime: RimeEngineRuntimeInfo,
        profile: String
    ) {
        val input = TextInputEditText(this).apply {
            setText(info.sourceName.substringBeforeLast('.').ifBlank { "librime ${runtime.librimeVersion}" })
            selectAll()
            maxLines = 1
        }
        val layout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = getString(R.string.rime_engine_display_name)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(context).apply {
                text = runtimeDetails(runtime) + "\n" + getString(R.string.rime_engine_profile_value, profile) +
                    "\n\n" + getString(R.string.rime_engine_full_trust_warning)
            })
            addView(layout, block(top = 12))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rime_engine_import_confirm_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel) { _, _ -> clearPendingImport() }
            .setPositiveButton(R.string.rime_engine_authorize_import, null)
            .setOnCancelListener { clearPendingImport() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    layout.error = getString(R.string.rime_engine_name_required)
                    return@setOnClickListener
                }
                dialog.dismiss()
                installImported(file, name, info, runtime, profile)
            }
        }
        dialog.show()
    }

    private fun installImported(
        file: File,
        name: String,
        info: RimeEngineStaticInfo,
        runtime: RimeEngineRuntimeInfo,
        profile: String
    ) {
        status.show(getString(R.string.rime_engine_importing), state = OperationStatusView.State.WORKING)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(RimeEngineElf.inspect(file, info.sourceName).sha256 == info.sha256) { "引擎文件在确认后发生变化" }
                    repository.installImported(file, name, info, runtime, profile)
                }
                pendingImport = null
                file.delete()
                status.show(getString(R.string.rime_engine_imported, name), state = OperationStatusView.State.SUCCESS)
                refresh()
            } catch (error: Exception) {
                clearPendingImport()
                status.show(getString(R.string.ui_operation_failed), error.message.orEmpty(), OperationStatusView.State.ERROR)
            }
        }
    }

    private fun confirmPlugin(
        candidate: RimePluginEngineCandidate,
        runtime: RimeEngineRuntimeInfo,
        profile: String
    ) {
        val message = runtimeDetails(runtime) + "\n" +
            getString(R.string.rime_engine_profile_value, profile) + "\n\n" +
            getString(R.string.rime_engine_plugin_certificate, candidate.certificateSha256) + "\n\n" +
            getString(R.string.rime_engine_full_trust_warning)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rime_engine_authorize_title, candidate.displayName))
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rime_engine_authorize) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val verified = RimeEngineElf.inspect(File(candidate.libraryPath),
                                candidate.staticInfo.sourceName)
                            require(verified.sha256 == candidate.staticInfo.sha256) { "插件引擎在确认后发生变化" }
                            repository.authorizePlugin(PluginRimeEngineAuthorization(
                                packageName = candidate.packageName,
                                displayName = candidate.displayName,
                                providerId = candidate.providerId,
                                certificateSha256 = candidate.certificateSha256,
                                payloadSha256 = candidate.staticInfo.sha256,
                                soname = candidate.soname,
                                staticInfo = candidate.staticInfo,
                                runtimeInfo = runtime,
                                profile = profile,
                                authorizedAt = System.currentTimeMillis()
                            ))
                        }
                        status.show(getString(R.string.rime_engine_authorized, candidate.displayName),
                            state = OperationStatusView.State.SUCCESS)
                        refresh()
                    } catch (error: Exception) {
                        status.show(getString(R.string.ui_operation_failed), error.message.orEmpty(), OperationStatusView.State.ERROR)
                    }
                }
            }
            .show()
    }

    private fun confirmSwitch(selection: RimeEngineSelection, name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rime_engine_switch_title, name))
            .setMessage(R.string.rime_engine_switch_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rime_engine_switch) { _, _ ->
                repository.select(selection, screen?.directories?.current)
                stopAndRestartProcess()
            }
            .show()
    }

    private fun stopAndRestartProcess() {
        status.show(getString(R.string.rime_engine_restarting), state = OperationStatusView.State.WORKING)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { FcitxDaemon.stopFcitx() }
            delay(150)
            Process.killProcess(Process.myPid())
        }
    }

    private fun updateImportedProfile(record: ImportedRimeEngine, profile: String) {
        repository.updateImportedProfile(record.id, profile)
        val selection = RimeEngineSelection(RimeEngineKind.IMPORTED, record.id)
        if (screen?.state?.active == selection) {
            repository.reconfigureActive(selection)
            stopAndRestartProcess()
        } else refresh()
    }

    private fun updatePluginProfile(record: PluginRimeEngineAuthorization, profile: String) {
        repository.updatePluginProfile(record.packageName, profile)
        val selection = RimeEngineSelection(RimeEngineKind.PLUGIN, record.packageName)
        if (screen?.state?.active == selection) {
            repository.reconfigureActive(selection)
            stopAndRestartProcess()
        } else refresh()
    }

    private fun confirmDeleteImported(record: ImportedRimeEngine) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rime_engine_delete_title)
            .setMessage(getString(R.string.rime_engine_delete_message, record.displayName, record.profile))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.deleteImported(record.id)
                refresh()
            }.show()
    }

    private fun confirmRevokePlugin(record: PluginRimeEngineAuthorization) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rime_engine_revoke_title)
            .setMessage(getString(R.string.rime_engine_revoke_message, record.displayName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.rime_engine_revoke) { _, _ ->
                repository.revokePlugin(record.packageName)
                refresh()
            }.show()
    }

    private fun chooseProfile(
        selected: String?,
        onCancel: () -> Unit = {},
        result: (String) -> Unit
    ) {
        val directories = screen?.directories ?: return
        val entries = directories.profiles.toTypedArray()
        if (entries.isEmpty()) return
        var choice = entries.indexOf(selected).takeIf { it >= 0 } ?: entries.indexOf(directories.current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rime_engine_choose_profile)
            .setSingleChoiceItems(entries, choice) { _, which -> choice = which }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setPositiveButton(android.R.string.ok) { _, _ -> result(entries[choice]) }
            .setOnCancelListener { onCancel() }
            .show()
    }

    private fun engineCard(title: String, details: String, active: Boolean, actions: List<View>): View {
        val padding = dp(16)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply {
                text = if (active) getString(R.string.rime_engine_active_name, title) else title
                textSize = 18f
                setTextColor(context.settingsColor(if (active)
                    androidx.appcompat.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOnSurface))
            })
            addView(TextView(context).apply {
                text = details
                textSize = 13f
                setPadding(0, dp(6), 0, 0)
            })
            if (actions.isNotEmpty()) addView(WrappingActionRow(context).apply {
                actions.forEach(::addView)
            }, block(top = 8))
        }
        return EditorUi.card(this, content)
    }

    private fun action(label: Int, block: () -> Unit): View = EditorUi.button(this, label, action = block)

    private fun sectionTitle(label: Int) {
        body.addView(TextView(this).apply {
            setText(label)
            textSize = 17f
            setPadding(0, dp(12), 0, dp(8))
        })
    }

    private fun emptyText(label: Int) {
        body.addView(TextView(this).apply { setText(label); textSize = 14f }, block(bottom = 8))
    }

    private fun staticDetails(info: RimeEngineStaticInfo, plugin: RimePluginEngineCandidate?): String = buildString {
        append(getString(R.string.rime_engine_file_value, info.sourceName)).append('\n')
        append(getString(R.string.rime_engine_abi_value, info.abi, info.bits)).append('\n')
        append(getString(R.string.rime_engine_size_value,
            String.format(Locale.ROOT, "%.2f", info.size / 1024.0 / 1024.0))).append('\n')
        append("SHA-256: ").append(info.sha256).append('\n')
        append("SONAME: ").append(info.soname ?: getString(R.string.none)).append('\n')
        append("Build ID: ").append(info.buildId ?: getString(R.string.none)).append('\n')
        append(getString(R.string.rime_engine_dependencies_value,
            info.neededLibraries.joinToString().ifBlank { getString(R.string.none) }))
        plugin?.let {
            append("\n\n").append(getString(R.string.rime_engine_plugin_package, it.packageName))
            append("\n").append(getString(R.string.rime_engine_plugin_certificate, it.certificateSha256))
        }
    }

    private fun runtimeDetails(info: RimeEngineRuntimeInfo): String = buildString {
        append(getString(R.string.rime_engine_version_value, info.librimeVersion)).append('\n')
        append(getString(R.string.rime_engine_api_size_value, info.apiDataSize)).append('\n')
        append(getString(R.string.rime_engine_capabilities_value,
            info.capabilities.joinToString().ifBlank { getString(R.string.none) }))
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0) ?: "librime.so"
            else uri.lastPathSegment?.substringAfterLast('/') ?: "librime.so"
        } catch (_: Exception) {
            uri.lastPathSegment?.substringAfterLast('/') ?: "librime.so"
        } finally {
            cursor?.close()
        }
    }

    private fun clearPendingImport() {
        pendingImport?.delete()
        pendingImport = null
    }

    override fun onDestroy() {
        if (isFinishing) clearPendingImport()
        super.onDestroy()
        FcitxDaemon.disconnect(javaClass.name)
    }

    private fun block(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
