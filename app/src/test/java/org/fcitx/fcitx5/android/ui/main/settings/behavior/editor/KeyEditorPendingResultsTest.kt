/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.activity.result.ActivityResult
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutEditorResultStore
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.*
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeColorEditorActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class KeyEditorPendingResultsTest {
    private fun parcel(state: Bundle): Bundle {
        val data = Parcel.obtain()
        try {
            data.writeBundle(state)
            data.setDataPosition(0)
            return data.readBundle(javaClass.classLoader)!!
        } finally { data.recycle() }
    }

    @Test fun threeNestedResultsSurviveRepeatedRecreationUntilTheFormIsReady() {
        val pending = KeyEditorPendingResults()
        val steps = arrayListOf<Map<*, *>>(hashMapOf("type" to "text", "text" to "hello"))
        pending.offer(KeyEditorPendingResults.Kind.MACRO, ActivityResult(Activity.RESULT_OK, Intent()
            .putExtra(MacroEditorActivity.EXTRA_TARGET_TOKEN, "swipeDown")
            .putExtra(MacroEditorActivity.EXTRA_MACRO_RESULT, steps)))
        pending.offer(KeyEditorPendingResults.Kind.COLOR, ThemeColorEditorActivity.EditorResult("textColor", -16711936))
        pending.offer(KeyEditorPendingResults.Kind.COMPOSE, ActivityResult(Activity.RESULT_OK,
            Intent().putExtra(KeyEditorForm.EXTRA_RESULT_ACTION, KeyEditorForm.RESULT_ACTION_SAVE)
                .putExtra(KeyEditorForm.EXTRA_RESULT_KEY_DATA, KeyEditorForm.toSerializableMap(
                    mapOf("type" to "MacroKey", "label" to "Compose")))))
        var restored = pending
        repeat(3) { restored = KeyEditorPendingResults().apply { restore(parcel(Bundle().also(restored::save))) } }
        val results = restored.takeAll()
        assertEquals(KeyEditorPendingResults.Kind.entries, results.map { it.first })
        assertTrue(restored.takeAll().isEmpty())
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.Theme_FcitxSettings_Base_Light)
        controller.setup()
        try {
            val form = KeyEditorForm(controller.get(), Intent().putExtra(KeyEditorForm.EXTRA_KEY_DATA,
                KeyEditorForm.toSerializableMap(mapOf("type" to "MacroKey", "label" to "Draft"))))
            results.forEach { (kind, value) -> when (kind) {
                KeyEditorPendingResults.Kind.MACRO -> form.acceptMacroResult(value as ActivityResult)
                KeyEditorPendingResults.Kind.COLOR -> form.acceptColorResult(value as ThemeColorEditorActivity.EditorResult)
                KeyEditorPendingResults.Kind.COMPOSE -> form.acceptComposeResult(value as ActivityResult)
            } }
            val draft = form.validatedDraft(false)!!
            assertEquals("Draft", draft["label"])
            assertEquals(steps, (draft["swipeDown"] as Map<*, *>)["macro"])
            assertEquals(-16711936, draft["textColor"])
            assertEquals("Compose", (draft["composeOverride"] as Map<*, *>)["label"])
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun clearingAColorAndCancelingAnEditorStayDistinctAfterParceling() {
        val pending = KeyEditorPendingResults()
        pending.offer(KeyEditorPendingResults.Kind.COLOR, ThemeColorEditorActivity.EditorResult("textColor", null))
        pending.offer(KeyEditorPendingResults.Kind.MACRO, ActivityResult(Activity.RESULT_CANCELED, null))
        val restored = KeyEditorPendingResults().apply { restore(parcel(Bundle().also(pending::save))) }
        val results = restored.takeAll()
        assertNull((results[0].second as ThemeColorEditorActivity.EditorResult).color)
        assertEquals(Activity.RESULT_CANCELED, (results[1].second as ActivityResult).resultCode)
        assertTrue(restored.takeAll().isEmpty())
    }

    @Test fun layoutNameActivityReturnsTheCapturedRequestToken() {
        val store = LayoutEditorResultStore()
        val request = Intent()
            .putExtra(LayoutNameInputActivity.EXTRA_INITIAL_VALUE, "NewLayout")
        store.begin(LayoutEditorResultStore.Channel.LAYOUT, "original", "rime", request)
        val controller = Robolectric.buildActivity(LayoutNameInputActivity::class.java, request).setup()
        try {
            val activity = controller.get()
            activity.onOptionsItemSelected(android.widget.PopupMenu(activity, android.view.View(activity)).menu.add(0, 9101, 0, "Save"))
            store.receive(LayoutEditorResultStore.Channel.LAYOUT,
                ActivityResult(shadowOf(activity).resultCode, shadowOf(activity).resultIntent))
            val result = store.takeNext()!!
            assertEquals("original", result.target?.profile)
            assertEquals("NewLayout", result.data.getStringExtra(LayoutNameInputActivity.EXTRA_RESULT_LAYOUT_NAME))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun profileActivityReturnsTheCapturedRequestAndPreservesCopyChoice() {
        val store = LayoutEditorResultStore()
        val request = Intent().putExtra(LayoutFileProfileInputActivity.EXTRA_ACTION, LayoutFileProfileInputActivity.ACTION_CREATE)
            .putExtra(LayoutFileProfileInputActivity.EXTRA_INITIAL_PROFILE, "NewProfile")
            .putExtra(LayoutFileProfileInputActivity.EXTRA_SHOW_COPY_SWITCH, true)
            .putExtra(LayoutFileProfileInputActivity.EXTRA_COPY_CURRENT_DEFAULT, false)
        store.begin(LayoutEditorResultStore.Channel.PROFILE, "original", "", request)
        val controller = Robolectric.buildActivity(LayoutFileProfileInputActivity::class.java, request).setup()
        try {
            val activity = controller.get()
            activity.onOptionsItemSelected(android.widget.PopupMenu(activity, android.view.View(activity)).menu.add(0, 9001, 0, "Save"))
            val fallback = LayoutFileProfileInputActivity.consumePendingResultPayload()!!
            store.receive(LayoutEditorResultStore.Channel.PROFILE, ActivityResult(Activity.RESULT_OK, fallback.resultIntent))
            val result = store.takeNext()!!
            assertEquals("original", result.target?.profile)
            assertEquals("NewProfile", result.data.getStringExtra(LayoutFileProfileInputActivity.EXTRA_RESULT_PROFILE))
            assertFalse(result.data.getBooleanExtra(LayoutFileProfileInputActivity.EXTRA_RESULT_COPY_CURRENT, true))
        } finally { controller.pause().stop().destroy() }
    }
}
