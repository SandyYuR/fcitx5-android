package org.fcitx.directrime

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Uses only this APK's sandbox and bundled deterministic schema. */
class DirectRimeSmokeTest {
    @Test fun unchangedReleaseSharesArraysWithoutMutatingEarlierSnapshot(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeRuntime.deploy(context, smoke = true))
        val generation = RimeRuntime.newGeneration()
        RimeRuntime.begin(context, generation, "smoke.reuse")
        RimeRuntime.key(generation, 'n'.code)
        val down = RimeRuntime.key(generation, 'i'.code)!!
        val oldRevision = down.revision
        val up = RimeRuntime.key(generation, 'i'.code, release = true)!!
        assertFalse(up.handled)
        assertNotSame(down, up)
        assertEquals(oldRevision, down.revision)
        assertTrue(up.revision > oldRevision)
        assertTrue(up.commit.isEmpty())
        assertSame(down.candidates, up.candidates)
        assertSame(down.comments, up.comments)
        assertSame(down.labels, up.labels)
        assertSame(down.tabs, up.tabs)
        RimeRuntime.end(generation)
    }

    @Test fun rawCoordinatesAndReadOnlyRefreshKeepInputAndConsumeCommitOnce(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeRuntime.deploy(context, smoke = true))
        val generation = RimeRuntime.newGeneration()
        RimeRuntime.begin(context, generation, "smoke.cursor")
        RimeRuntime.key(generation, 'n'.code)
        val typed = RimeRuntime.key(generation, 'i'.code)!!
        val before = RimeRuntime.rawInput(typed)!!
        assertEquals("ni", before.text)
        assertEquals(2, before.caretBytes)
        val refreshed = RimeRuntime.action(typed, 13)!!
        assertFalse(refreshed.handled)
        val after = RimeRuntime.rawInput(refreshed)!!
        assertEquals(before.text, after.text)
        assertEquals(before.caretBytes, after.caretBytes)
        assertNull(RimeRuntime.rawInput(typed))
        val committed = RimeRuntime.action(refreshed, 1, 0)!!
        assertEquals("你", committed.commit)
        assertTrue(RimeRuntime.action(committed, 13)!!.commit.isEmpty())
        RimeRuntime.end(generation)
    }

    @Test fun deployTypeSelectCommitOnceAndRejectStaleEditor(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeRuntime.deploy(context, smoke = true))
        val generation = RimeRuntime.newGeneration()
        val initial = RimeRuntime.begin(context, generation, "smoke.editor")!!
        assertTrue(initial.gateMessage, initial.ready)
        assertEquals("direct_smoke", initial.schema)
        RimeRuntime.key(generation, 'n'.code)
        val typed = RimeRuntime.key(generation, 'i'.code)!!
        assertTrue(typed.preedit.isNotEmpty())
        assertEquals("你", typed.candidates[0])
        val committed = RimeRuntime.action(typed, 1, 0)!!
        assertEquals("你", committed.commit)
        assertTrue(RimeRuntime.action(committed, 0)!!.commit.isEmpty())
        val later = RimeRuntime.newGeneration()
        RimeRuntime.begin(context, later, "smoke.other-editor")
        assertNull(RimeRuntime.key(generation, 'a'.code))
        assertNull(RimeRuntime.action(typed, 1, 0))
        RimeRuntime.end(later)
    }
    @Test fun pageReleaseAndSupplementaryText(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(RimeRuntime.deploy(context, smoke = true))
        val generation = RimeRuntime.newGeneration()
        RimeRuntime.begin(context, generation, "smoke.editor")
        RimeRuntime.key(generation, 'n'.code)
        val first = RimeRuntime.key(generation, 'i'.code)!!
        assertEquals(3, first.candidates.size)
        val second = RimeRuntime.action(first, 2, 1)!!
        assertEquals(1, second.page)
        assertEquals("逆", second.candidates[0])
        var current = RimeRuntime.action(second, 0)!!
        "xiao".forEach { current = RimeRuntime.key(generation, it.code)!! }
        assertEquals("😀", current.candidates[0])
        val commit = RimeRuntime.action(current, 1, 0)!!
        assertEquals("😀", commit.commit)
        assertTrue(RimeRuntime.key(generation, 'o'.code, release = true)!!.commit.isEmpty())
        RimeRuntime.end(generation)
    }
}
