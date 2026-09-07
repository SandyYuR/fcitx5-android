/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * Owns the session numeric override and the temporarily forced layer.
 * The keyboard view remains responsible for applying state changes to attached views.
 */
internal class NumericLayoutOverrideController {
    internal data class Snapshot(
        val sessionKey: String?,
        val manualKey: String?,
        val forcedKey: String?,
        val manual: Boolean,
        val dismissed: Boolean
    )

    fun snapshot(): Snapshot = Snapshot(sessionKey, manualKey, forcedKey, manual, dismissed)

    fun restore(snapshot: Snapshot) {
        sessionKey = snapshot.sessionKey
        manualKey = snapshot.manualKey
        forcedKey = snapshot.forcedKey
        manual = snapshot.manual
        dismissed = snapshot.dismissed
    }

    var sessionKey: String? = null
        private set
    var manualKey: String? = null
        private set
    var forcedKey: String? = null
        private set
    var manual: Boolean = false
        private set
    var dismissed: Boolean = false
        private set

    fun beginSession(key: String?) {
        sessionKey = key
        manualKey = null
        forcedKey = key
        manual = false
        dismissed = false
    }

    fun force(key: String?) {
        forcedKey = key?.trim()?.takeIf { it.isNotEmpty() } ?: manualKey ?: sessionKey
    }

    fun activateManual(key: String): Boolean {
        if (dismissed) return false
        if (sessionKey == null) {
            manual = true
            manualKey = key
        }
        forcedKey = key
        return true
    }

    fun releaseManual(): Boolean {
        if (!manual) return false
        manual = false
        manualKey = null
        forcedKey = sessionKey
        return true
    }

    /**
     * Release the manually activated override because an explicit layer switch moved the
     * keyboard to [target].
     *
     * A latching layer switch ("layer to"/BACK, as opposed to a one-shot OSL peek) is as
     * deliberate a "leave the number pad" gesture as the "ABC"-style layout switch key or a
     * language switch. While the manual slot stayed latched, a later [force] with no layer
     * of its own fell back to it and resurrected the number pad on top of the layer the user
     * actually chose — most visibly on the in-place input restart an app performs when its
     * send button clears the editor. Re-affirming the same layer is not a departure, so the
     * override stays remembered. Only the manual slot is dropped; the session slot for
     * numeric editors legitimately survives layer switches.
     *
     * @return whether a manual override was present and cleared.
     */
    fun releaseManualOnLayerSwitch(target: String?): Boolean {
        if (manualKey == target) return false
        return releaseManual()
    }

    /**
     * Release the manually activated override because the input method changed.
     * A language / input-method switch is an explicit user move away from the
     * previously shown layout, so a manual override picked earlier in the session
     * must not be resurrected by [force] afterwards. Only the manual slot is
     * dropped; the session slot for numeric editors legitimately survives IME
     * updates. Returns whether a manual override was present and cleared.
     */
    fun releaseManualOnImeUpdate(): Boolean {
        if (!manual) return false
        manual = false
        manualKey = null
        forcedKey = sessionKey
        return true
    }

    fun dismiss() {
        sessionKey = null
        manualKey = null
        forcedKey = null
        manual = false
        dismissed = true
    }

    /**
     * Whether the manual numeric layout is the layout currently being rendered, i.e. the
     * remembered manual key has not been superseded by a layer latch. This is the
     * truth "a number pad is on screen right now", as opposed to [manualKey] merely
     * being remembered.
     */
    fun isManualNumericShowing(): Boolean = manualKey != null && forcedKey == manualKey

    fun revalidateSession(resolved: String?): Boolean {
        val current = sessionKey ?: return false
        if (resolved == current) return false
        sessionKey = resolved
        if (forcedKey == current) forcedKey = resolved
        return resolved == null
    }

    fun revalidateManual(isValid: Boolean): Boolean {
        val current = manualKey ?: return false
        if (isValid) return false
        manualKey = null
        manual = false
        if (forcedKey == current) forcedKey = sessionKey
        return true
    }
}
