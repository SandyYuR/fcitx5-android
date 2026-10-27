/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.activity.result.ActivityResult
import org.fcitx.fcitx5.android.ui.main.settings.behavior.data.LayoutEditorResultStore.Channel
import org.fcitx.fcitx5.android.ui.main.settings.behavior.dialog.KeyEditorActivity
import org.fcitx.fcitx5.android.utils.serializable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28])
class LayoutEditorResultStoreTest {
    private fun result(request: Intent, value: String = "changed") = ActivityResult(Activity.RESULT_OK,
        LayoutEditorResultStore.attachRequestToken(request, Intent()
            .putExtra(KeyEditorActivity.EXTRA_RESULT_ACTION, KeyEditorActivity.RESULT_ACTION_SAVE)
            .putExtra(KeyEditorActivity.EXTRA_ROW_INDEX, 2)
            .putExtra(KeyEditorActivity.EXTRA_RESULT_KEY_DATA, hashMapOf<String, Any?>(
                "main" to value, "nested" to arrayListOf(hashMapOf("tap" to "你好"))))))

    private fun parcelRoundTrip(state: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(state)
            parcel.setDataPosition(0)
            return parcel.readBundle(javaClass.classLoader)!!
        } finally { parcel.recycle() }
    }

    @Test fun resultsWaitForAnExplicitDrainAndAreAppliedOnlyOnce() {
        val store = LayoutEditorResultStore()
        val request = Intent()
        store.begin(Channel.KEY, "original-file", "qwerty@方案#split", request)
        store.receive(Channel.KEY, result(request))
        val pending = store.takeAll().single()
        assertEquals("original-file", pending.target?.profile)
        assertEquals("qwerty@方案#split", pending.target?.entry)
        assertEquals("changed", pending.data.serializable<HashMap<String, Any?>>(
            KeyEditorActivity.EXTRA_RESULT_KEY_DATA)?.get("main"))
        assertTrue(store.takeAll().isEmpty())
        store.receive(Channel.KEY, result(request))
        assertTrue("replayed token", store.takeAll().isEmpty())
    }

    @Test fun outstandingRequestSurvivesProcessStateParceling() {
        val store = LayoutEditorResultStore()
        val request = Intent()
        store.begin(Channel.AUX, "original-file", "numeric", request)
        val saved = Bundle().also(store::save)
        val restored = LayoutEditorResultStore().apply { restore(parcelRoundTrip(saved)) }
        restored.receive(Channel.AUX, result(request))
        assertEquals("original-file", restored.takeAll().single().target?.profile)
    }

    @Test fun queuedResultAndItsNestedPayloadSurviveRepeatedSaveBeforeLoad() {
        val store = LayoutEditorResultStore()
        val request = Intent()
        store.begin(Channel.KEY, "original-file", "qwerty", request)
        store.receive(Channel.KEY, result(request, "draft"))
        var restored = store
        repeat(3) {
            val saved = Bundle().also(restored::save)
            restored = LayoutEditorResultStore().apply { restore(parcelRoundTrip(saved)) }
        }
        val pending = restored.takeAll().single()
        assertEquals("original-file", pending.target?.profile)
        assertEquals(2, pending.data.getIntExtra(KeyEditorActivity.EXTRA_ROW_INDEX, -1))
        val payload = pending.data.serializable<HashMap<String, Any?>>(KeyEditorActivity.EXTRA_RESULT_KEY_DATA)!!
        assertEquals("draft", payload["main"])
        assertEquals(arrayListOf(hashMapOf("tap" to "你好")), payload["nested"])
        assertTrue(restored.takeAll().isEmpty())
    }

    @Test fun bothChannelsSurviveInArrivalOrderWithIndependentTargets() {
        val store = LayoutEditorResultStore()
        val key = Intent()
        val aux = Intent()
        store.begin(Channel.KEY, "profile", "one", key)
        store.begin(Channel.AUX, "profile", "two", aux)
        store.receive(Channel.AUX, result(aux))
        store.receive(Channel.KEY, result(key))
        val saved = Bundle().also(store::save)
        val restored = LayoutEditorResultStore().apply { restore(parcelRoundTrip(saved)) }
        assertEquals(listOf("two", "one"), restored.takeAll().map { it.target?.entry })
    }

    @Test fun staleOrWrongChannelResultsCannotConsumeNewRequest() {
        val store = LayoutEditorResultStore()
        val old = Intent()
        val current = Intent()
        store.begin(Channel.KEY, "profile", "old", old)
        store.begin(Channel.KEY, "profile", "current", current)
        store.receive(Channel.KEY, result(old))
        store.receive(Channel.KEY, ActivityResult(Activity.RESULT_CANCELED, result(old).data))
        store.receive(Channel.AUX, result(current))
        assertTrue(store.takeAll().isEmpty())
        store.receive(Channel.KEY, result(current))
        assertEquals("current", store.takeAll().single().target?.entry)
    }

    @Test fun canceledAndEmptyResultsDoNotEditTheLayout() {
        val store = LayoutEditorResultStore()
        val request = Intent()
        store.begin(Channel.KEY, "profile", "layout", request)
        store.receive(Channel.KEY, ActivityResult(Activity.RESULT_OK, null))
        assertTrue(store.takeAll().isEmpty())
        store.receive(Channel.KEY, ActivityResult(Activity.RESULT_CANCELED, null))
        store.receive(Channel.KEY, result(request))
        assertTrue(store.takeAll().isEmpty())
    }

    @Test fun tokenlessLegacyChildResolvesItsSelectionAfterLoad() {
        val store = LayoutEditorResultStore()
        store.receive(Channel.KEY, result(Intent()))
        assertNull(store.takeAll().single().target)
        val current = Intent()
        store.begin(Channel.KEY, "profile", "layout", current)
        store.receive(Channel.KEY, result(Intent()))
        assertTrue(store.takeAll().isEmpty())
        store.receive(Channel.KEY, result(current))
        assertEquals("layout", store.takeAll().single().target?.entry)
    }

    @Test fun saveAndDeleteKeepTheOriginalOpaqueTokenWithoutChangingPayload() {
        val store = LayoutEditorResultStore()
        val request = Intent()
        store.begin(Channel.KEY, "profile", "layout", request)
        val delete = Intent().putExtra(KeyEditorActivity.EXTRA_RESULT_ACTION, KeyEditorActivity.RESULT_ACTION_DELETE)
        assertSame(delete, LayoutEditorResultStore.attachRequestToken(request, delete))
        store.receive(Channel.KEY, ActivityResult(Activity.RESULT_OK, delete))
        assertEquals(KeyEditorActivity.RESULT_ACTION_DELETE,
            store.takeAll().single().data.getStringExtra(KeyEditorActivity.EXTRA_RESULT_ACTION))
    }

    @Test fun namesAndProfilesRetainTheirFileTargetAcrossLoading() {
        val store = LayoutEditorResultStore()
        val profile = Intent()
        val layout = Intent()
        store.begin(Channel.PROFILE, "original", "", profile)
        store.begin(Channel.LAYOUT, "original", "source-layout", layout)
        store.receive(Channel.PROFILE, result(profile, "new-profile"))
        store.receive(Channel.LAYOUT, result(layout, "new-layout"))
        val restored = LayoutEditorResultStore().apply { restore(parcelRoundTrip(Bundle().also(store::save))) }
        assertEquals(Channel.PROFILE, restored.takeNext()?.channel)
        // Applying a profile can start a fresh asynchronous load: leave later results queued.
        val savedDuringReload = parcelRoundTrip(Bundle().also(restored::save))
        val afterReload = LayoutEditorResultStore().apply { restore(savedDuringReload) }
        val remaining = afterReload.takeNext()!!
        assertEquals(Channel.LAYOUT, remaining.channel)
        assertEquals("original", remaining.target?.profile)
        assertEquals("source-layout", remaining.target?.entry)
        assertNull(afterReload.takeNext())
    }

    @Test fun externalPickerAndScannerPayloadsWaitWithoutStoringImageBytes() {
        val store = LayoutEditorResultStore()
        val uri = android.net.Uri.parse("content://test.provider/layout.png")
        store.receive(Channel.IMAGE, ActivityResult(Activity.RESULT_OK, Intent().setData(uri)))
        store.receive(Channel.SCAN, ActivityResult(Activity.RESULT_OK, Intent().putExtra("scan_content", "qr-chunk")))
        val restored = LayoutEditorResultStore().apply { restore(parcelRoundTrip(Bundle().also(store::save))) }
        assertEquals(uri, restored.takeNext()?.data?.data)
        assertEquals("qr-chunk", restored.takeNext()?.data?.getStringExtra("scan_content"))
        assertNull(restored.takeNext())
    }
}
