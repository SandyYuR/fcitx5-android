/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.wanxiang

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class WanxiangGithubTest {
    @Test fun credentialsAreRestrictedToHttpsApiRequestsForTheWanxiangRepositories() {
        val token = "test_token"
        fun request(url: String) = WanxiangGithub.apiRequest(Request.Builder().url(url).header("Authorization", "Bearer $token").build(), token)
        assertEquals("Bearer $token", request("https://api.github.com/repos/amzxyz/rime-wanxiang/releases/latest").header("Authorization"))
        for (url in listOf("https://github.com/amzxyz/rime-wanxiang/releases/latest/download/a.zip", "https://raw.githubusercontent.com/amzxyz/rime-wanxiang-data/wanxiang/extra-dicts.json",
            "https://objects.githubusercontent.com/file", "https://api.github.com/repos/other/repository/releases", "http://api.github.com/repos/amzxyz/rime-wanxiang/releases/latest",
            "https://api.github.com/user")) assertNull(url, request(url).header("Authorization"))
    }
    @Test fun tokensAreNormalizedWithoutAcceptingHeaderInjection() {
        assertEquals("ghp_example", WanxiangGithub.normalize(" Bearer ghp_example "))
        assertEquals("", WanxiangGithub.normalize("  "))
        assertTrue(runCatching { WanxiangGithub.normalize("ghp_example\nX-Header: value") }.isFailure)
    }
}
