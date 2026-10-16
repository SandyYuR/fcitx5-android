package org.fxrime.direct

/*
 * RimeSnapshot — native 侧 Object[] 打包状态的 Kotlin 侧解包。
 *
 * 槽位编号必须与 native/rime_bridge.c 的常量表逐一对齐（那边的注释也写了「改这里必须同步改这里」）。
 * native 一次性把 commit + context + status 打包进一个 Object[]，Kotlin 侧只做解包，
 * 不再逐个字段回调 JNI —— 这是「批量 JNI」判断成立的前提：一次按键的 JNI 往返是常数次，
 * 不随候选条数增长。
 *
 * 只依赖 kotlin-stdlib，不 import android.*，因此可以在 JVM 上编译与单测。
 */
object RimeSnapshotSlots {
    const val DISPATCH = 0
    const val COMMIT = 1
    const val PREEDIT = 2
    const val CARET = 3
    const val SEL_START = 4
    const val SEL_END = 5
    const val PAGE_NO = 6
    const val PAGE_SIZE = 7
    const val HIGHLIGHTED = 8
    const val LAST_PAGE = 9
    const val SELECT_KEYS = 10
    const val PREVIEW = 11
    const val SCHEMA_ID = 12
    const val SCHEMA_NAME = 13
    const val STATUS_FLAGS = 14
    const val COUNT = 15
    const val CAND_BASE = 16

    /* 与 native 的 dr_dispatch 枚举同值 */
    const val DISPATCH_TO_RIME = 0
    const val DISPATCH_SWALLOW = 1
    const val DISPATCH_DROP = 2
    /* 进了 librime 但它不处理（process_key 返回 false）：按键必须落回编辑器，
     * 既不能吞也不能当空状态——否则用户按了键没有任何反应。 */
    const val DISPATCH_PASSTHROUGH = 3

    /* status 位压缩，与 native pack_state 的 flags 位序一致 */
    const val FLAG_DISABLED = 1 shl 0
    const val FLAG_COMPOSING = 1 shl 1
    const val FLAG_ASCII_MODE = 1 shl 2
    const val FLAG_FULL_SHAPE = 1 shl 3
    const val FLAG_SIMPLIFIED = 1 shl 4
    const val FLAG_TRADITIONAL = 1 shl 5
    const val FLAG_ASCII_PUNCT = 1 shl 6
}

data class RimeCandidate(val text: String, val comment: String?)

data class RimeSnapshot(
    val dispatch: Int,
    val commitText: String?,
    val preedit: String?,
    val caretPos: Int,
    val selStart: Int,
    val selEnd: Int,
    val pageNo: Int,
    val pageSize: Int,
    val highlightedIndex: Int,
    val isLastPage: Boolean,
    val selectKeys: String?,
    val commitPreview: String?,
    val schemaId: String?,
    val schemaName: String?,
    val statusFlags: Int,
    val candidates: List<RimeCandidate>
) {
    val composing: Boolean get() = statusFlags and RimeSnapshotSlots.FLAG_COMPOSING != 0
    val asciiMode: Boolean get() = statusFlags and RimeSnapshotSlots.FLAG_ASCII_MODE != 0
    val disabled: Boolean get() = statusFlags and RimeSnapshotSlots.FLAG_DISABLED != 0

    /** 这次按键是否被引擎前的门控吞掉（部署期 / 无 session）。吞掉时仍返回完整 shape。 */
    val swallowed: Boolean get() = dispatch == RimeSnapshotSlots.DISPATCH_SWALLOW

    fun flagNames(): List<String> {
        val out = mutableListOf<String>()
        if (disabled) out += "disabled"
        if (composing) out += "composing"
        if (asciiMode) out += "ascii_mode"
        if (statusFlags and RimeSnapshotSlots.FLAG_FULL_SHAPE != 0) out += "full_shape"
        if (statusFlags and RimeSnapshotSlots.FLAG_SIMPLIFIED != 0) out += "simplified"
        if (statusFlags and RimeSnapshotSlots.FLAG_TRADITIONAL != 0) out += "traditional"
        if (statusFlags and RimeSnapshotSlots.FLAG_ASCII_PUNCT != 0) out += "ascii_punct"
        return out
    }

    companion object {
        /**
         * 从 native 返回的 Object[] 解包。数组为 null（native 出错）时返回 null，
         * 调用方不得把 null 当成「空状态」——两者语义不同，见 DeployKeyGate。
         */
        fun fromObjectArray(raw: Array<Any>?): RimeSnapshot? {
            if (raw == null) return null
            if (raw.size < RimeSnapshotSlots.CAND_BASE) return null

            fun i(slot: Int): Int = (raw[slot] as? Int) ?: 0
            fun s(slot: Int): String? = raw[slot] as? String

            val count = i(RimeSnapshotSlots.COUNT)
            val cands = ArrayList<RimeCandidate>(count)
            var p = RimeSnapshotSlots.CAND_BASE
            repeat(count) {
                if (p + 1 < raw.size) {
                    cands += RimeCandidate(
                        text = raw[p] as? String ?: "",
                        comment = raw[p + 1] as? String
                    )
                }
                p += 2
            }

            return RimeSnapshot(
                dispatch = i(RimeSnapshotSlots.DISPATCH),
                commitText = s(RimeSnapshotSlots.COMMIT),
                preedit = s(RimeSnapshotSlots.PREEDIT),
                caretPos = i(RimeSnapshotSlots.CARET),
                selStart = i(RimeSnapshotSlots.SEL_START),
                selEnd = i(RimeSnapshotSlots.SEL_END),
                pageNo = i(RimeSnapshotSlots.PAGE_NO),
                pageSize = i(RimeSnapshotSlots.PAGE_SIZE),
                highlightedIndex = i(RimeSnapshotSlots.HIGHLIGHTED),
                isLastPage = i(RimeSnapshotSlots.LAST_PAGE) != 0,
                selectKeys = s(RimeSnapshotSlots.SELECT_KEYS),
                commitPreview = s(RimeSnapshotSlots.PREVIEW),
                schemaId = s(RimeSnapshotSlots.SCHEMA_ID),
                schemaName = s(RimeSnapshotSlots.SCHEMA_NAME),
                statusFlags = i(RimeSnapshotSlots.STATUS_FLAGS),
                candidates = cands
            )
        }
    }
}
