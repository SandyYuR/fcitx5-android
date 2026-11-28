/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.ui

import android.net.Uri
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = org.fcitx.fcitx5.android.FcitxApplication::class,
    shadows = [org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow::class])
class StoragePathUriTest {
    @Test fun encodedPlusAndLiteralPlusResolveToTheDisplayedDocument() {
        for (input in listOf("/A+B", "/A%2BB")) {
            val uri = Uri.parse(StoragePathUtils.derivePersistableUri(input))
            assertEquals("primary:A+B", DocumentsContract.getTreeDocumentId(uri))
            assertEquals("/A+B", StoragePathUtils.visiblePathFromUriString(uri.toString()))
        }
    }

    @Test fun unchangedDisplayRetainsProviderIdentityAndLiteralPercentEscapes() {
        for (uri in listOf(
            "content://another.provider/tree/arbitrary-id",
            DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:A%2BB").toString()
        )) {
            val display = StoragePathUtils.visiblePathFromUriString(uri).orEmpty()
            assertEquals(uri, StoragePathUtils.derivePersistableUri(display, display, uri))
        }
    }
}
