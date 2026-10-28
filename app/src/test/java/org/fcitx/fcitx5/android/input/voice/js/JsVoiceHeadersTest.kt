/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsVoiceHeadersTest {

    @Test
    fun defaultAuthIsBearerAuthorization() {
        val headers = JsVoiceHeaders.build("apiKey", "sk-1", "", "", "")
        assertEquals("Bearer sk-1", headers["Authorization"])
    }

    @Test
    fun customHeaderNameAndPrefixAreHonoured() {
        val headers = JsVoiceHeaders.build("apiKey", "sk-1", "X-Api-Key", "Key ", "")
        assertEquals("Key sk-1", headers["X-Api-Key"])
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun explicitHeaderWithEmptyPrefixInjectsRawSecret() {
        // 火山引擎要求 X-Api-Key 的值就是原始 key：显式给头名时空前缀是明确意图，不能补 Bearer。
        val headers = JsVoiceHeaders.build("apiKey", "abc-123", "X-Api-Key", "", "")
        assertEquals("abc-123", headers["X-Api-Key"])
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun extraHeadersArePassedThrough() {
        val headers = JsVoiceHeaders.build("", null, "", "", """{"X-DashScope-SSE":"disable"}""")
        assertEquals("disable", headers["X-DashScope-SSE"])
        assertTrue(headers.keys.none { it.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun reservedHeadersCannotBeOverridden() {
        try {
            JsVoiceHeaders.build("", null, "", "", """{"Host":"evil.example.com"}""")
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Host", ignoreCase = true))
        }
    }

    @Test
    fun missingSecretIsReportedWithFieldName() {
        try {
            JsVoiceHeaders.build("apiKey", null, "", "", "")
            fail("expected JsVoiceSecretMissingException")
        } catch (expected: JsVoiceSecretMissingException) {
            assertEquals("apiKey", expected.field)
        }
    }

    @Test
    fun headerValuesCannotCarryLineBreaks() {
        val headers = JsVoiceHeaders.build(
            "", null, "", "",
            """{"X-Test":"a\r\nInjected: 1"}"""
        )
        assertEquals("aInjected: 1", headers["X-Test"])
    }

    @Test
    fun invalidHeaderNamesAreRejected() {
        try {
            JsVoiceHeaders.build("", null, "", "", """{"X Bad":"v"}""")
            fail("expected rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Invalid header", ignoreCase = true))
        }
    }
}
