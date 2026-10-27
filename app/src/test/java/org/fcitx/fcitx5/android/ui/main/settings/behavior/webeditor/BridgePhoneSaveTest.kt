/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.webeditor

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class BridgePhoneSaveTest {
    private fun request(session: ImeWebEditorBridgeServer.Session, method: String, path: String,
                        body: String = "", cookie: String? = null): String = Socket("127.0.0.1", session.port).use { socket ->
        socket.soTimeout = 5000
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "$method $path HTTP/1.1\r\nHost: 127.0.0.1:${session.port}\r\n" +
            "Content-Length: ${bytes.size}\r\n" + (cookie?.let { "Cookie: $it\r\n" } ?: "") + "\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray()); write(bytes) }
        socket.getInputStream().bufferedReader().readText()
    }

    @Test fun aPairedBrowserSavesDirectlyToThePhonesLayoutFile() {
        ImeWebEditorBridgeServer.stop()
        val file = UserConfigFiles.textKeyboardLayoutJson("paired-pc")!!
        file.delete()
        try {
            val session = ImeWebEditorBridgeServer.start()
            val body = """{"profile":"paired-pc","json":"{\"rows\":[]}"}"""
            assertTrue(request(session, "PUT", "/api/v1/layout", body).startsWith("HTTP/1.1 401"))
            assertFalse(file.exists())
            val paired = request(session, "POST", "/pair", "code=${session.pairingCode}")
            assertTrue(paired.startsWith("HTTP/1.1 303"))
            val cookie = paired.lineSequence().first { it.startsWith("Set-Cookie: ") }
                .removePrefix("Set-Cookie: ").substringBefore(';')
            val saved = request(session, "PUT", "/api/v1/layout", body, cookie)
            assertTrue(saved, saved.startsWith("HTTP/1.1 200"))
            assertEquals(Json.parseToJsonElement("""{"rows":[]}"""), Json.parseToJsonElement(file.readText()))
            val read = request(session, "GET", "/api/v1/layout?profile=paired-pc", cookie = cookie)
            assertTrue(read.startsWith("HTTP/1.1 200"))
            assertTrue(read.contains("paired-pc"))
        } finally {
            ImeWebEditorBridgeServer.stop()
            file.delete()
        }
    }
}
