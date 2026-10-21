package org.fcitx.directrime

/** Constructor is the batched JNI boundary; cursor is a UTF-8 byte offset from librime. */
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
    val labels: Array<String>
) {
    val ready get() = gate == 0
    val cursorUtf16 get() = preedit.toByteArray(Charsets.UTF_8).let {
        String(it, 0, cursorBytes.coerceIn(0, it.size), Charsets.UTF_8).length
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

internal object Native {
    init { System.loadLibrary("direct-rime") }
    external fun start(shared: String, user: String, version: String, fullCheck: Boolean = false)
    external fun stop()
    external fun begin(generation: Long, program: String): Snapshot
    external fun beginInput(generation: Long, program: String, noLearning: Boolean): Snapshot
    external fun sessionStats(): LongArray
    // Core/read phases + core thread CPU (ns), faults and context-switch deltas.
    external fun operationTiming(): LongArray
    external fun end(generation: Long): Snapshot
    external fun key(generation: Long, sym: Int, mask: Int, release: Boolean): Snapshot
    external fun action(generation: Long, revision: Long, action: Int, index: Int, value: String): Snapshot
    external fun deploy(): Boolean
    external fun schemas(): Array<String>
    external fun schemaEntries(): Array<String>
    external fun options(): Array<String>
    external fun schemaSelection(): String
    external fun selectSchemas(ids: Array<String>): Boolean
    external fun sync(): Boolean
    external fun candidates(generation: Long, offset: Int, limit: Int): Array<String>
}
