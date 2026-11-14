/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import org.junit.Assert.*
import org.junit.Test

/** 固定住 Foxy 的 `js.<appId>` 命令解析：布局里的 App 节点靠它找目标。 */
class JsAppCommandTest {
    private fun app(id: String) = JsPluginApp(id, id, emptySet())

    @Test fun parsesTheFoxyCommandForm() {
        assertEquals("rewrite", JsAppCommand.parse("js.rewrite"))
        assertEquals("llm_assistant", JsAppCommand.parse("js.llm_assistant"))
        assertNull(JsAppCommand.parse("rewrite"))
        assertNull(JsAppCommand.parse("js."))
        assertNull(JsAppCommand.parse("js.a/b"))
        assertNull(JsAppCommand.parse("js.中文"))
    }

    @Test fun resolutionRequiresExactlyOneRegisteredApp() {
        val available = listOf("js:llm-assistant" to app("llm_assistant"), "js:rewrite" to app("rewrite"))
        assertEquals(JsAppCommand.Target("js:rewrite", "rewrite"), JsAppCommand.resolve("js.rewrite", available))
        assertNull(JsAppCommand.resolve("js.missing", available))
        assertNull(JsAppCommand.resolve("rewrite", available))
        // 同一 App ID 出现在两个插件里：Foxy 要求全局唯一，这里不猜，交给用户重新绑定。
        assertNull(JsAppCommand.resolve("js.rewrite", available + listOf("js:other" to app("rewrite"))))
    }
}
