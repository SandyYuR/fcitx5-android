/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.webeditor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class BridgeThemePrefsTest {
    private lateinit var session: ImeWebEditorBridgeServer.Session
    private lateinit var cookie: String

    @Before fun startPairedBridge() {
        ImeWebEditorBridgeServer.stop()
        val prefs = ThemeManager.prefs
        prefs.moveMainTextForAltLabel.setValue(true)
        prefs.altLabelOnSide.setValue(false)
        prefs.altLabelSide.setValue(ThemePrefs.AltLabelSide.Right)
        prefs.showKeyAltLabels.setValue(true)
        session = ImeWebEditorBridgeServer.start()
        val paired = request("POST", "/pair", "code=${session.pairingCode}", authenticated = false)
        assertTrue(paired, paired.startsWith("HTTP/1.1 303"))
        cookie = paired.lineSequence().first { it.startsWith("Set-Cookie: ") }
            .removePrefix("Set-Cookie: ").substringBefore(';')
    }

    @After fun stopBridge() {
        ImeWebEditorBridgeServer.stop()
    }

    private fun request(method: String, path: String, body: String = "", authenticated: Boolean = true): String =
        Socket("127.0.0.1", session.port).use { socket ->
            socket.soTimeout = 5000
            val bytes = body.toByteArray(Charsets.UTF_8)
            val headers = "$method $path HTTP/1.1\r\nHost: 127.0.0.1:${session.port}\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                (if (authenticated) "Cookie: $cookie\r\n" else "") + "\r\n"
            socket.getOutputStream().apply { write(headers.toByteArray()); write(bytes) }
            socket.getInputStream().bufferedReader().readText()
        }

    private fun readPrefs(): JsonObject {
        val response = request("GET", "/api/v1/theme/prefs")
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
        return Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject
    }

    private fun writePrefs(body: String) {
        val response = request("PUT", "/api/v1/theme/prefs", body)
        assertTrue(response, response.startsWith("HTTP/1.1 200"))
        assertTrue(Json.parseToJsonElement(response.substringAfter("\r\n\r\n")).jsonObject.getValue("ok").jsonPrimitive.boolean)
    }

    private fun assertLabels(body: JsonObject, moveMain: Boolean, onSide: Boolean, side: String, show: Boolean) {
        assertEquals(moveMain, body.getValue("moveMainTextForAltLabel").jsonPrimitive.boolean)
        assertEquals(onSide, body.getValue("altLabelOnSide").jsonPrimitive.boolean)
        assertEquals(side, body.getValue("altLabelSide").jsonPrimitive.content)
        assertEquals(show, body.getValue("showKeyAltLabels").jsonPrimitive.boolean)
    }

    @Test fun getReadsCurrentPhonePreferencesAndUsesLowercaseSides() {
        assertLabels(readPrefs(), moveMain = true, onSide = false, side = "right", show = true)
        val prefs = ThemeManager.prefs
        prefs.moveMainTextForAltLabel.setValue(false)
        prefs.altLabelOnSide.setValue(true)
        prefs.altLabelSide.setValue(ThemePrefs.AltLabelSide.Left)
        prefs.showKeyAltLabels.setValue(false)
        assertLabels(readPrefs(), moveMain = false, onSide = true, side = "left", show = false)
    }

    @Test fun pairedPutPersistsAllFourControlsAndCanRestoreThem() {
        writePrefs("""{"moveMainTextForAltLabel":false,"altLabelOnSide":true,"altLabelSide":"left","showKeyAltLabels":false}""")
        val prefs = ThemeManager.prefs
        assertFalse(prefs.moveMainTextForAltLabel.getValue())
        assertTrue(prefs.altLabelOnSide.getValue())
        assertEquals(ThemePrefs.AltLabelSide.Left, prefs.altLabelSide.getValue())
        assertFalse(prefs.showKeyAltLabels.getValue())
        assertLabels(readPrefs(), moveMain = false, onSide = true, side = "left", show = false)
        writePrefs("""{"moveMainTextForAltLabel":true,"altLabelOnSide":false,"altLabelSide":"right","showKeyAltLabels":true}""")
        assertLabels(readPrefs(), moveMain = true, onSide = false, side = "right", show = true)
    }

    @Test fun partialUpdatesAndMalformedControlValuesPreserveOtherPhoneSettings() {
        writePrefs("""{"moveMainTextForAltLabel":false,"altLabelOnSide":true,"altLabelSide":"left","showKeyAltLabels":false}""")
        writePrefs("""{"showKeyAltLabels":true}""")
        assertLabels(readPrefs(), moveMain = false, onSide = true, side = "left", show = true)
        for (side in listOf("\"center\"", "\"Left\"", "\"\"", "null", "{}", "[]", "17")) {
            writePrefs("""{"altLabelSide":$side}""")
            assertEquals(ThemePrefs.AltLabelSide.Left, ThemeManager.prefs.altLabelSide.getValue())
        }
        writePrefs("""{"moveMainTextForAltLabel":"true","altLabelOnSide":0,"showKeyAltLabels":{}}""")
        writePrefs("""{"moveMainTextForAltLabel":null,"altLabelOnSide":[],"showKeyAltLabels":null}""")
        assertLabels(readPrefs(), moveMain = false, onSide = true, side = "left", show = true)
    }

    @Test fun anUnpairedBrowserCannotReadOrChangeTheControls() {
        assertTrue(request("GET", "/api/v1/theme/prefs", authenticated = false).startsWith("HTTP/1.1 401"))
        val body = """{"moveMainTextForAltLabel":false,"altLabelOnSide":true,"altLabelSide":"left","showKeyAltLabels":false}"""
        assertTrue(request("PUT", "/api/v1/theme/prefs", body, authenticated = false).startsWith("HTTP/1.1 401"))
        assertLabels(readPrefs(), moveMain = true, onSide = false, side = "right", show = true)
    }
}
