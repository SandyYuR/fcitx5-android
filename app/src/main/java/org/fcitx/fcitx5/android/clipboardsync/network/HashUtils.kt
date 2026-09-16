package org.fcitx.fcitx5.android.clipboardsync.network

import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

object HashUtils {

    /**
     * 流式哈希的固定缓冲区大小（8KB）。
     *
     * 上传完成后记录 `size + hash` 曾把整个文件（上限 32MB）读进堆，
     * 用固定缓冲区逐段喂 [MessageDigest]，峰值内存就与文件大小无关。
     */
    private const val STREAM_BUFFER_SIZE = 8 * 1024

    fun sha256(text: String): String =
        sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHexString()

    /**
     * 单遍流式读取 [input]，同时返回读到的字节数与 SHA-256。
     *
     * 输出格式与 [sha256] 完全一致：两者共用同一个 [toHexString]（小写、每字节两位、
     * 共 64 字符），保证与历史持久化的记录逐字节可比，跨重启去重不会失配。
     * 与 `readBytes()` 一样不关闭 [input]，由调用方负责关闭。
     */
    fun sha256AndSize(input: InputStream): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            total += read
        }
        return total to digest.digest().toHexString()
    }

    fun calculateFileHash(fileName: String, bytes: ByteArray): String {
        val normalizedFileName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { fileName }
        val contentHash = sha256(bytes).uppercase(Locale.ROOT)
        return sha256("$normalizedFileName|$contentHash")
    }

    /**
     * 十六进制小写编码，与旧实现 `joinToString("") { "%02x".format(it) }` 逐字符等价。
     *
     * 单独抽出来是为了让 [sha256] 与 [sha256AndSize] 走同一条编码路径——只要格式只由
     * 一处定义，流式结果就不可能和旧的整块结果出现大小写或位宽差异。
     */
    private fun ByteArray.toHexString(): String {
        val chars = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            chars[index * 2] = HEX_DIGITS[value ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return String(chars)
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
