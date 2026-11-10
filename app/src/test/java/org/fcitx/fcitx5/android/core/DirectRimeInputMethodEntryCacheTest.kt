/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class DirectRimeInputMethodEntryCacheTest {
    @Test fun stableChinesePresentationReusesTheEntry() {
        val cache = DirectRimeInputMethodEntryCache()
        val first = cache.get(false, "wanxiang", "万象", "中州鹅")
        val second = cache.get(false, "wanxiang", "万象", "中州鹅")

        assertSame(first, second)
        assertEquals("fcitx-rime:wanxiang", second.subMode.icon)
    }

    @Test fun everyVisibleChineseOrResourceFieldInvalidatesTheEntry() {
        val cache = DirectRimeInputMethodEntryCache()
        val original = cache.get(false, "one", "方案一", "中州鹅")
        val renamedSchema = cache.get(false, "one", "方案甲", "中州鹅")
        val changedSchema = cache.get(false, "two", "方案甲", "中州鹅")
        val changedResource = cache.get(false, "two", "方案甲", "Rime")

        assertNotSame(original, renamedSchema)
        assertNotSame(renamedSchema, changedSchema)
        assertNotSame(changedSchema, changedResource)
        assertEquals("Rime", changedResource.name)
    }

    @Test fun asciiPresentationIgnoresHiddenSchemaButNotModeOrResources() {
        val cache = DirectRimeInputMethodEntryCache()
        val ascii = cache.get(true, "one", "方案一", "中州鹅")
        val hiddenSchemaChanged = cache.get(true, "two", "方案二", "中州鹅")
        val localized = cache.get(true, "two", "方案二", "Rime")
        val chinese = cache.get(false, "two", "方案二", "Rime")

        assertSame(ascii, hiddenSchemaChanged)
        assertNotSame(hiddenSchemaChanged, localized)
        assertNotSame(localized, chinese)
        assertEquals("abc", localized.subMode.label)
        assertEquals("方案二", chinese.subMode.label)
    }
}
