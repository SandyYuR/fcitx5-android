/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.webeditor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket
import java.nio.file.Files

class BridgeSessionAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun credentialsAreRandomAndBoundToTheSessionHostAndOrigin() {
        val access = BridgeSessionAccess("192.168.1.2", 18888)
        assertNotEquals(access.token, BridgeSessionAccess("192.168.1.2", 18888).token)
        assertFalse(access.authenticated(mapOf("host" to "192.168.1.2:18888")))
        val authorized = mapOf("host" to "192.168.1.2:18888", "authorization" to "Bearer ${access.token}")
        assertTrue(access.authenticated(authorized))
        assertFalse(access.authenticated(authorized + ("origin" to "https://attacker.example")))
        assertFalse(access.authenticated(authorized + ("host" to "attacker.example:18888")))
        assertTrue(access.authenticated(mapOf("host" to "127.0.0.1:18888", "cookie" to access.cookie.substringBefore(';'))))
    }

    @Test fun previewOnlyReadsBoundedImagesFromIconDirectories() {
        val icons = temporary.newFolder("button_icons")
        val private = temporary.newFolder("private")
        val image = File(icons, "normal.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2, 3), BridgeSessionAccess.readPreview(image, listOf(icons)))
        val secret = File(private, "secret.png").apply { writeText("private") }
        assertTrue(runCatching { BridgeSessionAccess.readPreview(secret, listOf(icons)) }.isFailure)
        val link = File(icons, "link.png")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertTrue(runCatching { BridgeSessionAccess.readPreview(link, listOf(icons)) }.isFailure)
        val large = File(icons, "large.png").apply { writeBytes(ByteArray(4 * 1024 * 1024 + 1)) }
        assertTrue(runCatching { BridgeSessionAccess.readPreview(large, listOf(icons)) }.isFailure)
    }

    @Test fun theSixDigitCodeHasARestrictedGuessingRate() {
        var now = 0L
        val access = BridgeSessionAccess("127.0.0.1", 18888) { now }
        assertTrue(access.pairingCode.matches(Regex("[0-9]{6}")))
        val wrong = if (access.pairingCode == "000000") "000001" else "000000"
        repeat(5) { assertEquals(BridgeSessionAccess.PairingResult.Rejected, access.pair(wrong)) }
        assertEquals(BridgeSessionAccess.PairingResult.Limited, access.pair(access.pairingCode))
        now += 60_000_000_000L
        assertEquals(BridgeSessionAccess.PairingResult.Accepted, access.pair(access.pairingCode))
    }

    private fun request(session: ImeWebEditorBridgeServer.Session, path: String, method: String = "GET",
                        headers: String = "", body: String = ""): String = Socket("127.0.0.1", session.port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: 127.0.0.1:${session.port}\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n$headers\r\n$body").toByteArray())
        socket.getInputStream().bufferedReader().readText()
    }

    @Test fun liveServerPairsWithTheShortCodeAndRevokesCookiesOnRestart() {
        ImeWebEditorBridgeServer.stop()
        try {
            val session = ImeWebEditorBridgeServer.start()
            assertTrue(request(session, "/api/v1/meta").startsWith("HTTP/1.1 401"))
            val pairingPage = request(session, "/editor")
            assertTrue(pairingPage.startsWith("HTTP/1.1 200"))
            assertFalse(pairingPage.contains(session.pairingCode))
            assertFalse(session.editorUrl.contains(session.access.token))
            val bootstrap = request(session, "/pair", "POST", body = "code=${session.pairingCode}")
            assertTrue(bootstrap.startsWith("HTTP/1.1 303"))
            assertTrue(bootstrap.contains("Location: /editor\r\n"))
            assertTrue(bootstrap.contains("HttpOnly; SameSite=Strict"))
            assertTrue(bootstrap.contains("Referrer-Policy: no-referrer"))
            val cookie = bootstrap.lineSequence().first { it.startsWith("Set-Cookie:") }
                .removePrefix("Set-Cookie: ").substringBefore(';')
            val accepted = request(session, "/api/v1/meta", "OPTIONS", "Cookie: $cookie\r\n")
            assertTrue(accepted.startsWith("HTTP/1.1 204"))
            assertFalse(accepted.contains("Access-Control-Allow-Origin: *"))
            assertTrue(request(session, "/api/v1/meta", "OPTIONS", "Cookie: $cookie\r\nOrigin: https://attacker.example\r\n")
                .startsWith("HTTP/1.1 403"))
            ImeWebEditorBridgeServer.stop()
            val restarted = ImeWebEditorBridgeServer.start()
            assertTrue(request(restarted, "/api/v1/meta", "OPTIONS", "Cookie: $cookie\r\n").startsWith("HTTP/1.1 401"))
        } finally { ImeWebEditorBridgeServer.stop() }
    }
}
