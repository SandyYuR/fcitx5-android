/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.flow.SharedFlow

/**
 * API of fcitx that hides lifecycle stuffs from [Fcitx]
 *
 * Functions can be safely used in any coroutine,
 * as the underlying operation is always dispatched in fcitx thread.
 */
interface FcitxAPI {

    enum class AddonDep {
        Required,
        Optional
    }

    /**
     * Subscribe this flow to receive event sent from fcitx
     */
    val eventFlow: SharedFlow<FcitxEvent<*>>

    /** Synchronous invalidation prevents queued results reaching a different editor. */
    fun invalidateInputSession(): Long = 0
    val inputSession: Long get() = 0
    val candidateActionContext: CandidateActionContext get() = CandidateActionContext(inputSession, 0)
    val acceptsInput: Boolean get() = true
    suspend fun startInputSession(generation: Long, program: String) {}

    val isReady: Boolean

    val inputMethodEntryCached: InputMethodEntry

    val statusAreaActionsCached: Array<Action>

    val clientPreeditCached: FormattedText

    val inputPanelCached: FcitxEvent.InputPanelEvent.Data

    /** A visible preedit frame has presented this transient hint; stale IDs are ignored. */
    suspend fun statusHintPresented(hintId: Long, frameUptimeMillis: Long) {}

    fun setLogRule(verbose: Boolean)

    fun getAddonReverseDependencies(addon: String): List<Pair<String, AddonDep>>

    fun translate(str: String, domain: String = "fcitx5"): String

    suspend fun save()

    /**
     * Save the input method profile and non-Rime addon state.
     *
     * Settings activity uses this so leaving settings does not trigger Rime's
     * user-data synchronization. Full [save] remains available for shutdown
     * and export paths that explicitly need to persist every addon.
     */
    suspend fun saveNonRimeState()

    suspend fun reloadConfig()

    suspend fun sendKey(key: String, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(c: Char, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: Int, states: UInt = 0u, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun sendKey(sym: KeySym, states: KeyStates, code: Int = 0, up: Boolean = false, timestamp: Int = -1)

    suspend fun select(idx: Int): Boolean
    suspend fun selectGlobal(idx: Int): Boolean = select(idx)
    suspend fun triggerGlobalCandidateAction(idx: Int, actionIdx: Int) = triggerCandidateAction(idx, actionIdx)
    suspend fun isEmpty(): Boolean
    suspend fun reset()
    suspend fun moveCursor(position: Int)

    suspend fun availableIme(): Array<InputMethodEntry>
    suspend fun enabledIme(): Array<InputMethodEntry>

    suspend fun setEnabledIme(array: Array<String>)

    suspend fun toggleIme()
    suspend fun activateIme(ime: String)
    suspend fun enumerateIme(forward: Boolean = true)

    suspend fun currentIme(): InputMethodEntry

    suspend fun getGlobalConfig(): RawConfig

    suspend fun setGlobalConfig(config: RawConfig)

    suspend fun getAddonConfig(addon: String): RawConfig

    suspend fun setAddonConfig(addon: String, config: RawConfig)

    suspend fun getAddonSubConfig(addon: String, path: String): RawConfig

    suspend fun setAddonSubConfig(addon: String, path: String, config: RawConfig = RawConfig())

    suspend fun getImConfig(key: String): RawConfig

    suspend fun setImConfig(key: String, config: RawConfig)

    suspend fun addons(): Array<AddonInfo>
    suspend fun setAddonState(name: Array<String>, state: BooleanArray)

    suspend fun focus(focus: Boolean = true)
    suspend fun focusOutIn()
    suspend fun activate(uid: Int, pkgName: String)
    suspend fun deactivate(uid: Int)
    suspend fun setCapFlags(flags: CapabilityFlags)

    suspend fun statusArea(): Array<Action>

    suspend fun activateAction(id: Int)

    suspend fun getCandidates(offset: Int, limit: Int): Array<CandidateWord>

    suspend fun getCandidateActions(idx: Int): Array<CandidateAction>
    suspend fun triggerCandidateAction(idx: Int, actionIdx: Int)

    suspend fun triggerCandidateActionIfCurrent(
        idx: Int, actionIdx: Int, global: Boolean, expected: CandidateActionContext
    ): Boolean {
        if (idx < 0 || candidateActionContext != expected) return false
        if (global) triggerGlobalCandidateAction(idx, actionIdx) else triggerCandidateAction(idx, actionIdx)
        return true
    }

    suspend fun activateSchemaActionIfCurrent(id: Int, expectedInputSession: Long): Boolean {
        if (inputSession != expectedInputSession) return false
        activateAction(id)
        return true
    }

    suspend fun setCandidatePagingMode(mode: Int)
    suspend fun offsetCandidatePage(delta: Int)

    suspend fun triggerCandidateListTabAction(id: Int)

}
