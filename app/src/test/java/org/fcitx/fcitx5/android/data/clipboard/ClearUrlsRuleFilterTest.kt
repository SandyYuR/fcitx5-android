/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.clipboard

import android.net.Uri
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.fcitx.fcitx5.android.common.clearurls.ClearUrlsRuleFilter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = android.app.Application::class)
class ClearUrlsRuleFilterTest {
    private fun raw() = RuntimeEnvironment.getApplication().assets.open("clearurls/data.min.json")
        .bufferedReader().use { it.readText() }
    private fun filter() = ClearUrlsRuleFilter(raw())

    @Test fun bundledRulesHavePinnedBytesAndAllProvidersCompile() {
        val raw = raw()
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("b328d2a5b95b1b23bac2c91342a8deee3f9a3826a5950bf9e28e332ffd94cd2f", digest)
        assertEquals(206, JSONObject(raw).getJSONObject("providers").length())
        assertNotNull(ClearUrlsRuleFilter(raw))
    }

    @Test fun ordinaryTrackingParametersAreRemovedFromQueryAndFragment() {
        assertEquals("https://example.test/?keep=1#section=2", filter().transform(
            "https://example.test/?keep=1&utm_source=pc&fbclid=abc#section=2&utm_medium=share"))
    }

    @Test fun encodedValuesAndDuplicateParametersArePreservedExactly() {
        val kept = "x=a%26b&x=c%3Dd&empty=&flag&q=x+y%2Bz&nested=%2526"
        assertEquals("https://example.test/?$kept", filter().transform("https://example.test/?utm_source=pc&$kept"))
    }

    @Test fun untrackedUrlsAreNotReencodedOrNormalized() {
        val url = "https://example.test/a%2fb?q=a%2bb&bad=%ZZ&flag=&x=1&x=2#raw=%2526"
        assertEquals(url, filter().transform(url))
    }

    @Test fun localEditorPairingKeysAndEscapedFileNamesStayIntact() {
        val url = "http://192.168.0.8:6014/?key=123456&file=scheme%26layout.json&token=a%2Bb"
        assertEquals(url, filter().transform(url))
    }

    @Test fun encodedParameterNamesAreMatchedWithoutChangingTheirValues() {
        assertEquals("https://example.test/?keep=%26utm_source%3Dsecret", filter().transform(
            "https://example.test/?%75tm_source=pc&keep=%26utm_source%3Dsecret"))
    }

    @Test fun removingAllTrackersRemovesEmptyQueryAndFragmentDelimiters() {
        assertEquals("https://example.test/", filter().transform("https://example.test/?utm_source=pc#utm_medium=share"))
    }

    @Test fun amazonPathQueryAndReferralRulesAreApplied() {
        assertEquals("https://www.amazon.com/dp/B012345678?keep=a%26b", filter().transform(
            "https://www.amazon.com/dp/B012345678/ref=share?tag=affiliate-20&pf_rd_p=x&keep=a%26b"))
    }

    @Test fun youtubeVideoIdentityAndTimestampSurviveSharingCleanup() {
        assertEquals("https://youtu.be/abc123?t=35", filter().transform(
            "https://youtu.be/abc123?si=tracking&feature=share&t=35"))
    }

    @Test fun googleRedirectIsUnwrappedThenFilteredWithoutDoubleDecodingValues() {
        val target = "https://example.test/?q=a%26b&nested=%2526&utm_source=pc"
        assertEquals("https://example.test/?q=a%26b&nested=%2526", filter().transform(
            "https://www.google.com/url?q=${Uri.encode(target)}&sa=t"))
    }

    @Test fun alreadyDecodedRedirectTargetsKeepTheirQueryEscaping() {
        assertEquals("https://example.test/?q=a%2526b", filter().transform(
            "https://www.google.com/url?q=https://example.test/?q=a%2526b&sa=t"))
    }

    @Test fun googleOauthExceptionsKeepFunctionalParameters() {
        val url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=client&state=a%26b&source=keep"
        assertEquals(url, filter().transform(url))
    }

    @Test fun emptyRedirectCannotEraseTheClipboard() {
        assertEquals("https://www.youtube.com/redirect?q=", filter().transform(
            "https://www.youtube.com/redirect?q=&feature=share"))
    }

    @Test fun plainTextAndOversizedDocumentsStayUntouched() {
        val filter = filter()
        for (text in listOf("hello https://example.test/?utm_source=x", "https://example.test/?utm_source=" + "a".repeat(70_000))) {
            assertSame(text, filter.transform(text))
        }
    }

    @Test fun nestedRedirectsHaveABoundedPassCount() {
        val target = "https://example.test/?keep=a%26b"
        var wrapped = target
        repeat(12) { wrapped = "https://www.google.com/url?q=${Uri.encode(wrapped)}" }
        val first = filter().transform(wrapped)
        assertEquals(wrapped, first)
        assertEquals(first, filter().transform(first))
    }

    @Test fun parallelCopiesDoNotShareQueryParserState() {
        val filter = filter()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 100).map { i -> executor.submit<String> {
                filter.transform("https://example.test/?id=$i&utm_source=pc&value=a%26b")
            } }
            futures.forEachIndexed { i, result ->
                assertEquals("https://example.test/?id=$i&value=a%26b", result.get(10, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }
}
