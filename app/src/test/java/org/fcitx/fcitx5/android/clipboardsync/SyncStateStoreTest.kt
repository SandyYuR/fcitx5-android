/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.clipboardsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8: clipboard-sync state must not grow without bound.
 *
 * The state used to be written into the app's default SharedPreferences, whose XML is parsed in
 * full by every process using PreferenceManager — the IME included. Payload length is now capped
 * so one huge clipboard entry cannot inflate the persisted state (and with it the IME's memory).
 */
class SyncStateStoreTest {

    @Test
    fun ordinaryClipboardTextIsPersistable() {
        assertTrue(SyncStateStore.isPersistable(""))
        assertTrue(SyncStateStore.isPersistable("hello"))
        assertTrue(SyncStateStore.isPersistable("x".repeat(1024)))
    }

    @Test
    fun contentAtTheLimitIsPersistable() {
        val atLimit = "x".repeat(SyncStateStore.MAX_PERSISTED_CONTENT_LENGTH)
        assertTrue(SyncStateStore.isPersistable(atLimit))
    }

    @Test
    fun oversizedContentIsNotPersisted() {
        val overLimit = "x".repeat(SyncStateStore.MAX_PERSISTED_CONTENT_LENGTH + 1)
        assertFalse(SyncStateStore.isPersistable(overLimit))
        // A pasted document or a base64 image easily reaches megabytes.
        assertFalse(SyncStateStore.isPersistable("x".repeat(4 * 1024 * 1024)))
    }

    @Test
    fun limitIsLargeEnoughForRealTextClipboards() {
        // Guards against tightening the bound to something that would break normal use.
        assertTrue(
            "limit should cover a long text clipboard",
            SyncStateStore.MAX_PERSISTED_CONTENT_LENGTH >= 8 * 1024
        )
    }
}
