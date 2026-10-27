/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProtectedBackupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val password = "fixture 简单密码".toCharArray()
    private fun encrypt(bytes: ByteArray) = ByteArrayOutputStream().also {
        ProtectedBackup.encrypt(bytes.inputStream(), it, password)
    }.toByteArray()
    private fun reject(bytes: ByteArray, secret: CharArray = password, max: Long = Long.MAX_VALUE) {
        val dir = temporary.newFolder()
        try { ProtectedBackup.decrypt(bytes.inputStream(), dir, secret, max); fail("Must reject") }
        catch (_: Exception) { }
        assertTrue("No partial ZIP may remain", dir.listFiles()!!.isEmpty())
    }

    @Test fun aZipLargerThanOneRecordRoundTripsWithoutPlaintextOutsidePrivateStaging() {
        val content = ByteArray(200_000) { (it * 31).toByte() }
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out ->
                out.putNextEntry(ZipEntry("fixture")); out.write(content); out.closeEntry()
            }
        }.toByteArray() + content // exercise several records even for compressible input
        val encrypted = encrypt(zip)
        assertTrue(ProtectedBackup.isProtected(encrypted.inputStream()))
        val file = ProtectedBackup.decrypt(encrypted.inputStream(), temporary.root, password)
        assertArrayEquals(zip, file.readBytes())
        assertTrue(file.delete())
    }
    @Test fun randomSaltAndNonceMakeIndependentExportsDifferent() {
        val bytes = "same source".toByteArray()
        assertFalse(encrypt(bytes).contentEquals(encrypt(bytes)))
    }
    @Test fun wrongPasswordIsRejectedWithoutAnImportableTemporary() {
        reject(encrypt("secret data".toByteArray()), "wrong password".toCharArray())
    }
    @Test fun tamperingWithCiphertextOrAuthenticatedHeaderIsRejected() {
        val encrypted = encrypt(ByteArray(90_000) { 42 })
        reject(encrypted.clone().apply { this[60] = (this[60].toInt() xor 1).toByte() })
        reject(encrypted.clone().apply { this[20] = (this[20].toInt() xor 1).toByte() })
    }
    @Test fun truncatedMissingFinalAndAppendedDataAreRejected() {
        val encrypted = encrypt(ByteArray(90_000))
        reject(encrypted.copyOf(encrypted.size - 1))
        reject(encrypted.copyOf(encrypted.size - 21)) // whole final record removed
        reject(encrypted + byteArrayOf(1))
    }
    @Test fun reorderedRecordsAndForgedLengthsAreRejectedBeforeLargeAllocation() {
        val encrypted = encrypt(ByteArray(140_000) { (it % 251).toByte() })
        val recordSize = 65_536 + 21
        val swapped = encrypted.clone()
        encrypted.copyInto(swapped, 42, 42 + recordSize, 42 + 2 * recordSize)
        encrypted.copyInto(swapped, 42 + recordSize, 42, 42 + recordSize)
        reject(swapped)
        reject(encrypted.clone().apply { ByteBuffer.wrap(this, 42, 4).putInt(Int.MAX_VALUE) })
    }
    @Test fun unsupportedVersionAndHostileKdfWorkFactorAreRejected() {
        val encrypted = encrypt(byteArrayOf(1))
        reject(encrypted.clone().apply { this[8] = 2 })
        reject(encrypted.clone().apply { ByteBuffer.wrap(this, 10, 4).putInt(Int.MAX_VALUE) })
    }
    @Test fun sizeBudgetAndCancellationDeleteAllDecryptedBytes() {
        val encrypted = encrypt(ByteArray(90_000))
        reject(encrypted, max = 1)
        val dir = temporary.newFolder()
        var checks = 0
        try {
            ProtectedBackup.decrypt(encrypted.inputStream(), dir, password) {
                if (++checks == 3) throw InterruptedIOException("fixture cancellation")
            }
            fail("Must cancel")
        } catch (_: InterruptedIOException) { }
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun legacyZipAndShortInputAreNotMistakenForEncryptedArchives() {
        assertFalse(ProtectedBackup.isProtected(byteArrayOf(0x50, 0x4b, 3, 4).inputStream()))
        assertFalse(ProtectedBackup.isProtected(byteArrayOf(1, 2).inputStream()))
    }
    @Test fun emptySourceAuthenticatesWithAnExplicitFinalRecord() {
        val encrypted = encrypt(byteArrayOf())
        val file = ProtectedBackup.decrypt(encrypted.inputStream(), temporary.root, password)
        assertEquals(0, file.length()); file.delete()
    }
}
