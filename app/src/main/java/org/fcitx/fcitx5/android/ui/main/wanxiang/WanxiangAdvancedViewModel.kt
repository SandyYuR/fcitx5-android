/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.*
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.util.UUID

internal class WanxiangAdvancedViewModel(application: Application) : AndroidViewModel(application) {
    data class Row(val key: String, val path: String, val value: String, val type: WanxiangYaml.Type,
        val help: String = "", val common: WanxiangCommonSettings.Key? = null, val reset: Boolean = false)
    data class State(val busy: Boolean = true, val saving: Boolean = false, val error: String? = null, val saved: Boolean = false,
        val files: List<String> = emptyList(), val file: String = COMMON, val groups: List<String> = emptyList(),
        val group: String = "", val rows: List<Row> = emptyList(), val dirty: Int = 0, val stale: Boolean = false,
        val direct: Boolean = false, val forcedDirect: Boolean = false, val compiled: Boolean = true, val target: WanxiangTarget? = null)
    @Serializable data class Pending(val file: String, val path: String, val type: String, val value: String,
        val common: String? = null, val editor: WanxiangValueEditor.State? = null)
    @Serializable data class Draft(val session: WanxiangAdvancedSession.Draft, val file: String, val group: String, val pending: Pending? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    var session: WanxiangAdvancedSession? = null
        private set
    var pending: Pending? = null
    private val connectionName = "WanxiangAdvanced-${UUID.randomUUID()}"
    private val connection by lazy { FcitxDaemon.connect(connectionName) }
    private val context get() = getApplication<Application>()
    private var initialized = false
    private var selectedFile = COMMON
    private var selectedGroup = ""

    fun initialize(restored: String?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            try {
                val raw = connection.runOnReady { getAddonSubConfig("rime", "profile-manager") }
                val target = withContext(Dispatchers.IO) { WanxiangTarget.from(context, raw) }
                val files = withContext(Dispatchers.IO) { WanxiangAdvancedFiles.files(File(target.path)) }
                mutable.value = mutable.value.copy(files = files, target = target)
                if (restored != null) {
                    val draft = Json.decodeFromString<Draft>(restored)
                    session = WanxiangAdvancedSession.restore(draft.session)
                    selectedFile = draft.file; selectedGroup = draft.group; pending = draft.pending
                    try {
                        require(session!!.target == target) { context.getString(org.fcitx.fcitx5.android.R.string.wanxiang_target_changed) }
                        withContext(Dispatchers.IO) { WanxiangAdvancedFiles.verify(File(target.path), session!!.plan().expected) }
                        // Saved state can capture a file selection before its snapshot arrives.
                        // Keep restored documents and edits, then finish loading the selection.
                        loadSelection()
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { refresh(error = error.stackTraceToString(), stale = true) }
                } else {
                    session = WanxiangAdvancedSession(target, false)
                    selectedFile = if ("default.yaml" in files && files.any { it in MAIN_FILES }) COMMON else files.firstOrNull().orEmpty()
                    require(selectedFile.isNotEmpty()) { context.getString(org.fcitx.fcitx5.android.R.string.wanxiang_advanced_empty) }
                    loadSelection()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.value = mutable.value.copy(busy = false, error = error.stackTraceToString()) }
        }
    }

    private suspend fun load(file: String) {
        if (file in session!!.documents) return
        val raw = connection.runOnReady { getAddonSubConfig("rime", "wanxiang-settings/$file") }
        session!!.add(Json.decodeFromString<WanxiangAdvancedFiles.Snapshot>(raw.findByName("Snapshot")!!.value))
    }
    private suspend fun loadSelection() {
        if (selectedFile == COMMON) {
            require("default.yaml" in mutable.value.files)
            mutable.value.files.filter { it == "default.yaml" || it in MAIN_FILES || it in PRODUCERS }.forEach { load(it) }
        } else load(selectedFile)
        if ("wanxiang_algebra.yaml" in mutable.value.files) load("wanxiang_algebra.yaml")
        refresh()
    }

    fun select(file: String, group: String? = null) {
        if (mutable.value.busy) return
        selectedFile = file
        if (group != null) { selectedGroup = group; refresh(); return }
        selectedGroup = ""
        mutable.value = mutable.value.copy(busy = true, error = null, saved = false)
        viewModelScope.launch {
            try { loadSelection() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { refresh(error = error.stackTraceToString(), stale = session?.dirty == true) }
        }
    }
    fun mode(direct: Boolean) {
        val old = session ?: return
        if (mutable.value.busy || old.dirty || direct == old.direct) return
        session = WanxiangAdvancedSession(old.target, direct).apply { old.documents.values.forEach { add(it.snapshot) } }
        refresh()
    }

    fun node(row: Row): org.yaml.snakeyaml.nodes.Node {
        val s = session!!
        return if (row.common != null) WanxiangCommonSettings(s).get(row.common) else s.value(selectedFile, row.path) ?: s.yaml.string("")
    }
    fun edit(row: Row, yaml: String) {
        val s = session!!
        val node = s.yaml.parse(yaml)
        require(row.type == WanxiangYaml.Type.YAML || s.yaml.type(node) == row.type) { "设置值类型与原类型不一致" }
        if (row.common != null) WanxiangCommonSettings(s).set(row.common, node) else s.change(selectedFile, row.path, node)
        pending = null
        refresh()
    }
    fun editPending(yaml: String) {
        val item = pending ?: return
        val common = item.common?.let(WanxiangCommonSettings.Key::valueOf)
        selectedFile = item.file
        if (common == null) selectedGroup = item.path.substringBefore('/')
        edit(Row(item.path, item.path, item.value, WanxiangYaml.Type.valueOf(item.type), common = common), yaml)
    }
    fun reset(row: Row) {
        try {
            session!!.reset(selectedFile, row.path)
            pending = null
            refresh()
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { refresh(error = error.stackTraceToString()) }
    }
    fun importSwitches() {
        val common = WanxiangCommonSettings(session!!)
        common.set(WanxiangCommonSettings.Key.REMEMBER_SWITCHES, common.importSwitches())
        refresh()
    }

    fun reload() {
        if (mutable.value.busy) return
        val direct = session?.direct ?: false
        pending = null
        mutable.value = mutable.value.copy(busy = true, error = null, saved = false, stale = false)
        viewModelScope.launch {
            try {
                val raw = connection.runOnReady { getAddonSubConfig("rime", "profile-manager") }
                val target = withContext(Dispatchers.IO) { WanxiangTarget.from(context, raw) }
                val files = withContext(Dispatchers.IO) { WanxiangAdvancedFiles.files(File(target.path)) }
                session = WanxiangAdvancedSession(target, direct)
                mutable.value = mutable.value.copy(files = files, target = target)
                if (selectedFile == COMMON && !("default.yaml" in files && files.any { it in MAIN_FILES }) || selectedFile != COMMON && selectedFile !in files)
                    selectedFile = files.firstOrNull().orEmpty()
                require(selectedFile.isNotBlank()) { context.getString(org.fcitx.fcitx5.android.R.string.wanxiang_advanced_empty) }
                loadSelection()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { refresh(error = error.stackTraceToString()) }
        }
    }

    fun save() {
        val draft = session ?: return
        if (!draft.dirty || mutable.value.busy || mutable.value.stale) return
        val changes = try { draft.changes() } catch (error: Exception) { refresh(error = error.stackTraceToString()); return }
        if (changes.isEmpty()) { reload(); return }
        val plan = draft.plan()
        mutable.value = mutable.value.copy(busy = true, saving = true, error = null)
        viewModelScope.launch {
            try {
                // A separate connection keeps the engine alive if the page is closed during save.
                withContext(NonCancellable + Dispatchers.IO) {
                    val name = "WanxiangSettingsSave-${UUID.randomUUID()}"
                    val saveConnection = FcitxDaemon.connect(name)
                    var job: File? = null
                    try {
                        val id = WanxiangAdvancedFiles.stage(File(context.noBackupFilesDir, "wanxiang-settings"), plan, changes)
                        job = File(context.noBackupFilesDir, "wanxiang-settings/$id")
                        saveConnection.runOnReady { setAddonSubConfig("rime", "wanxiang-settings-save", RawConfig(arrayOf(RawConfig("RequestId", id.toString())))) }
                    } finally {
                        job?.let { FileUtil.removeFile(it) }
                        FcitxDaemon.disconnect(name)
                    }
                }
                session = WanxiangAdvancedSession(draft.target, draft.direct)
                mutable.value = mutable.value.copy(busy = true, saving = false)
                loadSelection()
                mutable.value = mutable.value.copy(saved = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { refresh(error = error.stackTraceToString(), stale = error is WanxiangEditConflict || error.message.orEmpty().contains("当前 Rime 工作目录")) }
        }
    }

    fun exportDraft(): String? = session?.let { Json.encodeToString(Draft(it.draft(), selectedFile, selectedGroup, pending)) }
    fun repairDeployment() {
        val target = mutable.value.target ?: return
        if (mutable.value.busy) return
        pending = null
        mutable.value = mutable.value.copy(busy = true, saving = true, error = null)
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    val name = "WanxiangSettingsRepair-${UUID.randomUUID()}"
                    val repairConnection = FcitxDaemon.connect(name)
                    try {
                        repairConnection.runOnReady { setAddonSubConfig("rime", "wanxiang-settings-repair", RawConfig(arrayOf(
                            RawConfig("Profile", target.profile), RawConfig("Path", target.path)))) }
                    } finally { FcitxDaemon.disconnect(name) }
                }
                session = WanxiangAdvancedSession(target, session?.direct ?: false)
                mutable.value = mutable.value.copy(stale = false)
                loadSelection()
                mutable.value = mutable.value.copy(saved = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { refresh(error = error.stackTraceToString()) }
        }
    }
    private fun refresh(error: String? = null, stale: Boolean = mutable.value.stale) {
        val s = session ?: run { mutable.value = mutable.value.copy(busy = false, saving = false, error = error); return }
        val all = if (selectedFile == COMMON && s.mainFiles.isNotEmpty() && "default.yaml" in s.documents) {
            val common = WanxiangCommonSettings(s)
            WanxiangCommonSettings.Key.entries.map { key ->
                val value = common.get(key)
                Row(key.name, key.name, s.yaml.dump(value), s.yaml.type(value), help = common.help(key), common = key)
            }
        } else s.documents[selectedFile]?.let { doc ->
            s.fields(selectedFile).map { field -> Row(field.path, field.path, field.value, field.type, field.help,
                reset = s.edits[selectedFile to field.path]?.reset == true) }
        }.orEmpty()
        val groups = if (selectedFile == COMMON) emptyList() else all.map { it.path.substringBefore('/') }.distinct()
        if (selectedGroup !in groups) selectedGroup = groups.firstOrNull().orEmpty()
        val forced = selectedFile != COMMON && WanxiangAdvancedFiles.patchFile(selectedFile) == null
        mutable.value = mutable.value.copy(busy = false, saving = false, error = error, saved = false,
            file = selectedFile, group = selectedGroup, groups = groups,
            rows = if (selectedFile == COMMON) all else all.filter { it.path.substringBefore('/') == selectedGroup },
            dirty = s.edits.size, direct = s.direct, forcedDirect = forced, stale = stale,
            compiled = forced || if (selectedFile == COMMON) s.documents.filterKeys { it == "default.yaml" || it in MAIN_FILES }.values.all { it.snapshot.effective != null }
                else s.documents[selectedFile]?.snapshot?.effective != null)
    }

    override fun onCleared() { FcitxDaemon.disconnect(connectionName); super.onCleared() }
    companion object {
        const val COMMON = "@common"
        val MAIN_FILES = setOf("wanxiang.schema.yaml", "wanxiang_pro.schema.yaml", "wanxiang_lite.schema.yaml", "wanxiang_t9.schema.yaml", "wanxiang_t9i.schema.yaml")
        val PRODUCERS = setOf("wanxiang_phrase.schema.yaml", "wanxiang_abbrev.schema.yaml")
    }
}
