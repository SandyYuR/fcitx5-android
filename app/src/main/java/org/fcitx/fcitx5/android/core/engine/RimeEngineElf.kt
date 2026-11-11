/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Strict, non-executing validation for a single-file Android Rime engine. */
internal object RimeEngineElf {
    const val MAX_ENGINE_BYTES = 96L * 1024 * 1024
    private const val MIN_LOAD_ALIGNMENT = 16L * 1024
    private val allowedNeeded = setOf(
        "libandroid.so", "libc.so", "libdl.so", "liblog.so", "libm.so", "libz.so"
    )

    fun inspect(file: File, sourceName: String = file.name, expectedAbi: String = processAbi()): RimeEngineStaticInfo {
        require(file.isFile) { "所选文件不存在" }
        val length = file.length()
        require(length in 1..MAX_ENGINE_BYTES) { "引擎文件大小必须在 1–96 MiB 之间" }
        val mapped = RandomAccessFile(file, "r").use { channel ->
            channel.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, length)
        }
        val elf = Elf(mapped)
        require(elf.type == ET_DYN) { "文件不是 ELF 共享库" }
        val abi = machineAbi(elf.machine, elf.bits)
            ?: throw IllegalArgumentException("不支持的 ELF 架构：${elf.machine}")
        require(abi == expectedAbi) { "引擎架构为 $abi，当前输入法进程为 $expectedAbi" }

        val programHeaders = elf.programHeaders()
        val loads = programHeaders.filter { it.type == PT_LOAD }
        require(loads.isNotEmpty()) { "ELF 没有可加载段" }
        loads.forEach {
            require(it.fileSize <= it.memorySize && elf.range(it.offset, it.fileSize)) { "ELF 加载段越界" }
            require(it.align > 1 && it.offset % it.align == it.virtualAddress % it.align) { "ELF 加载段对齐无效" }
            require(it.flags and (PF_WRITE or PF_EXECUTE) != (PF_WRITE or PF_EXECUTE)) { "ELF 含有可写且可执行的加载段" }
        }
        val loadAlignment = loads.minOf(ProgramHeader::align)
        require(loadAlignment >= MIN_LOAD_ALIGNMENT) { "引擎未按 16 KiB 页面对齐" }
        programHeaders.firstOrNull { it.type == PT_GNU_STACK }?.let {
            require(it.flags and PF_EXECUTE == 0L) { "ELF 请求可执行栈" }
        }

        val sections = elf.sections()
        require(sections.isNotEmpty()) { "ELF 缺少用于校验的节表" }
        val dynsym = sections.firstOrNull { it.type == SHT_DYNSYM }
            ?: throw IllegalArgumentException("ELF 缺少动态符号表")
        val dynstr = sections.getOrNull(dynsym.link)
            ?.takeIf { it.type == SHT_STRTAB }
            ?: throw IllegalArgumentException("ELF 动态字符串表无效")
        val symbols = elf.dynamicSymbols(dynsym, dynstr)
        val entry = ENTRY_SYMBOLS.firstOrNull { it in symbols }
            ?: throw IllegalArgumentException("不是可用的 Rime 引擎：缺少 rime_get_api 导出")

        val dynamic = sections.firstOrNull { it.type == SHT_DYNAMIC }
            ?: throw IllegalArgumentException("ELF 缺少动态链接信息")
        val dynamicSegment = programHeaders.singleOrNull { it.type == PT_DYNAMIC }
            ?: throw IllegalArgumentException("ELF 动态加载段无效")
        require(dynamic.offset == dynamicSegment.offset && dynamic.size == dynamicSegment.fileSize) {
            "ELF 动态节与加载段不一致"
        }
        val dynamicStrings = sections.getOrNull(dynamic.link)
            ?.takeIf { it.type == SHT_STRTAB }
            ?: throw IllegalArgumentException("ELF 动态字符串表无效")
        val metadata = elf.dynamicMetadata(dynamic, dynamicStrings, loads)
        require(!metadata.textRelocations) { "ELF 含有文本重定位" }
        require(metadata.rpath.isNullOrBlank() && metadata.runpath.isNullOrBlank()) { "ELF 不得声明 RPATH/RUNPATH" }
        val unexpected = metadata.needed.filterNot { it in allowedNeeded }
        require(unexpected.isEmpty()) {
            "引擎依赖未随单文件提供的动态库：${unexpected.joinToString()}"
        }

        return RimeEngineStaticInfo(
            sha256 = sha256(file),
            size = length,
            abi = abi,
            bits = elf.bits,
            soname = metadata.soname,
            buildId = elf.buildId(sections),
            entrySymbol = entry,
            neededLibraries = metadata.needed.distinct().sorted(),
            loadAlignment = loadAlignment,
            sourceName = sourceName.take(256)
        )
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun processAbi(architecture: String = System.getProperty("os.arch").orEmpty()): String = when {
        architecture.equals("aarch64", true) || architecture.equals("arm64", true) -> "arm64-v8a"
        architecture.startsWith("arm", true) -> "armeabi-v7a"
        architecture.equals("x86_64", true) || architecture.equals("amd64", true) -> "x86_64"
        architecture.contains("86") -> "x86"
        else -> throw IllegalStateException("无法识别当前进程架构：$architecture")
    }

    private fun machineAbi(machine: Int, bits: Int): String? = when (machine to bits) {
        EM_ARM to 32 -> "armeabi-v7a"
        EM_AARCH64 to 64 -> "arm64-v8a"
        EM_386 to 32 -> "x86"
        EM_X86_64 to 64 -> "x86_64"
        else -> null
    }

    private data class ProgramHeader(
        val type: Long,
        val flags: Long,
        val offset: Long,
        val virtualAddress: Long,
        val fileSize: Long,
        val memorySize: Long,
        val align: Long
    )

    private data class Section(
        val type: Long,
        val offset: Long,
        val size: Long,
        val link: Int,
        val entrySize: Long
    )

    private data class DynamicMetadata(
        val needed: List<String>,
        val soname: String?,
        val rpath: String?,
        val runpath: String?,
        val textRelocations: Boolean
    )

    private class Elf(private val bytes: ByteBuffer) {
        val bits: Int
        val type: Int
        val machine: Int
        private val phoff: Long
        private val phentsize: Int
        private val phnum: Int
        private val shoff: Long
        private val shentsize: Int
        private val shnum: Int

        init {
            require(bytes.limit() >= 64 && bytes.get(0) == 0x7f.toByte() &&
                bytes.get(1) == 'E'.code.toByte() && bytes.get(2) == 'L'.code.toByte() &&
                bytes.get(3) == 'F'.code.toByte()) { "文件不是 ELF" }
            bits = when (bytes.get(4).toInt() and 0xff) {
                ELFCLASS32 -> 32
                ELFCLASS64 -> 64
                else -> throw IllegalArgumentException("不支持的 ELF 位数")
            }
            require((bytes.get(5).toInt() and 0xff) == ELFDATA2LSB) { "仅支持 Android 小端 ELF" }
            bytes.order(ByteOrder.LITTLE_ENDIAN)
            type = u16(16)
            machine = u16(18)
            if (bits == 64) {
                phoff = u64(32)
                shoff = u64(40)
                phentsize = u16(54)
                phnum = u16(56)
                shentsize = u16(58)
                shnum = u16(60)
            } else {
                phoff = u32(28)
                shoff = u32(32)
                phentsize = u16(42)
                phnum = u16(44)
                shentsize = u16(46)
                shnum = u16(48)
            }
            require(phnum in 1..4096 && phentsize >= if (bits == 64) 56 else 32) { "ELF 程序头无效" }
            require(shnum in 1..65534 && shentsize >= if (bits == 64) 64 else 40) { "ELF 节表无效" }
            require(tableRange(phoff, phentsize, phnum) && tableRange(shoff, shentsize, shnum)) { "ELF 头表越界" }
        }

        fun range(offset: Long, size: Long): Boolean = offset >= 0 && size >= 0 &&
            offset <= bytes.limit().toLong() && size <= bytes.limit().toLong() - offset

        fun programHeaders(): List<ProgramHeader> = List(phnum) { index ->
            val base = phoff + index.toLong() * phentsize
            if (bits == 64) ProgramHeader(
                type = u32(base), flags = u32(base + 4), offset = u64(base + 8),
                virtualAddress = u64(base + 16), fileSize = u64(base + 32),
                memorySize = u64(base + 40), align = u64(base + 48)
            ) else ProgramHeader(
                type = u32(base), flags = u32(base + 24), offset = u32(base + 4),
                virtualAddress = u32(base + 8), fileSize = u32(base + 16),
                memorySize = u32(base + 20), align = u32(base + 28)
            )
        }

        fun sections(): List<Section> = List(shnum) { index ->
            val base = shoff + index.toLong() * shentsize
            val section = if (bits == 64) Section(
                type = u32(base + 4), offset = u64(base + 24), size = u64(base + 32),
                link = u32(base + 40).toInt(), entrySize = u64(base + 56)
            ) else Section(
                type = u32(base + 4), offset = u32(base + 16), size = u32(base + 20),
                link = u32(base + 24).toInt(), entrySize = u32(base + 36)
            )
            // SHT_NOBITS (notably .bss) occupies memory only; its size is not backed by
            // bytes in the file. Every other section must stay inside the imported file.
            require(section.type == SHT_NOBITS || range(section.offset, section.size)) { "ELF 节区越界" }
            require(section.link in 0 until shnum) { "ELF 节链接越界" }
            section
        }

        fun dynamicSymbols(section: Section, strings: Section): Set<String> {
            val expected = if (bits == 64) 24L else 16L
            val entrySize = section.entrySize.takeIf { it >= expected } ?: expected
            require(section.size % entrySize == 0L && section.size / entrySize <= 1_000_000) { "动态符号表无效" }
            return buildSet {
                repeat((section.size / entrySize).toInt()) { index ->
                    val base = section.offset + index * entrySize
                    val nameOffset = u32(base)
                    val info = u8(base + if (bits == 64) 4 else 12)
                    val sectionIndex = u16(base + if (bits == 64) 6 else 14)
                    val binding = info ushr 4
                    if (nameOffset != 0L && sectionIndex != 0 && binding in setOf(STB_GLOBAL, STB_WEAK)) {
                        add(string(strings, nameOffset))
                    }
                }
            }
        }

        fun dynamicMetadata(section: Section, strings: Section, loads: List<ProgramHeader>): DynamicMetadata {
            val expected = if (bits == 64) 16L else 8L
            val entrySize = section.entrySize.takeIf { it >= expected } ?: expected
            require(section.size % entrySize == 0L && section.size / entrySize <= 65536) { "动态链接表无效" }
            val neededOffsets = mutableListOf<Long>()
            var sonameOffset: Long? = null
            var rpathOffset: Long? = null
            var runpathOffset: Long? = null
            var stringTableAddress: Long? = null
            var stringTableSize: Long? = null
            var textRelocations = false
            repeat((section.size / entrySize).toInt()) { index ->
                val base = section.offset + index * entrySize
                val tag = if (bits == 64) u64(base) else u32(base)
                val value = if (bits == 64) u64(base + 8) else u32(base + 4)
                when (tag) {
                    DT_NEEDED -> neededOffsets += value
                    DT_STRTAB -> stringTableAddress = value
                    DT_STRSZ -> stringTableSize = value
                    DT_SONAME -> sonameOffset = value
                    DT_RPATH -> rpathOffset = value
                    DT_RUNPATH -> runpathOffset = value
                    DT_TEXTREL -> textRelocations = true
                    DT_FLAGS -> if (value and DF_TEXTREL != 0L) textRelocations = true
                }
            }
            val address = requireNotNull(stringTableAddress) { "ELF 缺少 DT_STRTAB" }
            val size = requireNotNull(stringTableSize) { "ELF 缺少 DT_STRSZ" }
            require(size in 1..strings.size) { "ELF 动态字符串表大小无效" }
            val mappedOffset = loads.firstNotNullOfOrNull { load ->
                if (address < load.virtualAddress) null else {
                    val relative = address - load.virtualAddress
                    if (relative <= load.fileSize && size <= load.fileSize - relative) load.offset + relative else null
                }
            }
            require(mappedOffset == strings.offset) { "ELF DT_STRTAB 与动态字符串节不一致" }
            fun text(offset: Long?): String? = offset?.let {
                require(it in 0 until size) { "ELF 动态字符串偏移越界" }
                string(strings, it)
            }
            return DynamicMetadata(
                neededOffsets.map { require(it in 0 until size); string(strings, it) },
                text(sonameOffset), text(rpathOffset), text(runpathOffset), textRelocations
            )
        }

        fun buildId(sections: List<Section>): String? {
            sections.filter { it.type == SHT_NOTE }.forEach { section ->
                var offset = section.offset
                val end = section.offset + section.size
                while (offset + 12 <= end) {
                    val nameSize = u32(offset).toInt()
                    val descriptionSize = u32(offset + 4).toInt()
                    val noteType = u32(offset + 8)
                    val nameOffset = offset + 12
                    val descriptionOffset = align4(nameOffset + nameSize)
                    val next = align4(descriptionOffset + descriptionSize)
                    if (nameSize < 0 || descriptionSize < 0 || next > end || !range(nameOffset, nameSize.toLong()) ||
                        !range(descriptionOffset, descriptionSize.toLong())) break
                    val name = ByteArray(nameSize) { bytes.get((nameOffset + it).toInt()) }
                        .toString(Charsets.US_ASCII).trimEnd('\u0000')
                    if (name == "GNU" && noteType == NT_GNU_BUILD_ID && descriptionSize in 1..128) {
                        return ByteArray(descriptionSize) { bytes.get((descriptionOffset + it).toInt()) }
                            .joinToString("") { "%02x".format(it) }
                    }
                    offset = next
                }
            }
            return null
        }

        private fun string(section: Section, relativeOffset: Long): String {
            require(relativeOffset in 0 until section.size) { "ELF 字符串偏移越界" }
            val start = section.offset + relativeOffset
            val end = section.offset + section.size
            var cursor = start
            while (cursor < end && bytes.get(cursor.toInt()) != 0.toByte()) cursor++
            require(cursor < end && cursor - start <= 4096) { "ELF 字符串无效" }
            return ByteArray((cursor - start).toInt()) { bytes.get((start + it).toInt()) }
                .toString(Charsets.UTF_8)
        }

        private fun u8(offset: Long) = bytes.get(index(offset, 1)).toInt() and 0xff
        private fun u16(offset: Long) = bytes.getShort(index(offset, 2)).toInt() and 0xffff
        private fun u32(offset: Long) = bytes.getInt(index(offset, 4)).toLong() and 0xffffffffL
        private fun u64(offset: Long): Long {
            val value = bytes.getLong(index(offset, 8))
            require(value >= 0) { "ELF 64 位偏移过大" }
            return value
        }
        private fun index(offset: Long, size: Int): Int {
            require(range(offset, size.toLong())) { "ELF 读取越界" }
            return offset.toInt()
        }
        private fun tableRange(offset: Long, entrySize: Int, count: Int): Boolean =
            entrySize > 0 && count > 0 && range(offset, entrySize.toLong() * count)
        private fun align4(value: Long) = (value + 3) and -4L
    }

    private const val ELFCLASS32 = 1
    private const val ELFCLASS64 = 2
    private const val ELFDATA2LSB = 1
    private const val ET_DYN = 3
    private const val EM_386 = 3
    private const val EM_ARM = 40
    private const val EM_X86_64 = 62
    private const val EM_AARCH64 = 183
    private const val PT_LOAD = 1L
    private const val PT_DYNAMIC = 2L
    private const val PT_GNU_STACK = 0x6474e551L
    private const val PF_EXECUTE = 1L
    private const val PF_WRITE = 2L
    private const val SHT_STRTAB = 3L
    private const val SHT_DYNAMIC = 6L
    private const val SHT_NOTE = 7L
    private const val SHT_NOBITS = 8L
    private const val SHT_DYNSYM = 11L
    private const val STB_GLOBAL = 1
    private const val STB_WEAK = 2
    private const val DT_NEEDED = 1L
    private const val DT_STRTAB = 5L
    private const val DT_STRSZ = 10L
    private const val DT_SONAME = 14L
    private const val DT_RPATH = 15L
    private const val DT_TEXTREL = 22L
    private const val DT_FLAGS = 30L
    private const val DT_RUNPATH = 29L
    private const val DF_TEXTREL = 4L
    private const val NT_GNU_BUILD_ID = 3L
    private val ENTRY_SYMBOLS = listOf("rime_get_api", "RimeGetApi")
}
