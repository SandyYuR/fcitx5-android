package org.fcitx.fcitx5.android.ui.main.update

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ResumableAssetDownloadTest {
    @get:Rule val temp = TemporaryFolder()
    private fun files() = DownloadFiles(temp.root, UUID.randomUUID()).apply { prepare() }

    @Test fun aFreshInstanceResumesFromAStrongValidatorAndVerifiesTheWholeFile() = runBlocking {
        MockWebServer().use { server ->
            val files = files()
            files.part.writeText("first")
            files.write(DownloadCheckpoint(etag = "\"v1\"", total = 11))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-10/11")
                .setHeader("ETag", "\"v1\"").setBody("second"))
            val asset = ReleaseAsset("fixture.bin", server.url("/asset").toString(), 11, null)
            val reopened = DownloadFiles(temp.root, UUID.fromString(files.directory.name))
            ResumableAssetDownload.run(OkHttpClient(), asset.browserDownloadUrl, asset, reopened) { }
            assertEquals("firstsecond", reopened.part.readText())
            assertTrue(reopened.read().verified)
            assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun aServerIgnoringRangeReplacesThePartialRatherThanAppending() = runBlocking {
        MockWebServer().use { server ->
            val files = files(); files.part.writeText("old-prefix")
            files.write(DownloadCheckpoint(etag = "\"old\""))
            server.enqueue(MockResponse().setHeader("ETag", "\"new\"").setBody("new-content"))
            val asset = ReleaseAsset("fixture.bin", server.url("/asset").toString(), 11, null)
            ResumableAssetDownload.run(OkHttpClient(), asset.browserDownloadUrl, asset, files) { }
            assertEquals("new-content", files.part.readText())
            assertTrue(files.read().verified)
        }
    }

    @Test fun aChecksumMismatchNeverMarksTheFileReadyForPublication() = runBlocking {
        MockWebServer().use { server ->
            val files = files(); server.enqueue(MockResponse().setBody("content"))
            val asset = ReleaseAsset("fixture.bin", server.url("/asset").toString(), 7, "0".repeat(64))
            try {
                ResumableAssetDownload.run(OkHttpClient(), asset.browserDownloadUrl, asset, files) { }
                fail("Must reject checksum mismatch")
            } catch (_: InvalidAssetDownload) { }
            assertFalse(files.read().verified)
            assertNull(files.read().publishedUri)
        }
    }

    @Test fun weakValidatorsNeverEnableAppending() = runBlocking {
        MockWebServer().use { server ->
            val files = files(); files.part.writeText("old")
            files.write(DownloadCheckpoint(etag = "W/\"old\""))
            server.enqueue(MockResponse().setBody("new"))
            val asset = ReleaseAsset("f.bin", server.url("/f").toString(), 3, null)
            ResumableAssetDownload.run(OkHttpClient(), asset.browserDownloadUrl, asset, files) { }
            assertNull(server.takeRequest().getHeader("Range"))
            assertEquals("new", files.part.readText())
        }
    }
    @Test fun changedOrWeakenedRangeValidatorRestartsWithoutMixingVersions() = runBlocking {
        for (etag in listOf("\"new\"", "W/\"old\"")) MockWebServer().use { server ->
            val files = files(); files.part.writeText("old")
            files.write(DownloadCheckpoint(etag = "\"old\"", total = 6))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 3-5/6")
                .setHeader("ETag", etag).setBody("new"))
            val asset = ReleaseAsset("f.bin", server.url("/f").toString(), 6, null)
            try { ResumableAssetDownload.run(OkHttpClient(), asset.browserDownloadUrl, asset, files) { }; fail("Must restart") }
            catch (_: IOException) { }
            assertFalse(files.part.exists()); assertNull(files.read().etag)
            server.takeRequest()
            server.enqueue(MockResponse().setBody("abcdef"))
            ResumableAssetDownload.run(OkHttpClient(), asset.browserDownloadUrl, asset, files) { }
            assertNull(server.takeRequest().getHeader("Range")); assertEquals("abcdef", files.part.readText())
        }
    }
    @Test fun wrongRangesAreRejectedBeforeChangingThePartial() = runBlocking {
        MockWebServer().use { server ->
            val files = files(); files.part.writeText("old")
            files.write(DownloadCheckpoint(etag = "\"old\"", total = 6))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-5/6").setBody("oops"))
            try { ResumableAssetDownload.run(OkHttpClient(), server.url("/f").toString(),
                ReleaseAsset("f.bin", "", 6, null), files) { }; fail("Must reject") }
            catch (_: InvalidAssetDownload) { }
            assertEquals("old", files.part.readText()); assertFalse(files.read().verified)
        }
    }
    @Test fun aRejectedRangeDiscardsOnlyPrivatePartialAndRetriesFromZero() = runBlocking {
        MockWebServer().use { server ->
            val files = files(); files.part.writeText("old"); files.write(DownloadCheckpoint(etag = "\"old\""))
            server.enqueue(MockResponse().setResponseCode(416))
            try { ResumableAssetDownload.run(OkHttpClient(), server.url("/f").toString(),
                ReleaseAsset("f.bin", "", 3, null), files) { }; fail("Must restart") }
            catch (_: IOException) { }
            assertFalse(files.part.exists()); assertNull(files.read().etag)
        }
    }
    @Test fun chunkedUnknownLengthIsBoundedAndVerifiedAtEof() = runBlocking {
        MockWebServer().use { server ->
            val files = files(); server.enqueue(MockResponse().setChunkedBody("unknown-size", 2))
            ResumableAssetDownload.run(OkHttpClient(), server.url("/f").toString(),
                ReleaseAsset("f.bin", "", 0, null), files) { }
            assertEquals(12L, files.read().total); assertTrue(files.read().verified)
        }
    }
    @Test fun oversizedDeclarationsFailWithoutWritingTheBody() = runBlocking {
        MockWebServer().use { server ->
            val files = files()
            server.enqueue(MockResponse().setHeader("Content-Length", DownloadFiles.MAX_BYTES + 1))
            try { ResumableAssetDownload.run(OkHttpClient(), server.url("/f").toString(),
                ReleaseAsset("f.bin", "", 0, null), files) { }; fail("Must reject") }
            catch (_: InvalidAssetDownload) { }
            assertFalse(files.part.exists())
        }
    }
    @Test fun cancellationClosesTheCallbackBeforeReleasingTheFileLock() = runBlocking {
        MockWebServer().use { server ->
            val files = files()
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(100_000) { 65 }))
                .setHeader("ETag", "\"v1\"").throttleBody(1024, 100, TimeUnit.MILLISECONDS))
            val job = launch(Dispatchers.IO) {
                files.locked { ResumableAssetDownload.run(OkHttpClient(), server.url("/f").toString(),
                    ReleaseAsset("f.bin", "", 100_000, null), files) { } }
            }
            withTimeout(5000) { while (files.part.length() < 1024) delay(10) }
            assertFalse(files.tryLocked { fail("Still locked") })
            withTimeout(2000) { job.cancelAndJoin() }
            val finalSize = files.part.length()
            assertTrue(files.tryLocked { })
            delay(200)
            assertEquals(finalSize, files.part.length()); assertFalse(files.read().verified)
        }
    }
    @Test fun cancellationOfSilentHeadersAlsoCompletesPromptly() = runBlocking {
        MockWebServer().use { server ->
            val files = files(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { files.locked {
                ResumableAssetDownload.run(OkHttpClient(), server.url("/f").toString(), ReleaseAsset("f", "", 0, null), files) { }
            } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(files.tryLocked { })
        }
    }
    @Test fun stagingSymlinksAreRejectedWithoutChangingTheirTargets() {
        val foreign = temp.newFile().apply { writeText("foreign") }
        val files = files(); Files.createSymbolicLink(files.part.toPath(), foreign.toPath())
        try { files.removePrivateContent(); fail("Must reject redirected part") } catch (_: IOException) { }
        assertEquals("foreign", foreign.readText())
    }
    @Test fun spaceBudgetReservesRoomForBothPublicationAndUserData() {
        val files = files()
        try { files.requireSpace(8L * 1024 * 1024, 39L * 1024 * 1024); fail("Must preserve reserve") }
        catch (_: InvalidAssetDownload) { }
        files.requireSpace(8L * 1024 * 1024, 40L * 1024 * 1024)
    }
}
