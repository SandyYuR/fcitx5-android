package org.fcitx.fcitx5.android.utils

import org.junit.Assert.*
import org.junit.Test

class ExternalRimeStorageTest {
    private val authority = "com.android.externalstorage.documents"
    private val root = "/storage/emulated/0"

    @Test fun permissionStrategyCoversBothSidesOfScopedStorage() {
        assertEquals(ExternalRimeStorage.Access.LegacyPermission, ExternalRimeStorage.accessForSdk(23))
        assertEquals(ExternalRimeStorage.Access.LegacyPermission, ExternalRimeStorage.accessForSdk(28))
        assertEquals(ExternalRimeStorage.Access.Unsupported, ExternalRimeStorage.accessForSdk(29))
        assertEquals(ExternalRimeStorage.Access.AllFilesPermission, ExternalRimeStorage.accessForSdk(30))
        assertEquals(ExternalRimeStorage.Access.AllFilesPermission, ExternalRimeStorage.accessForSdk(36))
    }

    @Test fun primaryAndSdCardPathsRoundTripThroughDocumentIds() {
        for (id in listOf("primary:Rime|sync/中文", "1234-ABCD:Rime/词库",
            "12345678-1234-1234-1234-123456789abc:Rime")) {
            val path = ExternalRimeStorage.absolutePath(authority, id, root)!!
            assertEquals(id, ExternalRimeStorage.documentId(path, root))
        }
    }

    @Test fun currentDirectoryUsesActualPathOnEitherVolume() {
        assertEquals("primary:Sync/Rime", ExternalRimeStorage.documentId("$root/Sync/Rime", root))
        assertEquals("1234-ABCD:Sync/Rime", ExternalRimeStorage.documentId("/storage/1234-ABCD/Sync/Rime", root))
        assertNull(ExternalRimeStorage.documentId("${root}other/Rime", root))
    }

    @Test fun cloudDocumentsAndMalformedVolumeIdsCannotBecomeNativePaths() {
        assertNull(ExternalRimeStorage.absolutePath("com.google.android.apps.docs.storage", "primary:Rime", root))
        assertNull(ExternalRimeStorage.absolutePath(authority, "cloud:Rime", root))
        assertNull(ExternalRimeStorage.absolutePath(authority, "primary", root))
        assertNull(ExternalRimeStorage.absolutePath(authority, "primary:../private", root))
        assertNull(ExternalRimeStorage.absolutePath(authority, "primary:Rime/../../private", root))
    }

    @Test fun restoreOnlyReplacesDedicatedSubdirectoriesOnKnownVolumes() {
        assertEquals(root, ExternalRimeStorage.restoreVolumeRoot("$root/Documents/Rime", root))
        assertEquals("/storage/1234-ABCD", ExternalRimeStorage.restoreVolumeRoot("/storage/1234-ABCD/Rime", root))
        assertNull(ExternalRimeStorage.restoreVolumeRoot(root, root))
        assertNull(ExternalRimeStorage.restoreVolumeRoot("$root/Documents", root))
        assertNull(ExternalRimeStorage.restoreVolumeRoot("$root/Download", root))
        assertNull(ExternalRimeStorage.restoreVolumeRoot("$root/Android/data/another.app", root))
        assertNull(ExternalRimeStorage.restoreVolumeRoot("$root/Documents/../Rime", root))
        assertNull(ExternalRimeStorage.restoreVolumeRoot("/data/user/0/another.app", root))
    }
}
