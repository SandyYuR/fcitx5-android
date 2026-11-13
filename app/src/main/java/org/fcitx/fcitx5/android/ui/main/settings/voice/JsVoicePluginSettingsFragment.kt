/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.voice

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceField
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceFields
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceHostPermissions
import org.fcitx.fcitx5.android.input.voice.js.JsVoicePluginMeta
import org.fcitx.fcitx5.android.input.voice.js.JsVoicePluginStore
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceProviders
import org.fcitx.fcitx5.android.input.voice.js.JsVoiceSettingsStore
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment

/**
 * 按插件声明渲染设置项。
 *
 * 三个约束决定了这里的写法：
 * 1. 所有偏好都 `isPersistent = false`——普通值走 [JsVoiceSettingsStore]，密钥走 AndroidKeyStore
 *    加密后再落盘；若交给 Preference 框架默认持久化，密钥就会以明文躺在 SharedPreferences 里。
 * 2. `visibleWhen` 在每次改值后重算，而不是只在建页时算一次，否则改「模式」后另一半字段不出现。
 * 3. 声明来自脚本本身（[JsVoiceProviders.describe]），页面不重复写一份默认值。
 */
class JsVoicePluginSettingsFragment : PaddingPreferenceFragment() {

    private var pluginId: String = JsVoiceProviders.QWEN_ASR_ID
    private var loaded = false
    private val secretStates = mutableMapOf<String, JsVoiceSettingsStore.SecretState>()
    private var refreshOverview: (() -> Unit)? = null

    private val replaceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (view != null) load()
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/javascript")) { uri ->
        if (uri != null) {
            val resolver = requireContext().contentResolver
            val spec = JsVoiceProviders.specOf(pluginId) ?: return@registerForActivityResult
            viewLifecycleOwner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val script = JsVoiceProviders.readScript(spec)
                        check(script.isNotEmpty()) { "Plugin source is empty" }
                        resolver.openOutputStream(uri, "wt")?.use { it.write(script.toByteArray(Charsets.UTF_8)) }
                            ?: error("Cannot open destination")
                    }
                }
                if (!isAdded) return@launch
                toast(result.fold({ getString(R.string.js_plugin_export_success) },
                    { getString(R.string.js_plugin_source_failed, it.message.orEmpty()) }))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pluginId = arguments?.getString(ARG_PLUGIN_ID) ?: JsVoiceProviders.QWEN_ASR_ID
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!loaded) {
            loaded = true
            showPlaceholder(getString(R.string.js_voice_settings_loading))
            load()
        }
    }

    override fun onDestroyView() {
        loaded = false
        refreshOverview = null
        super.onDestroyView()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fields = JsVoiceProviders.describe(pluginId)
                    val states = fields.filter { it.isSecret }.associate { field ->
                        field.key to JsVoiceSettingsStore.secretState(pluginId, field.key)
                    }
                    fields to states
                }
            }
            if (!isAdded) return@launch
            result.onSuccess { (fields, states) ->
                secretStates.clear()
                secretStates.putAll(states)
                render(fields)
            }
                .onFailure {
                    showPlaceholder(getString(R.string.js_voice_settings_failed, it.message.orEmpty()))
                    appendSourceEntries()
                    appendRemoveEntry()
                }
        }
    }

    private fun showPlaceholder(text: String) {
        val screen = preferenceScreen ?: return
        screen.removeAll()
        screen.addPreference(Preference(requireContext()).apply {
            key = KEY_PLACEHOLDER
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            title = text
        })
    }

    private fun render(fields: List<JsVoiceField>) {
        refreshOverview = null
        val screen = preferenceScreen ?: return
        screen.removeAll()
        if (fields.isEmpty()) {
            showPlaceholder(getString(R.string.js_voice_settings_empty))
            appendHostPermissionEntry()
            appendSourceEntries()
            appendRemoveEntry()
            return
        }
        val values = LinkedHashMap(
            JsVoiceFields.currentValues(fields, JsVoiceSettingsStore.values(pluginId))
        )
        val overview = Preference(requireContext()).apply {
            isPersistent = false
            isSelectable = false
            isIconSpaceReserved = false
            title = JsVoiceProviders.specOf(pluginId)?.label
        }.also(screen::addPreference)
        fun section(titleId: Int) = PreferenceCategory(requireContext()).apply {
            setTitle(titleId)
            isIconSpaceReserved = false
        }.also(screen::addPreference)
        val parameters = section(R.string.ui_plugin_settings)
        val credentials = section(R.string.ui_plugin_credentials)
        refreshOverview = {
            val visibleSecrets = fields.filter { it.isSecret && JsVoiceFields.isVisible(it, values) }
            overview.summary = if (visibleSecrets.isEmpty()) null else getString(R.string.ui_plugin_secret_summary,
                visibleSecrets.count { secretStates[it.key] == JsVoiceSettingsStore.SecretState.READY }, visibleSecrets.size)
            parameters.isVisible = (0 until parameters.preferenceCount).any { parameters.getPreference(it).isVisible }
            credentials.isVisible = (0 until credentials.preferenceCount).any { credentials.getPreference(it).isVisible }
        }
        val rendered = mutableListOf<Pair<JsVoiceField, Preference>>()
        var order = 0
        fields.forEach { field ->
            val preference = createPreference(field, values) {
                JsVoiceFields.defaultsAfterChange(fields, field.key, values).forEach { (key, value) ->
                    values[key] = value
                    JsVoiceSettingsStore.setValue(pluginId, key, value)
                    rendered.firstOrNull { it.first.key == key }?.let { (dependent, target) ->
                        when (target) {
                            is EditTextPreference -> target.text = value
                            is ListPreference -> target.value = value
                            is SwitchPreferenceCompat -> target.isChecked = value == "true"
                        }
                        target.summary = if (dependent.kind == JsVoiceField.KIND_SWITCH) {
                            dependent.description
                        } else describe(dependent, value)
                    }
                }
                refreshVisibility(rendered, values)
            }
            preference.isIconSpaceReserved = false
            preference.isSingleLineTitle = false
            preference.order = order++
            (if (field.isSecret) credentials else parameters).addPreference(preference)
            rendered += field to preference
        }
        refreshVisibility(rendered, values)
        appendHostPermissionEntry()
        appendSourceEntries()
        appendRemoveEntry()
    }

    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    private fun appendSourceEntries() {
        val spec = JsVoiceProviders.specOf(pluginId) ?: return
        val section = managementSection("ui_management", R.string.ui_plugin_manage, 10_000)
        fun action(key: String, titleId: Int, summary: String, block: () -> Unit) {
            section.addPreference(Preference(requireContext()).apply {
                this.key = key
                isPersistent = false
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setTitle(titleId)
                this.summary = summary
                setOnPreferenceClickListener { block(); true }
            })
        }
        section.addPreference(Preference(requireContext()).apply {
            key = "js_plugin_source"
            isPersistent = false
            isSelectable = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            title = getString(when {
                spec.overridesBuiltIn -> R.string.js_plugin_source_custom
                spec.builtIn -> R.string.js_plugin_source_builtin
                else -> R.string.js_plugin_source_imported
            })
            summary = "${spec.version} · ${spec.originName}"
        })
        action("js_plugin_export", R.string.js_plugin_export, getString(R.string.js_plugin_export_summary)) {
            exportLauncher.launch(pluginId.removePrefix(JsVoicePluginMeta.PROVIDER_ID_PREFIX) + ".js")
        }
        action("js_plugin_replace", R.string.js_plugin_replace, getString(R.string.js_plugin_replace_summary)) {
            replaceLauncher.launch(JsVoicePluginsActivity.intent(requireContext(), pluginId))
        }
        if (spec.overridesBuiltIn) {
            action("js_plugin_restore", R.string.js_plugin_restore, getString(R.string.js_plugin_restore_summary)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.js_plugin_restore)
                    .setMessage(R.string.js_plugin_restore_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.js_plugin_restore) { _, _ -> restoreBuiltIn() }
                    .show()
            }
        }
    }

    private fun restoreBuiltIn() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(JsVoicePluginStore.restoreBuiltIn(pluginId.removePrefix(JsVoicePluginMeta.PROVIDER_ID_PREFIX))) {
                        "Cannot restore bundled plugin"
                    }
                    JsVoiceProviders.reload()
                }
            }
            if (!isAdded) return@launch
            result.onSuccess {
                toast(getString(R.string.js_plugin_restored))
                load()
            }.onFailure { toast(getString(R.string.js_plugin_source_failed, it.message.orEmpty())) }
        }
    }

    private fun managementSection(key: String, title: Int, order: Int): PreferenceCategory {
        return preferenceScreen.findPreference<PreferenceCategory>(key) ?: PreferenceCategory(requireContext()).apply {
            this.key = key
            setTitle(title)
            this.order = order
            isIconSpaceReserved = false
        }.also(preferenceScreen::addPreference)
    }

    private fun appendHostPermissionEntry() {
        val appSpec = JsVoiceProviders.specOf(pluginId)
        if (appSpec?.apps?.isNotEmpty() == true) {
            managementSection("ui_permissions", R.string.ui_plugin_permissions, 9_000).addPreference(Preference(requireContext()).apply {
                key = "js_app_revoke"
                isPersistent = false
                isIconSpaceReserved = false
                order = 8_999
                setTitle(R.string.js_app_revoke)
                setSummary(R.string.js_app_revoke_summary)
                setOnPreferenceClickListener {
                    org.fcitx.fcitx5.android.input.voice.js.JsAppPermissions.revoke(pluginId)
                    JsVoiceHostPermissions.revoke(pluginId)
                    Toast.makeText(requireContext(), R.string.ui_plugin_revoked, Toast.LENGTH_SHORT).show()
                    true
                }
            })
            // Apps authorize each actual endpoint when it is first used.
            if (!appSpec.hasVoice) return
        }
        val spec = JsVoiceProviders.specOf(pluginId) ?: return
        val preference = Preference(requireContext()).apply {
            key = "js_voice_network_permission"
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            order = 9_000
            title = getString(R.string.js_voice_network_entry)
        }
        fun refresh() {
            preference.summary = getString(
                if (JsVoiceHostPermissions.isApproved(spec.id, spec.hosts)) {
                    R.string.js_voice_network_granted
                } else R.string.js_voice_network_pending,
                spec.hosts.sorted().joinToString("\n"),
            )
        }
        refresh()
        preference.setOnPreferenceClickListener {
            JsVoiceHostPermissionDialog.build(requireContext(), spec, ::refresh).show()
            true
        }
        managementSection("ui_permissions", R.string.ui_plugin_permissions, 9_000).addPreference(preference)
    }

    /**
     * 已导入插件在页面底部带一条「移除」：移除是插件级动作，放在插件自己的设置页里才有上下文，
     * 不用在列表里为每行再挤一个按钮。内置插件不显示这条——它们随 APK 走，删不掉。
     */
    private fun appendRemoveEntry() {
        val screen = preferenceScreen ?: return
        val spec = JsVoiceProviders.specOf(pluginId) ?: return
        if (spec.builtIn || spec.overridesBuiltIn) return
        if (screen.findPreference<Preference>(KEY_REMOVE) != null) return
        managementSection("ui_management", R.string.ui_plugin_manage, 10_000).addPreference(Preference(requireContext()).apply {
            key = KEY_REMOVE
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            order = 10_000
            title = getString(R.string.js_voice_plugins_delete_entry)
            summary = getString(R.string.js_voice_plugins_delete_entry_summary, spec.originName)
            setOnPreferenceClickListener {
                confirmRemove(spec.id, spec.label)
                true
            }
        })
    }

    private fun confirmRemove(pluginId: String, label: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.js_voice_plugins_delete_title)
            .setMessage(getString(R.string.js_voice_plugins_delete_message, label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.js_voice_plugins_delete_confirm) { _, _ -> remove(pluginId, label) }
            .show()
    }

    private fun remove(pluginId: String, label: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(JsVoicePluginStore.delete(pluginId.removePrefix(JsVoicePluginMeta.PROVIDER_ID_PREFIX))) {
                        "Cannot remove plugin"
                    }
                    JsVoiceProviders.reload()
                }
            }
            if (!isAdded) return@launch
            if (result.isFailure) {
                toast(getString(R.string.js_plugin_source_failed, result.exceptionOrNull()?.message.orEmpty()))
                return@launch
            }
            // 插件没了，首选语音里那一项就成了永远识别不了的死选项，这里顺手清掉。
            val preferred = AppPrefs.getInstance().keyboard.preferredVoiceInput
            val preferredWasRemoved = preferred.getValue() == pluginId
            if (preferredWasRemoved) preferred.setValue("")
            Toast.makeText(
                requireContext(),
                if (preferredWasRemoved) {
                    getString(R.string.js_voice_plugins_deleted, label) + "\n" +
                        getString(R.string.js_voice_plugins_preferred_reset)
                } else {
                    getString(R.string.js_voice_plugins_deleted, label)
                },
                Toast.LENGTH_LONG
            ).show()
            requireActivity().finish()
        }
    }

    private fun refreshVisibility(
        rendered: List<Pair<JsVoiceField, Preference>>,
        values: Map<String, String>,
    ) {
        rendered.forEach { (field, preference) ->
            preference.isVisible = JsVoiceFields.isVisible(field, values)
        }
        refreshOverview?.invoke()
    }

    private fun createPreference(
        field: JsVoiceField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
    ): Preference = when (field.kind) {
        JsVoiceField.KIND_SECRET -> secretPreference(field)
        JsVoiceField.KIND_SWITCH -> switchPreference(field, values, onChanged)
        JsVoiceField.KIND_SELECT -> if (field.allowCustom) {
            customSelectPreference(field, values, onChanged)
        } else {
            selectPreference(field, values, onChanged)
        }
        JsVoiceField.KIND_NUMBER -> textPreference(field, values, onChanged, InputType.TYPE_CLASS_NUMBER)
        else -> textPreference(field, values, onChanged, InputType.TYPE_CLASS_TEXT)
    }

    private fun textPreference(
        field: JsVoiceField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
        inputType: Int,
    ): EditTextPreference = EditTextPreference(requireContext()).apply {
        key = field.key
        title = field.label
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        setOnBindEditTextListener { it.inputType = inputType }
        text = values[field.key]
        summary = describe(field, values[field.key])
        setOnPreferenceChangeListener { preference, newValue ->
            val value = (newValue as? String).orEmpty()
            values[field.key] = value
            JsVoiceSettingsStore.setValue(pluginId, field.key, value)
            (preference as EditTextPreference).text = value
            preference.summary = describe(field, value)
            onChanged()
            true
        }
    }

    /** `allowCustom` 的选择项：预设列表放进对话框说明，实际输入自由文本。 */
    private fun customSelectPreference(
        field: JsVoiceField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
    ): EditTextPreference = EditTextPreference(requireContext()).apply {
        key = field.key
        title = field.label
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        text = values[field.key]
        summary = describe(field, values[field.key])
        dialogMessage = field.options.joinToString("\n") { it.label }
        setOnPreferenceChangeListener { preference, newValue ->
            val value = (newValue as? String).orEmpty()
            values[field.key] = value
            JsVoiceSettingsStore.setValue(pluginId, field.key, value)
            (preference as EditTextPreference).text = value
            preference.summary = describe(field, value)
            onChanged()
            true
        }
    }

    private fun selectPreference(
        field: JsVoiceField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
    ): ListPreference = ListPreference(requireContext()).apply {
        key = field.key
        title = field.label
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        entries = field.options.map { it.label }.toTypedArray()
        entryValues = field.options.map { it.value }.toTypedArray()
        // 存值可能来自旧版本或手写配置，不在选项里就退回第一项，避免列表显示空白；
        // 退回值必须写回 values，否则 visibleWhen 仍按那个无效值判断。
        val initial = values[field.key]?.takeIf { stored -> field.options.any { it.value == stored } }
            ?: field.options.firstOrNull()?.value.orEmpty()
        values[field.key] = initial
        value = initial
        summary = describe(field, initial)
        setOnPreferenceChangeListener { preference, newValue ->
            val value = (newValue as? String).orEmpty()
            values[field.key] = value
            JsVoiceSettingsStore.setValue(pluginId, field.key, value)
            (preference as ListPreference).value = value
            preference.summary = describe(field, value)
            onChanged()
            true
        }
    }

    private fun switchPreference(
        field: JsVoiceField,
        values: MutableMap<String, String>,
        onChanged: () -> Unit,
    ): SwitchPreferenceCompat = SwitchPreferenceCompat(requireContext()).apply {
        key = field.key
        title = field.label
        isPersistent = false
        isIconSpaceReserved = false
        isSingleLineTitle = false
        isChecked = values[field.key] == "true"
        summary = field.description
        setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            values[field.key] = enabled.toString()
            JsVoiceSettingsStore.setValue(pluginId, field.key, enabled.toString())
            onChanged()
            true
        }
    }

    /**
     * 密钥项：从不回填，摘要区分可用、未设置与需要重新输入。KeyStore 操作在 IO 线程执行。
     *
     * 清空输入框即清除密钥；返回值固定 false，避免 EditTextPreference 把明文留在控件里。
     */
    private fun secretPreference(field: JsVoiceField): EditTextPreference =
        EditTextPreference(requireContext()).apply {
            key = field.key
            title = field.label
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
            text = ""
            summary = secretSummary(field)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setOnPreferenceChangeListener { preference, newValue ->
                val value = (newValue as? String).orEmpty()
                preference.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch {
                    val (saved, state) = withContext(Dispatchers.IO) {
                        JsVoiceSettingsStore.setSecret(pluginId, field.key, value.ifBlank { null }) to
                            JsVoiceSettingsStore.secretState(pluginId, field.key)
                    }
                    secretStates[field.key] = state
                    preference.isEnabled = true
                    preference.summary = secretSummary(field)
                    refreshOverview?.invoke()
                    if (!saved) Toast.makeText(requireContext(), R.string.js_voice_secret_save_failed, Toast.LENGTH_LONG).show()
                }
                false
            }
        }

    private fun secretSummary(field: JsVoiceField): String {
        val state = getString(when (secretStates[field.key] ?: JsVoiceSettingsStore.SecretState.MISSING) {
            JsVoiceSettingsStore.SecretState.READY -> R.string.js_voice_secret_set
            JsVoiceSettingsStore.SecretState.MISSING -> R.string.js_voice_secret_unset
            JsVoiceSettingsStore.SecretState.UNAVAILABLE -> R.string.js_voice_secret_unavailable
        })
        return if (field.description.isBlank()) state else field.description + " · " + state
    }

    private fun describe(field: JsVoiceField, value: String?): String {
        val shown = field.options.firstOrNull { it.value == value }?.label ?: value.orEmpty()
        return if (field.description.isBlank()) shown else field.description + " · " + shown
    }

    companion object {
        private const val ARG_PLUGIN_ID = "plugin_id"
        private const val KEY_PLACEHOLDER = "js_voice_placeholder"
        private const val KEY_REMOVE = "js_voice_remove_plugin"

        fun newInstance(pluginId: String): JsVoicePluginSettingsFragment =
            JsVoicePluginSettingsFragment().apply {
                arguments = Bundle().apply { putString(ARG_PLUGIN_ID, pluginId) }
            }
    }
}
