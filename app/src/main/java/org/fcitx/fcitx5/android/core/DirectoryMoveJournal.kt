/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.fcitx.fcitx5.android.utils.FileUtil
import org.fcitx.fcitx5.android.utils.readBounded
import java.io.File

/** Restore a directory move interrupted between rename and metadata replacement. */
internal class DirectoryMoveJournal(private val dataRoot: File, private val metadata: File) {
    private val file = File(metadata.parentFile, "rime-directory-operation.json")
    @Serializable private data class Move(val source: String, val target: String, val deleteOnCommit: Boolean, val metadataAfter: String)

    fun prepare(source: String, target: String, deleteOnCommit: Boolean, metadataAfter: String) {
        recover()
        val move = Move(source, target, deleteOnCommit, metadataAfter)
        val (from, to) = paths(move)
        check(from.exists() && !to.exists()) { "方案目录不存在或目标已存在" }
        FileUtil.writeAtomically(file, Json.encodeToString(move))
        if (!from.renameTo(to)) {
            recover()
            error("无法移动方案目录")
        }
    }

    /** The metadata is the commit marker. Recovery is idempotent in either direction. */
    fun recover() {
        if (!file.exists()) return
        val move = Json.decodeFromString<Move>(file.inputStream().use { it.readBounded(4 * 1024 * 1024).decodeToString() })
        val (from, to) = paths(move)
        val committed = metadata.isFile && metadata.readText() == move.metadataAfter
        if (committed) {
            if (move.deleteOnCommit) FileUtil.removeFile(to).getOrThrow()
            else check(to.isDirectory) { "方案改名恢复失败，数据目录缺失" }
        } else if (to.exists()) {
            check(!from.exists() && to.renameTo(from)) { "方案目录恢复发生冲突，数据保留在 ${to.path}" }
        } else check(from.isDirectory) { "方案目录恢复失败，数据目录缺失" }
        check(file.delete()) { "无法清理方案目录恢复日志" }
    }

    private fun paths(move: Move): Pair<File, File> {
        require(RimeDataDirectoryState.isValidName(move.source))
        require(if (move.deleteOnCommit) move.target.matches(Regex("\\.deleted-[0-9a-f-]{36}"))
            else RimeDataDirectoryState.isValidName(move.target))
        require(move.source != move.target)
        val root = dataRoot.canonicalFile
        return File(root, move.source) to File(root, move.target)
    }
}
