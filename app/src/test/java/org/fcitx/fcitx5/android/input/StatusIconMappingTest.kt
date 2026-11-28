/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.DirectRimeInputMethodEntryCache
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusIconMappingTest {
    @Test fun realRimeEntriesSwitchBetweenSchemaAndLatinIcons() {
        val cache = DirectRimeInputMethodEntryCache()
        val schema = cache.get(false, "luna_pinyin", "朙月拼音", "中州鹅")
        assertEquals(R.drawable.ic_status_rime, StatusIconMapping.fromEntry(schema))
        val latin = cache.get(true, "luna_pinyin", "朙月拼音", "中州鹅")
        assertEquals("fcitx-rime", latin.icon)
        assertEquals("fcitx_rime_latin", latin.subMode.icon)
        assertEquals(R.drawable.ic_status_latin_direct, StatusIconMapping.fromEntry(latin))
        assertEquals(R.drawable.ic_status_rime, StatusIconMapping.fromEntry(
            cache.get(false, "luna_pinyin", "朙月拼音", "中州鹅")))
    }
}
