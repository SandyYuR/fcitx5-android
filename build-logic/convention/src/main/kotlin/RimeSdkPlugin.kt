/* SPDX-License-Identifier: LGPL-2.1-or-later */
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import java.io.File
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipFile

@Serializable
internal data class RimeSdkLock(
    val schema_version: Int,
    val repository: String,
    val tag: String,
    val asset: String,
    val sha256: String,
    val librime_commit: String,
    val upstream_commit: String,
    val abis: List<String>
) {
    fun validate() {
        require(schema_version == 1) { "Unsupported Rime SDK lock version" }
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        require(tag.matches(Regex("android-sdk-[A-Za-z0-9_.-]+")))
        require(asset == "rime-android-sdk.zip")
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(listOf(librime_commit, upstream_commit).all { it.matches(Regex("[0-9a-f]{40}")) })
        require(abis == listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
    }

    val url get() = "https://github.com/$repository/releases/download/$tag/$asset"
}

@Serializable
internal data class RimeSdkManifest(
    val schema_version: Int,
    val librime_commit: String,
    val upstream_commit: String,
    val abis: List<String>,
    val sources_lock_sha256: String,
    val files: Map<String, String>,
    val development: Boolean = false,
    val source_tree: String? = null
)

internal object RimeSdkArchive {
    private val libraries = mapOf(
        "librime" to "librime.a", "glog" to "libglog.a", "leveldb" to "libleveldb.a",
        "lua" to "liblua_static.a", "marisa" to "libmarisa.a", "opencc" to "libopencc.a",
        "yaml-cpp" to "libyaml-cpp.a"
    )

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val length = input.read(buffer)
                if (length < 0) break
                digest.update(buffer, 0, length)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun extract(archive: File, destination: File, lock: RimeSdkLock) {
        lock.validate()
        require(sha256(archive) == lock.sha256) { "Rime SDK archive checksum mismatch" }
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = entries.map { it.name }
            require(names.size == names.toSet().size) { "Duplicate SDK file" }
            require(names.all { name ->
                '\\' !in name && ':' !in name && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
            }) { "Invalid SDK path" }
            val manifestEntry = zip.getEntry("sdk-manifest.json") ?: error("Missing SDK manifest")
            val manifest = zip.getInputStream(manifestEntry).use {
                Json.decodeFromString<RimeSdkManifest>(it.reader().readText())
            }
            require(!manifest.development) { "A development SDK cannot replace a pinned release" }
            require(manifest.schema_version == 1 && manifest.librime_commit == lock.librime_commit &&
                manifest.upstream_commit == lock.upstream_commit && manifest.abis == lock.abis) {
                "Rime SDK source/version does not match the lock"
            }
            require(names.toSet() == manifest.files.keys + "sdk-manifest.json") { "SDK file list mismatch" }
            require(manifest.files.keys.all { it.substringBefore('/') in libraries.keys + "licenses" }) {
                "Unexpected SDK component"
            }
            for (abi in lock.abis) {
                for ((library, filename) in libraries) {
                    require("$library/$abi/lib/$filename" in manifest.files) { "Missing $abi/$filename" }
                }
                require("librime/$abi/include/rime_api.h" in manifest.files)
                require("librime/$abi/share/rime/source-manifest.json" in manifest.files)
            }
            require("opencc/data/s2t.json" in manifest.files && "licenses/librime-LICENSE" in manifest.files)
            destination.mkdirs()
            for (entry in entries) {
                require(!entry.isDirectory) { "Unexpected SDK directory entry" }
                val target = File(destination, entry.name)
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                if (entry.name != "sdk-manifest.json") {
                    require(sha256(target) == manifest.files[entry.name]) { "SDK file checksum mismatch: ${entry.name}" }
                }
            }
        }
    }

    fun copyDevelopment(source: File, destination: File, requiredAbis: List<String>) {
        val root = source.toPath().toRealPath()
        val manifestFile = File(source, "sdk-manifest.json")
        val manifest = Json.decodeFromString<RimeSdkManifest>(manifestFile.readText())
        require(manifest.schema_version == 1 && manifest.development &&
            manifest.source_tree?.matches(Regex("[0-9a-f]{40}")) == true &&
            listOf(manifest.librime_commit, manifest.upstream_commit).all { it.matches(Regex("[0-9a-f]{40}")) } &&
            manifest.sources_lock_sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid development SDK provenance" }
        require(manifest.abis.isNotEmpty() && manifest.abis.distinct() == manifest.abis &&
            manifest.abis.all { it in Versions.supportedABIs } && manifest.abis.containsAll(requiredAbis)) {
            "Development SDK does not contain the requested ABIs: $requiredAbis"
        }
        for (name in manifest.files.keys) {
            require('\\' !in name && ':' !in name && name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) {
                "Invalid development SDK path"
            }
            require(name.substringBefore('/') in libraries.keys + "licenses") { "Unexpected development SDK component" }
        }
        for (abi in manifest.abis) {
            for ((library, filename) in libraries) require("$library/$abi/lib/$filename" in manifest.files)
            require("librime/$abi/include/rime_api.h" in manifest.files)
            require("librime/$abi/share/rime/source-manifest.json" in manifest.files)
        }
        require("opencc/data/s2t.json" in manifest.files && "licenses/librime-LICENSE" in manifest.files)
        for ((name, digest) in manifest.files) {
            val input = File(source, name)
            require(input.toPath().toRealPath().startsWith(root)) { "Development SDK link escapes its directory" }
            val output = File(destination, name)
            output.parentFile.mkdirs()
            input.inputStream().use { stream -> output.outputStream().use { stream.copyTo(it) } }
            require(sha256(output) == digest) { "Development SDK checksum mismatch: $name" }
        }
        // Serialize the manifest that was validated, even if the producer updates its directory.
        File(destination, "sdk-manifest.json").writeText(Json.encodeToString(manifest))
    }
}

abstract class PrepareRimeSdkTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    @get:OutputDirectory
    abstract val sdkDirectory: DirectoryProperty

    @get:Internal
    abstract val cacheDirectory: DirectoryProperty

    @get:Input
    abstract val offline: Property<Boolean>

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localSdkDirectory: DirectoryProperty

    @get:Input
    abstract val requiredAbis: ListProperty<String>

    @TaskAction
    fun prepare() {
        val lock = Json.decodeFromString<RimeSdkLock>(lockFile.get().asFile.readText())
        lock.validate()
        val destination = sdkDirectory.get().asFile
        destination.parentFile.mkdirs()
        // Serialize with other Gradle processes sharing this generated SDK.
        FileChannel.open(File(destination.parentFile, ".rime-sdk.lock").toPath(),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (localSdkDirectory.isPresent) {
                    val source = localSdkDirectory.get().asFile
                    require(source.canonicalFile != destination.canonicalFile) { "Local SDK must be separate from generated native/rime-sdk" }
                    val staging = Files.createTempDirectory(destination.parentFile.toPath(), ".rime-sdk-").toFile()
                    try {
                        RimeSdkArchive.copyDevelopment(source, staging, requiredAbis.get())
                        destination.deleteRecursively()
                        Files.move(staging.toPath(), destination.toPath())
                        logger.lifecycle("Prepared local development Rime SDK from $source")
                    } finally {
                        staging.deleteRecursively()
                    }
                    return
                }
                val cache = cacheDirectory.get().asFile.apply { mkdirs() }
                val archive = File(cache, "${lock.sha256}.zip")
                if (!archive.isFile || RimeSdkArchive.sha256(archive) != lock.sha256) {
                    check(!offline.get()) { "Rime SDK ${lock.tag} is not cached; run prepareRimeSdk once online" }
                    val download = Files.createTempFile(cache.toPath(), "download-", ".zip").toFile()
                    try {
                        logger.lifecycle("Downloading Rime SDK ${lock.tag}")
                        val connection = URI(lock.url).toURL().openConnection().apply {
                            connectTimeout = 30000
                            readTimeout = 120000
                        }
                        connection.getInputStream().use { input -> download.outputStream().use { input.copyTo(it) } }
                        require(RimeSdkArchive.sha256(download) == lock.sha256) { "Downloaded Rime SDK checksum mismatch" }
                        Files.move(download.toPath(), archive.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        download.delete()
                    }
                }
                val staging = Files.createTempDirectory(destination.parentFile.toPath(), ".rime-sdk-").toFile()
                try {
                    RimeSdkArchive.extract(archive, staging, lock)
                    destination.deleteRecursively()
                    Files.move(staging.toPath(), destination.toPath())
                } finally {
                    staging.deleteRecursively()
                }
            }
        }
    }
}

class RimeSdkPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "Apply rime-sdk only to the root project" }
        val localSdk = target.providers.gradleProperty("localRimeSdkDir")
        target.gradle.taskGraph.whenReady {
            check(!localSdk.isPresent || allTasks.none { it.name.contains("Release", ignoreCase = true) }) {
                "localRimeSdkDir is restricted to Debug builds; release builds must use the pinned SDK"
            }
        }
        target.tasks.register<PrepareRimeSdkTask>("prepareRimeSdk") {
            group = "build setup"
            description = "Download and verify the pinned Android Rime SDK"
            lockFile.set(target.layout.projectDirectory.file("native/rime-sdk.lock.json"))
            sdkDirectory.set(target.layout.projectDirectory.dir("native/rime-sdk"))
            cacheDirectory.set(File(target.gradle.gradleUserHomeDir, "caches/rime-sdk"))
            offline.set(target.gradle.startParameter.isOffline)
            requiredAbis.set(selectBuildAbis(target.buildAbiOverride, Versions.supportedABIs).toList())
            if (localSdk.isPresent) localSdkDirectory.set(target.file(localSdk.get()))
        }
    }
}
