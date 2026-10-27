/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated, bounded records around the existing ZIP. Passwords are never persisted. */
internal object ProtectedBackup {
    private val magic = "FCRIMEBK".toByteArray(Charsets.US_ASCII)
    private const val HEADER_SIZE = 42
    private const val ITERATIONS = 1_300_000
    private const val CHUNK = 64 * 1024
    private const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    fun isProtected(input: InputStream): Boolean {
        val prefix = ByteArray(magic.size)
        return try { DataInputStream(input).readFully(prefix); prefix.contentEquals(magic) }
        catch (_: EOFException) { false }
    }

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray,
                checkActive: () -> Unit = {}) {
        require(password.isNotEmpty())
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                write(magic); writeByte(1); writeByte(1); writeInt(ITERATIONS); write(salt); write(nonce)
            }
        }.toByteArray()
        val key = derive(password, salt, ITERATIONS)
        val destination = DataOutputStream(output)
        val buffer = ByteArray(CHUNK)
        var sequence = 0L
        var total = 0L
        try {
            destination.write(header)
            while (true) {
                checkActive()
                var count = 0
                while (count < CHUNK) {
                    checkActive()
                    val n = input.read(buffer, count, CHUNK - count)
                    if (n < 0) break
                    count += n
                }
                total += count
                if (total > MAX_BYTES) throw IOException("Backup exceeds the supported size.")
                val last = count == 0
                val encrypted = record(Cipher.ENCRYPT_MODE, key, nonce, header, sequence++, count, last)
                    .doFinal(buffer, 0, count)
                destination.writeInt(count); destination.writeBoolean(last); destination.write(encrypted)
                if (last) break
            }
        } finally { key.fill(0); buffer.fill(0) }
    }

    /** No unauthenticated ZIP reaches the importer. Failed/truncated imports delete their temp. */
    fun decrypt(input: InputStream, cacheDir: File, password: CharArray,
                maxBytes: Long = MAX_BYTES, checkActive: () -> Unit = {}): File {
        val source = DataInputStream(input)
        val header = ByteArray(HEADER_SIZE).also(source::readFully)
        val data = DataInputStream(ByteArrayInputStream(header))
        val prefix = ByteArray(8).also(data::readFully)
        if (!prefix.contentEquals(magic) || data.readUnsignedByte() != 1 || data.readUnsignedByte() != 1)
            throw IOException("Unsupported protected backup format.")
        val iterations = data.readInt()
        if (iterations !in 100_000..2_000_000) throw IOException("Invalid backup key derivation parameters.")
        val salt = ByteArray(16).also(data::readFully)
        val nonce = ByteArray(12).also(data::readFully)
        checkActive()
        val key = derive(password, salt, iterations)
        var temporary: File? = null
        try {
            temporary = File.createTempFile("protected-backup-import-", ".zip", cacheDir)
            FileOutputStream(temporary).use { output ->
                var total = 0L
                var sequence = 0L
                while (true) {
                    checkActive()
                    val count = source.readInt()
                    val flag = source.readUnsignedByte()
                    if (count !in 0..CHUNK || flag !in 0..1 || (flag == 1) != (count == 0))
                        throw IOException("Invalid protected backup record.")
                    total += count
                    if (total > maxBytes) throw IOException("Backup exceeds the supported size.")
                    val bytes = ByteArray(count + 16).also(source::readFully)
                    val plain = record(Cipher.DECRYPT_MODE, key, nonce, header, sequence++, count, flag == 1)
                        .doFinal(bytes)
                    try { output.write(plain) } finally { plain.fill(0) }
                    if (flag == 1) {
                        if (source.read() != -1) throw IOException("Unexpected data after protected backup.")
                        break
                    }
                }
                output.fd.sync()
            }
            return temporary
        } catch (e: Throwable) {
            temporary?.delete()
            throw e
        } finally { key.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        // PBKDF2-HMAC-SHA1 is available on API 23; use an appropriate work factor.
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun record(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray,
                       sequence: Long, count: Int, last: Boolean): Cipher {
        val iv = nonce.clone()
        for (i in 0..7) iv[iv.lastIndex - i] = (iv[iv.lastIndex - i].toInt() xor (sequence ushr (8 * i)).toInt()).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(13).putLong(sequence).putInt(count).put(if (last) 1 else 0).array())
        }
    }
}
