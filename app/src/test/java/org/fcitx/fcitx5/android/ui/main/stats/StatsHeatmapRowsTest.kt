/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.stats

import org.fcitx.fcitx5.android.data.stats.StatsKeyGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsHeatmapRowsTest {
    private fun key(label: String, x: Float, y: Float, row: Int = -1) = StatsKeyGeometry(label, x, y, .1f, 48f, row)

    private fun labels(geometry: List<StatsKeyGeometry>) = StatsHeatmapRows.rows(geometry).map { row -> row.map { it.label } }

    @Test fun legacyRowsWithSubPixelOffsetStayOneRow() {
        // The nine-key side bar used to add y = 130.9 while the main row was 130.5; exact equality
        // split that into two visible rows.
        val geometry = listOf(key("，", .1f, 130.5f), key("全选", 0f, 130.5f), key("ghi", .2f, 130.9f),
            key("。", .1f, 196f), key("粘贴", 0f, 196.4f))
        assertEquals(listOf(listOf("全选", "，", "ghi"), listOf("粘贴", "。")), labels(geometry))
    }

    @Test fun storedRowsFollowTheRowIndexAndSortKeysLeftToRight() {
        // y is deliberately misleading: rows are ordered by their stored index, not by y.
        val geometry = listOf(key("z", .5f, 500f, 2), key("q", .4f, 0f, 0), key("w", .1f, 300f, 0), key("a", .2f, 0f, 1))
        assertEquals(listOf(listOf("w", "q"), listOf("a"), listOf("z")), labels(geometry))
    }

    @Test fun unnumberedKeysJoinTheClosestStoredRowOrStayOnTheirOwn() {
        val geometry = listOf(key("q", 0f, 40f, 0), key("全选", .8f, 41.5f), key("floating", 0f, 400f))
        assertEquals(listOf(listOf("q", "全选"), listOf("floating")), labels(geometry))
    }

    @Test fun emptyLayoutRendersNoRows() {
        assertEquals(emptyList<List<String>>(), labels(emptyList()))
    }
}
