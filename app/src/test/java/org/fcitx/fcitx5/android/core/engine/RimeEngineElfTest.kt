/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RimeEngineElfTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsSelfContainedRimeLibrary() {
        val file = fixture()
        val info = RimeEngineElf.inspect(file, "variant.so", "arm64-v8a")
        assertEquals("arm64-v8a", info.abi)
        assertEquals("rime_get_api", info.entrySymbol)
        assertEquals("librime.so", info.soname)
        assertEquals(listOf("libc.so"), info.neededLibraries)
        assertEquals(0x4000, info.loadAlignment)
        assertEquals(RimeEngineElf.sha256(file), info.sha256)
    }

    @Test fun rejectsLibraryWithoutRimeEntry() {
        val error = runCatching { RimeEngineElf.inspect(fixture(entry = "other_api"), expectedAbi = "arm64-v8a") }
            .exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("rime_get_api"))
    }

    @Test fun rejectsNonSystemDependencyForSingleFileImport() {
        val error = runCatching {
            RimeEngineElf.inspect(fixture(needed = "libc++_shared.so"), expectedAbi = "arm64-v8a")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("libc++_shared.so"))
    }

    @Test fun rejectsFourKilobyteOnlyAndWritableExecutableLibraries() {
        assertTrue(runCatching {
            RimeEngineElf.inspect(fixture(alignment = 0x1000), expectedAbi = "arm64-v8a")
        }.exceptionOrNull()?.message.orEmpty().contains("16 KiB"))
        assertTrue(runCatching {
            RimeEngineElf.inspect(fixture(loadFlags = 7), expectedAbi = "arm64-v8a")
        }.exceptionOrNull()?.message.orEmpty().contains("可写且可执行"))
    }

    @Test fun rejectsDynamicMetadataThatDoesNotDescribeTheLoadedSegment() {
        val error = runCatching {
            RimeEngineElf.inspect(fixture(dynamicSegmentOffset = 0x700), expectedAbi = "arm64-v8a")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("动态节与加载段不一致"))
    }

    @Test fun rejectsOversizedSymbolAndDynamicTablesBeforeScanningThem() {
        val symbols = runCatching {
            RimeEngineElf.inspect(fixture(declaredSymbolCount = 65_537), expectedAbi = "arm64-v8a")
        }.exceptionOrNull()
        assertTrue(symbols?.message.orEmpty().contains("动态符号表条目过多"))

        val dynamic = runCatching {
            RimeEngineElf.inspect(fixture(declaredDynamicEntries = 4_097), expectedAbi = "arm64-v8a")
        }.exceptionOrNull()
        assertTrue(dynamic?.message.orEmpty().contains("动态链接表条目过多"))
    }

    @Test fun rejectsTooManyNeededLibrariesAndCumulativeStringDecoding() {
        val dependencies = runCatching {
            RimeEngineElf.inspect(fixture(neededCount = 65), expectedAbi = "arm64-v8a")
        }.exceptionOrNull()
        assertTrue(dependencies?.message.orEmpty().contains("动态依赖条目过多"))

        val longName = "x".repeat(200)
        val strings = runCatching {
            RimeEngineElf.inspect(fixture(needed = longName, neededCount = 64), expectedAbi = "arm64-v8a")
        }.exceptionOrNull()
        assertTrue(strings?.message.orEmpty().contains("字符串解码总量过大"))
    }

    @Test fun rejectsPathologicalNoteEntryCounts() {
        val notes = runCatching {
            RimeEngineElf.inspect(fixture(declaredNoteEntries = 4_097), expectedAbi = "arm64-v8a")
        }.exceptionOrNull()
        assertTrue(notes?.message.orEmpty().contains("note 条目过多"))
    }

    private fun fixture(
        entry: String = "rime_get_api",
        needed: String = "libc.so",
        neededCount: Int = 1,
        alignment: Long = 0x4000,
        loadFlags: Int = 5,
        dynamicSegmentOffset: Long = 0x600,
        declaredSymbolCount: Int = 2,
        declaredDynamicEntries: Int = neededCount + 4,
        declaredNoteEntries: Int = 0
    ) = temporary.newFile().apply {
        require(neededCount >= 0 && declaredSymbolCount >= 2 &&
            declaredDynamicEntries >= neededCount + 4 && declaredNoteEntries >= 0)
        val bytes = ByteArray(maxOf(0x2000, 0x500 + declaredSymbolCount * 24,
            0x600 + declaredDynamicEntries * 16, 0x2000 + declaredNoteEntries * 12))
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put(0, 0x7f); data.put(1, 'E'.code.toByte()); data.put(2, 'L'.code.toByte()); data.put(3, 'F'.code.toByte())
        data.put(4, 2); data.put(5, 1); data.put(6, 1)
        data.putShort(16, 3); data.putShort(18, 183.toShort()); data.putInt(20, 1)
        data.putLong(32, 64); data.putLong(40, 0x1000)
        data.putShort(52, 64); data.putShort(54, 56); data.putShort(56, 3)
        data.putShort(58, 64); data.putShort(60, if (declaredNoteEntries > 0) 5 else 4); data.putShort(62, 0)

        // PT_LOAD and a non-executable GNU stack.
        data.putInt(64, 1); data.putInt(68, loadFlags); data.putLong(72, 0); data.putLong(80, 0)
        data.putLong(96, bytes.size.toLong()); data.putLong(104, bytes.size.toLong()); data.putLong(112, alignment)
        val stack = 64 + 56
        data.putInt(stack, 0x6474e551); data.putInt(stack + 4, 6); data.putLong(stack + 48, 16)
        val dynamicSegment = 64 + 56 * 2
        data.putInt(dynamicSegment, 2); data.putInt(dynamicSegment + 4, 6)
        data.putLong(dynamicSegment + 8, dynamicSegmentOffset)
        data.putLong(dynamicSegment + 16, dynamicSegmentOffset)
        val dynamicSize = declaredDynamicEntries.toLong() * 16
        data.putLong(dynamicSegment + 32, dynamicSize); data.putLong(dynamicSegment + 40, dynamicSize)
        data.putLong(dynamicSegment + 48, 8)

        val strings = "\u0000$entry\u0000$needed\u0000librime.so\u0000".toByteArray()
        val entryOffset = 1
        val neededOffset = entryOffset + entry.length + 1
        val sonameOffset = neededOffset + needed.length + 1
        System.arraycopy(strings, 0, bytes, 0x400, strings.size)

        // One undefined symbol followed by the exported API symbol.
        val symbol = 0x500 + 24
        data.putInt(symbol, entryOffset)
        data.put(symbol + 4, 0x12) // STB_GLOBAL | STT_FUNC
        data.putShort(symbol + 6, 1)

        var dynamicCursor = 0x600
        repeat(neededCount) {
            data.putLong(dynamicCursor, 1); data.putLong(dynamicCursor + 8, neededOffset.toLong())
            dynamicCursor += 16
        }
        data.putLong(dynamicCursor, 14); data.putLong(dynamicCursor + 8, sonameOffset.toLong()); dynamicCursor += 16
        data.putLong(dynamicCursor, 5); data.putLong(dynamicCursor + 8, 0x400); dynamicCursor += 16
        data.putLong(dynamicCursor, 10); data.putLong(dynamicCursor + 8, strings.size.toLong()); dynamicCursor += 16
        data.putLong(dynamicCursor, 0); data.putLong(dynamicCursor + 8, 0)

        fun section(index: Int, type: Int, offset: Long, size: Long, link: Int, entrySize: Long) {
            val base = 0x1000 + index * 64
            data.putInt(base + 4, type); data.putLong(base + 24, offset); data.putLong(base + 32, size)
            data.putInt(base + 40, link); data.putLong(base + 56, entrySize)
        }
        section(1, 3, 0x400, strings.size.toLong(), 0, 1)
        section(2, 11, 0x500, declaredSymbolCount.toLong() * 24, 1, 24)
        section(3, 6, 0x600, dynamicSize, 1, 16)
        if (declaredNoteEntries > 0) {
            section(4, 7, 0x2000, declaredNoteEntries.toLong() * 12, 0, 0)
        }
        writeBytes(bytes)
    }
}
