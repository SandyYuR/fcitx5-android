package org.fcitx.fcitx5.android.core

import org.fcitx.directrime.Snapshot
import org.junit.Assert.*
import org.junit.Test

class ReleaseSnapshotTest {
    private fun snapshot(handled: Boolean = false, generation: Long = 1, revision: Long = 1,
                         optionRevision: Long = 1, commit: String = "", ascii: Boolean = false,
                         candidates: Array<String> = arrayOf("你"), tabs: Array<String> = emptyArray()) =
        Snapshot(generation, revision, optionRevision, handled, 0, commit, "ni", "test", ascii,
            0, true, 2, candidates, arrayOf(""), tabs, "测试", 0, "你", arrayOf("1"))

    @Test fun cursorConversionMatchesUtf8DecodingIncludingTruncatedCodePoints() {
        for (text in listOf("", "abc", "汉é🙂𐀀x", "a\uD800b\uDC00c")) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            for (offset in -1..bytes.size + 2) {
                val snapshot = Snapshot(1, 1, 1, false, 0, "", text, "", false, 0, true,
                    offset, emptyArray(), emptyArray(), emptyArray(), "", 0, "", emptyArray())
                assertEquals("$text at $offset", String(bytes, 0, offset.coerceIn(0, bytes.size), Charsets.UTF_8).length,
                    snapshot.cursorUtf16)
            }
        }
    }

    @Test fun unchangedReleaseMayAdvanceRevisionWithoutRepackingPresentation() {
        assertTrue(snapshot(revision = 2).isUnchangedRelease(snapshot(commit = "previous commit")))
    }

    @Test fun modifierSwitchCommitsLazyCandidateInvalidationAndNewEditorsArePreserved() {
        val old = snapshot()
        assertFalse(snapshot(ascii = true).isUnchangedRelease(old))
        assertFalse(snapshot(commit = "你").isUnchangedRelease(old))
        assertFalse(snapshot(handled = true).isUnchangedRelease(old))
        assertFalse(snapshot(optionRevision = 2).isUnchangedRelease(old))
        assertFalse(snapshot(generation = 2).isUnchangedRelease(old))
        assertFalse(snapshot(candidates = arrayOf("泥")).isUnchangedRelease(old))
        assertFalse(snapshot(tabs = arrayOf("ni")).isUnchangedRelease(old))
    }
}
