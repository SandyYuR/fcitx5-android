/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync.network

import android.app.Application
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
class ClipCascadePayloadBudgetTest {
    private val budget = ClipCascadePayloadBudget(1024, 256, 128, 4096)
    private val key = ByteArray(32) { it.toByte() }
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun client(encrypted: Boolean = false, limits: ClipCascadePayloadBudget = budget) =
        ClipCascadeClient("http://127.0.0.1", "fixture", "fixture", limits).also {
            if (encrypted) it.javaClass.getDeclaredField("encryptionKey").apply {
                isAccessible = true; set(it, key)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> call(client: ClipCascadeClient, name: String, vararg args: Any): T {
        val method = client.javaClass.declaredMethods.single { it.name == name }
        method.isAccessible = true
        return try { method.invoke(client, *args) as T }
        catch (e: InvocationTargetException) { throw e.targetException }
    }

    private fun encode(client: ClipCascadeClient, data: ClipCascadeClipboardData): String =
        call(client, "encodeOutgoingBody", data)
    private fun decode(client: ClipCascadeClient, body: String): ClipCascadeClipboardData =
        call(client, "decodeIncomingBody", body)
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun envelope(plain: String, nonce: ByteArray = ByteArray(16)): ClipCascadeEncryptedPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return ClipCascadeEncryptedPayload(b64(nonce), b64(sealed.copyOfRange(0, sealed.size - 16)),
            b64(sealed.copyOfRange(sealed.size - 16, sealed.size)))
    }

    @Test fun ordinaryTextRoundTripsWithAndWithoutEncryption() {
        val data = ClipCascadeClipboardData("你好 😀\n\"quoted\" \\ fixture")
        for (encrypted in listOf(false, true)) {
            val client = client(encrypted)
            assertEquals(data, decode(client, encode(client, data)))
        }
    }

    @Test fun aPaddedBinaryAtTheLimitIsAccepted() {
        val data = ClipCascadeClipboardData(b64(ByteArray(1024)), "file_eager", "fixture.bin")
        for (encrypted in listOf(false, true)) {
            val client = client(encrypted)
            assertEquals(data, decode(client, encode(client, data)))
        }
    }

    @Test fun unpaddedBase64CannotSlipThroughASmallDecodedLimit() {
        val limits = ClipCascadePayloadBudget(1, 256, 128, 4096)
        val client = client(limits = limits)
        encode(client, ClipCascadeClipboardData("AA", "image"))
        assertThrows(IOException::class.java) { encode(client, ClipCascadeClipboardData("AAA", "image")) }
    }

    @Test fun multibyteTextIsLimitedByUtf8BytesBeforeSerialization() {
        assertThrows(IOException::class.java) { encode(client(), ClipCascadeClipboardData("界".repeat(342))) }
    }

    @Test fun jsonEscapingIsBudgetedBeforeBuildingTheSerializedString() {
        assertThrows(IOException::class.java) {
            encode(client(), ClipCascadeClipboardData("\u0000".repeat(500)))
        }
    }

    @Test fun oversizedMetadataIsRejected() {
        assertThrows(IOException::class.java) {
            encode(client(), ClipCascadeClipboardData("ok", filename = "x".repeat(257)))
        }
        assertThrows(IOException::class.java) {
            encode(client(), ClipCascadeClipboardData("ok", type = "x".repeat(65)))
        }
    }

    @Test fun encryptionExpansionIsCheckedAgainstTheOutgoingQueueBeforeEncoding() {
        val limits = ClipCascadePayloadBudget(1024, 128, 64, 1024)
        val data = ClipCascadeClipboardData("x".repeat(500))
        encode(client(limits = limits), data)
        assertThrows(IOException::class.java) { encode(client(true, limits), data) }
    }

    @Test fun wireBodyLimitWinsBeforeAttemptingToParseInvalidJson() {
        assertThrows(ClipCascadeMessageTooLargeException::class.java) {
            decode(client(), "x".repeat(budget.maxWireBodyBytes.toInt() + 1))
        }
    }

    @Test fun plainBodyHasItsOwnLowerBudget() {
        assertThrows(ClipCascadeMessageTooLargeException::class.java) {
            decode(client(), "{\"payload\":\"${"x".repeat(budget.maxPlainBodyBytes.toInt())}\"}")
        }
    }

    @Test fun incomingDataIsCheckedAfterJsonDecoding() {
        assertThrows(ClipCascadeMessageTooLargeException::class.java) {
            decode(client(), json.encodeToString(ClipCascadeClipboardData("x".repeat(1025))))
        }
    }

    @Test fun ciphertextIsBoundedBeforeBase64AllocationOrAuthentication() {
        val forged = ClipCascadeEncryptedPayload(b64(ByteArray(16)),
            b64(ByteArray(budget.maxPlainBodyBytes.toInt() + 1)), b64(ByteArray(16)))
        assertThrows(ClipCascadeMessageTooLargeException::class.java) {
            decode(client(true), json.encodeToString(forged))
        }
    }

    @Test fun nonceAndTagAreBoundedAndHaveValidatedDecodedLengths() {
        val valid = envelope(json.encodeToString(ClipCascadeClipboardData("ok")))
        val client = client(true)
        for (bad in listOf(valid.copy(nonce = "A".repeat(129)), valid.copy(tag = b64(ByteArray(15))),
            valid.copy(nonce = b64(ByteArray(8))))) {
            assertThrows(IOException::class.java) { decode(client, json.encodeToString(bad)) }
        }
    }

    @Test fun commonTwelveByteGcmNoncesRemainCompatible() {
        val data = ClipCascadeClipboardData("authenticated text")
        assertEquals(data, decode(client(true), json.encodeToString(envelope(json.encodeToString(data), ByteArray(12)))))
    }

    @Test fun aBadAuthenticationTagNeverReturnsPlaintext() {
        val data = envelope(json.encodeToString(ClipCascadeClipboardData("private fixture")))
        val tag = Base64.decode(data.tag, Base64.DEFAULT).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) {
            decode(client(true), json.encodeToString(data.copy(tag = b64(tag))))
        }
    }

    private fun frameBody(client: ClipCascadeClient, raw: String): String {
        val frame: Any = call(client, "parseStompFrame", raw)
        return frame.javaClass.getDeclaredField("body").apply { isAccessible = true }.get(frame) as String
    }

    @Test fun stompAcceptsLfAndCrLfAndCountsUtf8ContentLength() {
        val client = client()
        assertEquals("你好", frameBody(client, "MESSAGE\ncontent-length:6\n\n你好\u0000"))
        assertEquals("你好", frameBody(client, "MESSAGE\r\ncontent-length:6\r\n\r\n你好\u0000"))
    }

    @Test fun stompRejectsOversizedOrMissingHeaders() {
        for (raw in listOf("MESSAGE\n${"x".repeat(129)}\n\n{}\u0000", "MESSAGE")) {
            assertThrows(IOException::class.java) { frameBody(client(), raw) }
        }
    }

    @Test fun stompRejectsTooManySmallHeaders() {
        val limits = ClipCascadePayloadBudget(1024, 256, 4096, 8192)
        assertThrows(IOException::class.java) {
            frameBody(client(limits = limits), "MESSAGE\n" + (1..65).joinToString("\n") { "h$it:v" } + "\n\n{}\u0000")
        }
    }

    @Test fun headerBudgetCountsMultibyteUtf8RatherThanOnlyCharacters() {
        assertThrows(IOException::class.java) {
            frameBody(client(), "MESSAGE\nlabel:${"界".repeat(60)}\n\n{}\u0000")
        }
    }

    @Test fun outgoingFrameIsLimitedBeforeBuildingItsString() {
        assertThrows(ClipCascadeMessageTooLargeException::class.java) {
            call<String>(client(), "encodeStompFrame", "SEND", linkedMapOf<String, String>(), "x".repeat(4096))
        }
    }

    @Test fun stompRejectsInvalidOrMismatchedContentLengths() {
        for (length in listOf("-1", "x", "9223372036854775808", "999999999", "2")) {
            assertThrows(IOException::class.java) { frameBody(client(), "MESSAGE\ncontent-length:$length\n\nx\u0000") }
        }
    }

    @Test fun receivedFramesAreBoundedBeforeSplittingOrTrimming() {
        assertThrows(ClipCascadeMessageTooLargeException::class.java) {
            frameBody(client(), " ".repeat(budget.maxReceivedFrameBytes.toInt() + 1))
        }
        assertThrows(ClipCascadeMessageTooLargeException::class.java) {
            frameBody(client(), "界".repeat(budget.maxReceivedFrameBytes.toInt() / 2))
        }
    }

    @Test fun theUriReadBudgetIncludesBothBase64Expansions() {
        val limits = ClipCascadePayloadBudget(4096, 256, 128, 4096)
        assertTrue(limits.maxOutgoingBinaryBytes in 1 until limits.maxPayloadBytes)
        val data = ClipCascadeClipboardData(b64(ByteArray(limits.maxOutgoingBinaryBytes.toInt())), "file_eager", "f")
        val client = client(true, limits)
        assertEquals(data, decode(client, encode(client, data)))
        assertTrue(ClipCascadePayloadBudget.DEFAULT.maxOutgoingBinaryBytes < 10L * 1024 * 1024)
    }
}
