package org.fcitx.directrime

/**
 * Constructor is the batched JNI boundary; cursor is a UTF-8 byte offset from librime.
 * Display arrays are producer-owned, read-only values and may be shared across snapshots.
 */
class Snapshot(
    val generation: Long,
    val revision: Long,
    val optionsRevision: Long,
    val handled: Boolean,
    val gate: Int,
    val commit: String,
    val preedit: String,
    val schema: String,
    val ascii: Boolean,
    val page: Int,
    val lastPage: Boolean,
    val cursorBytes: Int,
    val candidates: Array<String>,
    val comments: Array<String>,
    val tabs: Array<String>,
    val schemaName: String,
    val highlighted: Int,
    val preview: String,
    val labels: Array<String>,
    /** -1 when not collected; otherwise pre-operation UTF-16 length << 1 plus the command bit. */
    val inputStatistics: Long = -1L,
    /** Changes only when the selectable tab labels or spans change. */
    val tabsRevision: Long = 0L
) {
    companion object {
        /** JNI has compared all display fields. Keep prior snapshots immutable and never replay their commit. */
        @JvmStatic fun reusePresentation(previous: Snapshot, revision: Long, inputStatistics: Long = -1L) = Snapshot(
            previous.generation, revision, previous.optionsRevision, false, previous.gate, "",
            previous.preedit, previous.schema, previous.ascii, previous.page, previous.lastPage,
            previous.cursorBytes, previous.candidates, previous.comments, previous.tabs,
            previous.schemaName, previous.highlighted, previous.preview, previous.labels,
            inputStatistics, previous.tabsRevision
        )
    }

    val rawInputLength: Int get() {
        val length = inputStatistics ushr 1
        return if (inputStatistics >= 0 && length < Int.MAX_VALUE) length.toInt() else -1
    }
    val inputWasCommand: Boolean get() = rawInputLength >= 0 && (inputStatistics and 1L) != 0L
    val inputWasComposing: Boolean get() = rawInputLength > 0

    /** Rime still processes releases; only identical, unhandled presentation updates can be elided. */
    fun isUnchangedRelease(previous: Snapshot?): Boolean = previous != null && !handled &&
        commit.isEmpty() && generation == previous.generation && gate == previous.gate &&
        optionsRevision == previous.optionsRevision && tabsRevision == previous.tabsRevision &&
        preedit == previous.preedit &&
        cursorBytes == previous.cursorBytes && schema == previous.schema &&
        schemaName == previous.schemaName && ascii == previous.ascii && page == previous.page &&
        lastPage == previous.lastPage && highlighted == previous.highlighted && preview == previous.preview &&
        candidates.contentEquals(previous.candidates) && comments.contentEquals(previous.comments) &&
        labels.contentEquals(previous.labels) && tabs.contentEquals(previous.tabs)

    val ready get() = gate == 0
    val cursorUtf16: Int get() {
        var index = 0
        var bytes = 0
        while (index < preedit.length && bytes < cursorBytes) {
            val c = preedit[index]
            val pair = c.isHighSurrogate() && index + 1 < preedit.length && preedit[index + 1].isLowSurrogate()
            val width = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                pair -> 4
                c.isSurrogate() -> 1 // Java UTF-8 encodes an unpaired surrogate as '?'.
                else -> 3
            }
            // Match UTF-8 decoding's replacement character for a truncated final code point.
            if (cursorBytes - bytes < width) return index + 1
            bytes += width
            index += if (pair) 2 else 1
        }
        return index
    }
    val gateMessage get() = when (gate) {
        0 -> if (ascii) "英文" else schema
        1 -> "部署中，按键已拦截"
        2 -> "无可用方案，请导入方案并部署"
        3 -> "会话已过期"
        4 -> "部署失败，请检查方案后重新部署"
        else -> "引擎不可用"
    }
}

/** Read only on explicit caret interaction; ordinary key snapshots do not copy raw input. */
internal class RawInputState(val text: String, val caretBytes: Int)

internal object Native {
    init { System.loadLibrary("direct-rime") }
    /** Select the built-in engine after the current engine has stopped. */
    external fun selectBuiltin()
    /** Configure/select an authorized external engine after the current engine has stopped. */
    external fun configureExternal(fd: Int, entry: String, identity: String): Array<String>
    external fun start(shared: String, user: String, version: String, fullCheck: Boolean = false)
    external fun stop()
    external fun begin(generation: Long, program: String): Snapshot
    external fun beginInput(generation: Long, program: String, noLearning: Boolean): Snapshot
    external fun sessionStats(): LongArray
    // Core/read phases + core thread CPU (ns), faults and context-switch deltas.
    external fun operationTiming(): LongArray
    external fun end(generation: Long): Snapshot
    external fun key(generation: Long, sym: Int, mask: Int, release: Boolean, statistics: Boolean = false): Snapshot
    external fun action(generation: Long, revision: Long, action: Int, index: Int, value: String, statistics: Boolean = false): Snapshot
    external fun rawInput(generation: Long, revision: Long): RawInputState?
    external fun deploy(): Boolean
    external fun schemas(): Array<String>
    external fun schemaEntries(): Array<String>
    external fun options(): Array<String>
    external fun schemaSelection(): String
    external fun selectSchemas(ids: Array<String>): Boolean
    external fun sync(): Boolean
    external fun candidates(generation: Long, offset: Int, limit: Int): Array<String>
}
