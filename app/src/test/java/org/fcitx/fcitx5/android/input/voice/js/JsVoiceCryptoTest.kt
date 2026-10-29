/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class JsVoiceCryptoTest {
    @Test fun signsTheRfc2202VectorWithTheRequestedSecretField() {
        val signature = JsVoiceCrypto.hmacSha1("secretKey", "what do ya want for nothing?") { field ->
            assertEquals("secretKey", field)
            "Jefe"
        }
        assertEquals("7/zfauXrL6LSdBbV8YTfnCWafHk=", Base64.getEncoder().encodeToString(signature))
    }

    @Test fun signsCanonicalTencentTextAndChineseHotwordsAsUtf8() {
        val payload = "asr.cloud.tencent.com/asr/v2/1234567890?hotword_list=腾讯云|10&secretid=AKIDTEST"
        val signature = JsVoiceCrypto.hmacSha1("secretKey", payload) { "test-secret" }
        assertEquals("0iarwsi1UT35hRUFqeqhP5T3igY=", Base64.getEncoder().encodeToString(signature))
    }

    @Test fun missingSecretReportsTheFieldWithoutEchoingThePayload() {
        for (secret in listOf(null, "", " ")) {
            try {
                JsVoiceCrypto.hmacSha1("secretKey", "private-payload") { secret }
                fail("expected missing secret")
            } catch (e: JsVoiceSecretMissingException) {
                assertEquals("secretKey", e.field)
                assertFalse(e.message.orEmpty().contains("private-payload"))
            }
        }
    }
}
