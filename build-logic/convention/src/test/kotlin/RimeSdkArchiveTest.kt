/* SPDX-License-Identifier: LGPL-2.1-or-later */
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RimeSdkArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val abis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    private val commit = "1".repeat(40)
    private val upstream = "2".repeat(40)

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fixture(change: (MutableMap<String, ByteArray>, MutableMap<String, String>) -> Unit = { _, _ -> }): Pair<File, RimeSdkLock> {
        val files = mutableMapOf<String, ByteArray>()
        for (abi in abis) {
            for ((name, archive) in mapOf("librime" to "librime.a", "glog" to "libglog.a", "leveldb" to "libleveldb.a", "lua" to "liblua_static.a", "marisa" to "libmarisa.a", "opencc" to "libopencc.a", "yaml-cpp" to "libyaml-cpp.a")) {
                files["$name/$abi/lib/$archive"] = "$abi/$archive".toByteArray()
            }
            files["librime/$abi/include/rime_api.h"] = "api".toByteArray()
            files["librime/$abi/share/rime/source-manifest.json"] = "{}".toByteArray()
        }
        files["opencc/data/s2t.json"] = "{}".toByteArray()
        files["licenses/librime-LICENSE"] = "license".toByteArray()
        val hashes = files.mapValues { digest(it.value) }.toMutableMap()
        change(files, hashes)
        val manifest = RimeSdkManifest(1, commit, upstream, abis, "3".repeat(64), hashes)
        files["sdk-manifest.json"] = Json.encodeToString(manifest).toByteArray()
        val zip = temporary.newFile()
        ZipOutputStream(zip.outputStream()).use { output ->
            for ((name, bytes) in files) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return zip to RimeSdkLock(1, "SandyYuR/librime", "android-sdk-test", "rime-android-sdk.zip", RimeSdkArchive.sha256(zip), commit, upstream, abis)
    }

    @Test fun verifiedSdkExtractsAllAbisAndSharedData() {
        val (zip, lock) = fixture()
        val output = temporary.newFolder()
        RimeSdkArchive.extract(zip, output, lock)
        for (abi in abis) assertTrue(File(output, "librime/$abi/lib/librime.a").isFile)
        assertTrue(File(output, "opencc/data/s2t.json").isFile)
    }

    @Test fun corruptedArchiveIsRejectedBeforeExtraction() {
        val (zip, lock) = fixture()
        val output = temporary.newFolder()
        zip.appendText("corrupt")
        assertThrows(IllegalArgumentException::class.java) { RimeSdkArchive.extract(zip, output, lock) }
        assertTrue(output.listFiles()!!.isEmpty())
    }

    @Test fun validZipFromAnotherSourceCommitIsRejected() {
        val (zip, lock) = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            RimeSdkArchive.extract(zip, temporary.newFolder(), lock.copy(librime_commit = "4".repeat(40)))
        }
    }

    @Test fun zipTraversalAndWindowsPathsAreRejected() {
        for (name in listOf("../escaped", "/absolute", "C:/absolute", "licenses/../escaped", "licenses\\escaped")) {
            val (zip, lock) = fixture { files, hashes -> files[name] = byteArrayOf(1); hashes[name] = digest(byteArrayOf(1)) }
            val output = temporary.newFolder()
            assertThrows(name, IllegalArgumentException::class.java) { RimeSdkArchive.extract(zip, output, lock) }
            assertTrue(output.listFiles()!!.isEmpty())
        }
    }

    @Test fun unlistedFilesAndUnexpectedComponentsAreRejected() {
        for (listed in listOf(false, true)) {
            val (zip, lock) = fixture { files, hashes ->
                files["fcitx/libfcitx.a"] = byteArrayOf(1)
                if (listed) hashes["fcitx/libfcitx.a"] = digest(byteArrayOf(1))
            }
            assertThrows(IllegalArgumentException::class.java) { RimeSdkArchive.extract(zip, temporary.newFolder(), lock) }
        }
    }

    @Test fun incompleteAbiAndTamperedInnerLibraryAreRejected() {
        val missing = "leveldb/x86/lib/libleveldb.a"
        val cases: List<(MutableMap<String, ByteArray>, MutableMap<String, String>) -> Unit> = listOf(
            { files, hashes -> files.remove(missing); hashes.remove(missing) },
            { files, _ -> files[missing] = "changed".toByteArray() }
        )
        for (change in cases) {
            val (zip, lock) = fixture(change)
            assertThrows(IllegalArgumentException::class.java) { RimeSdkArchive.extract(zip, temporary.newFolder(), lock) }
        }
    }

    @Test fun offlineGradleTaskUsesVerifiedCacheAndRepairsChangedOutputs() {
        val (zip, lock) = fixture()
        val project = temporary.newFolder()
        File(project, "settings.gradle").writeText("rootProject.name = 'sdk-fixture'")
        File(project, "build.gradle").writeText("""
            plugins { id 'org.fcitx.fcitx5.android.rime-sdk' }
            prepareRimeSdk {
                cacheDirectory.set(layout.projectDirectory.dir('cache'))
                offline.set(true)
            }
        """.trimIndent())
        File(project, "native/rime-sdk.lock.json").apply {
            parentFile.mkdirs()
            writeText(Json.encodeToString(lock))
        }
        val cached = File(project, "cache/${lock.sha256}.zip").apply { parentFile.mkdirs() }
        zip.copyTo(cached)
        fun runner() = GradleRunner.create().withProjectDir(project).withPluginClasspath()
            .withArguments("prepareRimeSdk", "--offline", "--max-workers=1")
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":prepareRimeSdk")!!.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, runner().build().task(":prepareRimeSdk")!!.outcome)
        val output = File(project, "native/rime-sdk/librime/x86/lib/librime.a")
        val original = output.readText()
        output.writeText("damaged")
        assertEquals(TaskOutcome.SUCCESS, runner().build().task(":prepareRimeSdk")!!.outcome)
        assertEquals(original, output.readText())
        cached.delete()
        output.writeText("changed again")
        assertTrue(runner().buildAndFail().output.contains("is not cached"))
    }
}
