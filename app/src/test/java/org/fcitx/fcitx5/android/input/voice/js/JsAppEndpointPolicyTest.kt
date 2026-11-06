/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class JsAppEndpointPolicyTest {
    @Test fun emptyHostsDeclarationAllowsTheExactConfiguredHostAndModelsPath() {
        val policy = JsAppEndpointPolicy(emptySet(), mapOf("customUrl" to "  https://RELAY.example:8443/api/v1  "))
        val parsed = policy.checkUrl("https://relay.example:8443/api/v1/responses?route=1")
        assertEquals("relay.example", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals("/api/v1/responses", parsed.encodedPath)
        assertEquals("route=1", parsed.encodedQuery)
        assertEquals("relay.example", policy.checkUrl("https://relay.example:8443/api/v1/models").host)
    }

    @Test fun noConfiguredEndpointsMeansNoNetworkDestinations() {
        val policy = JsAppEndpointPolicy(emptySet(), mapOf("customUrl" to "", "model" to "model-x"))
        assertTrue(runCatching { policy.checkUrl("https://api.openai.com/v1/responses") }.isFailure)
        assertTrue(runCatching { policy.checkUrl("https://api.anthropic.com/v1/messages") }.isFailure)
    }

    @Test fun renamedOrRemovedEndpointsDoNotRemainAllowedBecauseOfAnOldApproval() {
        // Permission storage may still contain an old consent. Eligibility is always derived anew.
        val policy = JsAppEndpointPolicy(emptySet(), mapOf("customUrl" to "https://new.example/v1"))
        assertTrue(runCatching { policy.checkUrl("https://old.example/v1/responses") }.isFailure)
        assertEquals("new.example", policy.checkUrl("https://new.example/v1/responses").host)
    }

    @Test fun urlsCannotSpoofTheConfiguredHostOrUseInsecureTransports() {
        val policy = JsAppEndpointPolicy(emptySet(), mapOf("customUrl" to "https://relay.example/v1"))
        for (url in listOf("https://relay.example.evil.test/v1", "https://sub.relay.example/v1",
            "https://relay.example@evil.test/v1", "https://user@relay.example/v1",
            "https://relay.example\\@evil.test/v1", "http://relay.example/v1", "wss://relay.example/v1", "/v1/responses")) {
            assertTrue(url, runCatching { policy.checkUrl(url) }.isFailure)
        }
    }

    @Test fun unconfiguredHostsAreRejectedBeforeAnyCredentialIsRead() = runBlocking {
        var secretReads = 0
        val policy = JsAppEndpointPolicy(emptySet(), mapOf("customUrl" to "https://relay.example/v1"))
        val http = JsAppHttp(policy::checkUrl, { secretReads++; "test-secret" })
        assertTrue(runCatching { http.request(buildJsonObject {
            put("url", "https://other.example/v1/responses"); put("credential", "customKey")
        }) }.isFailure)
        assertEquals(0, secretReads)
    }
}
