/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.FileUtil
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal class WanxiangEditConflict(message: String) : IllegalStateException(message)

/** Files are discovered from the installed scheme, rather than shipping an updater metadata copy. */
internal object WanxiangAdvancedFiles {
    private val schema = Regex("wanxiang(?:_[a-z0-9]+)*\\.schema\\.yaml")
    private val custom = Regex("wanxiang(?:_[a-z0-9]+)*\\.custom\\.yaml")
    private val dictionary = Regex("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]{0,95}\\.dict\\.yaml")
    fun source(name: String) = name == "default.yaml" || name == "wanxiang_algebra.yaml" || schema.matches(name)
    fun writable(name: String) = '/' !in name && '\\' !in name && (source(name) || name == "default.custom.yaml" || custom.matches(name) || dictionary.matches(name))
    fun patchFile(name: String): String? = when {
        name == "default.yaml" -> "default.custom.yaml"
        schema.matches(name) -> name.removeSuffix(".schema.yaml") + ".custom.yaml"
        else -> null
    }
    fun files(user: File): List<String> {
        val names = user.listFiles().orEmpty().filter { it.isFile && source(it.name) }.map { it.name }.toMutableSet()
        // Rime normally resolves default.yaml from shared data without a user copy.
        if (names.any { schema.matches(it) }) names += "default.yaml"
        return names.sortedWith(compareBy<String> { if (it == "default.yaml") 0 else if (it == "wanxiang.schema.yaml") 1 else 2 }.thenBy { it })
    }

    /** Repair the known missing-build failure on an already deployed Wanxiang profile. */
    fun needsDeploymentRepair(user: File): Boolean = File(user, "installation.yaml").isFile &&
        File(user, "build").isDirectory && !File(user, "build/default.yaml").isFile &&
        user.listFiles().orEmpty().any { it.isFile && schema.matches(it.name) }
    fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        var count = 0L
        while (true) { val n = input.read(buffer); if (n < 0) break; count += n; require(count <= WanxiangYaml.MAX_TEXT); digest.update(buffer, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun read(file: File): String {
        require(file.isFile) { "Configuration is missing: ${file.name}" }
        require(file.length() <= WanxiangYaml.MAX_TEXT) { "Configuration exceeds 2 MiB: ${file.name}" }
        val bytes = ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(bytes.size() + n <= WanxiangYaml.MAX_TEXT); bytes.write(buffer, 0, n) }
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
    private fun watched(root: File): Map<String, String?> {
        val files = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".yaml") && !it.name.endsWith(".dict.yaml") &&
            !it.name.startsWith('.') && it.name !in setOf("user.yaml", "installation.yaml") }
        require(files.size <= 128) { "Too many configuration files" }
        return files.associate { file ->
            val safe = WanxiangPayload.child(root, file.name)
            require(safe.length() <= WanxiangYaml.MAX_TEXT)
            file.name to hash(safe)
        }
    }

    @Serializable data class Snapshot(val file: String, val source: String, val custom: String,
        val effective: String?, val expected: Map<String, String?>)
    @Serializable data class Plan(val profile: String, val path: String, val expected: Map<String, String?>)

    /** Read-only: never call deployment APIs or delete/rebuild live compiled resources here. */
    fun snapshot(user: File, name: String, shared: File? = null): Snapshot {
        require(source(name)) { "Unsupported scheme configuration" }
        val root = user.canonicalFile
        val before = watched(root).toMutableMap()
        val localSource = WanxiangPayload.child(root, name)
        val source = read(if (localSource.isFile) localSource else
            shared?.let { WanxiangPayload.child(it.canonicalFile, name) } ?: localSource)
        if (!localSource.exists()) before[name] = null
        val patch = patchFile(name)
        val custom = patch?.let { WanxiangPayload.child(root, it) }
        if (patch != null && custom?.exists() != true) before[patch] = null
        val customText = custom?.takeIf { it.isFile }?.let(::read).orEmpty()
        val compiledFile = WanxiangPayload.child(root, "build/$name")
        val compiled = if (patch != null && compiledFile.isFile && compiledFile.length() <= WanxiangYaml.MAX_TEXT) read(compiledFile) else null
        val after = watched(root).toMutableMap()
        if (patch != null && custom?.exists() != true) after[patch] = null
        if (!localSource.exists()) after[name] = null
        if (before != after) throw WanxiangEditConflict("Configuration changed while loading. Reload the page.")
        return Snapshot(name, source, customText, compiled, before)
    }

    fun stage(root: File, plan: Plan, changes: Map<String, String>): UUID {
        require(changes.isNotEmpty() && changes.keys.all(::writable))
        val id = UUID.randomUUID()
        val job = WanxiangPayload.child(root.canonicalFile, id.toString())
        check(job.mkdirs())
        try {
            changes.forEach { (name, text) ->
                require(text.length <= WanxiangYaml.MAX_TEXT)
                FileUtil.writeAtomically(WanxiangPayload.child(job, "payload/$name"), text)
            }
            FileUtil.writeAtomically(WanxiangPayload.child(job, "plan.json"), Json.encodeToString(plan))
        } catch (error: Throwable) { FileUtil.removeFile(job); throw error }
        return id
    }

    fun verify(root: File, expected: Map<String, String?>) {
        require(expected.size <= 256)
        expected.forEach { (name, expectedHash) ->
            require('/' !in name && '\\' !in name && name.endsWith(".yaml"))
            val file = WanxiangPayload.child(root.canonicalFile, name)
            if ((if (file.exists()) hash(file) else null) != expectedHash) throw WanxiangEditConflict("配置文件已在其他位置修改，请重新加载：$name")
        }
    }
}
