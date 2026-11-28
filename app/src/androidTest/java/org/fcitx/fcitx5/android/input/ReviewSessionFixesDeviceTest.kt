/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.RimeDataDirectoryState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.candidates.floating.FloatingCandidatesMode
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.input.cursor.CursorTracker
import org.fcitx.fcitx5.android.ui.main.settings.im.RimeImportActivity
import org.fcitx.fcitx5.android.core.DirectRimeConfig
import org.fcitx.fcitx5.android.core.FcitxLifecycle
import org.fcitx.fcitx5.android.core.InputEngine
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.RawConfig
import org.fcitx.fcitx5.android.core.engine.RimeEngineKind
import org.fcitx.fcitx5.android.core.engine.RimeEngineState
import org.fcitx.fcitx5.android.core.engine.RimeEngineStore
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.utils.FilePathGuard
import org.fcitx.fcitx5.android.utils.FileUtil
import org.fcitx.fcitx5.android.utils.IniCodec
import org.fcitx.fcitx5.android.utils.readBounded
import org.junit.Assert.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real editor/service-queue regressions on the expected engine, with an isolated smoke profile transaction. */
class ReviewSessionFixesDeviceTest {
    companion object {
        private var smokeProfile: SmokeProfile? = null

        @JvmStatic @BeforeClass fun prepareSmokeProfile() {
            val profile = SmokeProfile()
            // JUnit invokes AfterClass even if preparation fails partway through.
            smokeProfile = profile
            profile.prepare()
        }

        @JvmStatic @AfterClass fun restoreSmokeProfile() {
            smokeProfile?.restore()
        }
    }

    /** No daemon connection is opened until the owned profile transaction has been prepared. */
    private class SmokeProfile {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val repository get() = RimeEngineStore.repository(context)
        private val expectedEngineKind = when (InstrumentationRegistry.getArguments().getString("expectedEngineKind")) {
            "BUILTIN" -> RimeEngineKind.BUILTIN
            "PLUGIN" -> RimeEngineKind.PLUGIN
            else -> error("Pass expectedEngineKind=BUILTIN or PLUGIN explicitly")
        }
        private val profileName = "review-session-smoke-" + UUID.randomUUID()
        private val directory = File(context.cacheDir, profileName).canonicalFile
        private var directoryCreated = false
        private var transactionStarted = false
        private var directoryRecord: OriginalFile? = null
        private var engineRecord: OriginalFile? = null
        private var originalState: RimeEngineState? = null
        private var originalUserFiles = emptyList<OriginalDigest>()

        private data class OriginalFile(val file: File, val bytes: ByteArray?) {
            fun restore() {
                if (bytes == null) {
                    check(!file.exists() || file.delete()) { "Could not restore record absence" }
                } else {
                    FileUtil.writeAtomically(file) { it.write(bytes) }
                }
                check(if (bytes == null) !file.exists() else file.isFile && file.readBytes().contentEquals(bytes)) {
                    "Persistent record bytes or existence were not restored"
                }
            }

            companion object {
                fun capture(file: File): OriginalFile {
                    check(!file.exists() || file.isFile) { "Persistent record is not a regular file" }
                    return OriginalFile(file, if (file.exists()) file.readBytes() else null)
                }
            }
        }

        private data class OriginalDigest(val file: File, val existed: Boolean, val length: Long, val digest: String) {
            fun verify() {
                check(capture(file) == this) {
                    "An original user file changed during the isolated smoke fixture"
                }
            }

            companion object {
                private data class ShellResult(val status: Int, val stdout: String, val stderr: String)

                private fun pathArgument(file: File): String = file.absolutePath.also { path ->
                    require(path.length <= 2048 && Regex("/[A-Za-z0-9._/-]+").matches(path) &&
                        path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }) {
                        "Original file path must be one absolute shell-safe argument"
                    }
                }

                private fun probe(tool: String, path: String): ShellResult {
                    check(Build.VERSION.SDK_INT >= 34) { "Read-only stderr capture requires API 34" }
                    val command = when (tool) {
                        "stat" -> "/system/bin/stat -c %f:%s $path"
                        "sha256sum" -> "/system/bin/sha256sum $path"
                        else -> error("Unsupported read-only original-file probe")
                    }
                    // Runtime.exec does not parse shell quotes. Feed a fixed script to sh stdin instead.
                    val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
                        .executeShellCommandRwe("/system/bin/sh")
                    val readers = Executors.newFixedThreadPool(2)
                    try {
                        check(pipes.size == 3) { "Read-only shell did not return all three streams" }
                        val stdout = readers.submit<String> {
                            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use {
                                it.readBounded(8192).toString(Charsets.UTF_8)
                            }
                        }
                        val stderr = readers.submit<String> {
                            ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).use {
                                it.readBounded(8192).toString(Charsets.UTF_8)
                            }
                        }
                        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { input ->
                            val script = command + "\nprobe_status=\$?\n" +
                                "printf '\\nREVIEW_PROBE_RC=%d\\n' \"\$probe_status\"\nexit \"\$probe_status\"\n"
                            input.write(script.toByteArray(Charsets.UTF_8))
                        }
                        val output = stdout.get(10, TimeUnit.SECONDS)
                        val error = stderr.get(10, TimeUnit.SECONDS)
                        val marker = Regex("\\nREVIEW_PROBE_RC=([0-9]{1,3})\\n").find(output)
                        check(marker != null && marker.range.last == output.lastIndex) {
                            "Read-only shell exit status was not completely returned"
                        }
                        val status = marker.groupValues[1].toInt()
                        check(status in 0..255) { "Read-only shell returned an invalid exit status" }
                        return ShellResult(status, output.substring(0, marker.range.first), error)
                    } finally {
                        pipes.forEach { runCatching { it.close() } }
                        readers.shutdownNow()
                    }
                }

                private fun metadata(path: String): Pair<Int, Long>? {
                    val result = probe("stat", path)
                    if (result.status == 1 && result.stdout.isEmpty() &&
                        result.stderr == "stat: '$path': No such file or directory\n") return null
                    check(result.status == 0 && result.stderr.isEmpty()) {
                        "Original file stat failed; absence is accepted only for explicit ENOENT"
                    }
                    val match = Regex("([0-9a-fA-F]{4,8}):(0|[1-9][0-9]*)\\n").matchEntire(result.stdout)
                    checkNotNull(match) { "Original file stat output was not completely parsed" }
                    val mode = match.groupValues[1].toIntOrNull(16)
                    val size = match.groupValues[2].toLongOrNull()
                    check(mode != null && (mode and 0xf000) == 0x8000 && size != null) {
                        "Original user file is not a regular file with a valid size"
                    }
                    return mode to size
                }

                fun capture(file: File): OriginalDigest {
                    val path = pathArgument(file)
                    val before = metadata(path) ?: return OriginalDigest(file, false, 0L, "")
                    val hash = probe("sha256sum", path)
                    check(hash.status == 0 && hash.stderr.isEmpty()) { "Original file SHA-256 failed" }
                    val match = Regex("([0-9a-f]{64})  " + Regex.escape(path) + "\\n").matchEntire(hash.stdout)
                    checkNotNull(match) { "Original file SHA-256 output was not completely parsed" }
                    check(metadata(path) == before) { "Original file metadata changed while it was hashed" }
                    return OriginalDigest(file, true, before.second, match.groupValues[1])
                }
            }
        }

        // Read-only access avoids initializing an unused native owner for a precondition or teardown.
        private fun daemonEngine(): Lazy<*> = FcitxDaemon::class.java.getDeclaredField("engineDelegate")
            .apply { isAccessible = true }.get(null) as Lazy<*>

        fun prepare() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Prepare smoke files off Main" }
            check(FcitxDaemon.getFirstConnectionOrNull() == null && !daemonEngine().isInitialized()) {
                "Run this class in a fresh instrumentation process before any daemon connection"
            }
            val config = DirectRimeConfig(context)
            val before = repository.state()
            check(before.active.kind == expectedEngineKind && before.activation == null) {
                "The explicitly expected engine must already be selected and idle"
            }
            val boundProfile = if (expectedEngineKind == RimeEngineKind.PLUGIN) {
                check(before.plugins.count { it.packageName == before.active.id } == 1) {
                    "The existing authorized plugin slot must remain unique"
                }
                checkNotNull(repository.activeExternalProfile())
            } else null
            val savedDirectory = OriginalFile.capture(File(config.root, RimeDataDirectoryState.CONFIG_PATH))
            val savedEngine = OriginalFile.capture(File(context.noBackupFilesDir, "rime-engines/state.json"))
            directoryRecord = savedDirectory
            engineRecord = savedEngine
            originalState = before
            // Decode the captured bytes only. config.load() also recovers/migrates user data.
            val raw = IniCodec.decode(savedDirectory.bytes?.toString(Charsets.UTF_8).orEmpty())
            val names = raw.findByName("Profiles")?.value.orEmpty().lines()
                .filter(RimeDataDirectoryState::isValidName).distinct().ifEmpty { listOf("rime") }
            val current = raw.findByName("Current")?.value?.takeIf { it in names } ?: names.first()
            val encoded = raw.findByName("ExternalPathsV2")?.value?.takeIf { it.isNotBlank() }
            val legacy = raw.findByName("ExternalPaths")?.value?.takeIf { it.isNotBlank() }
            val external = if (encoded != null) RimeDataDirectoryState.decodePaths(encoded, "", names)
                else if (legacy != null) RimeDataDirectoryState.decodePaths(null, legacy, names) else emptyMap()
            val paths = names.associateWith { external[it]?.let(::File) ?: File(config.dataRoot, it) }
            check((boundProfile == null || boundProfile in paths) && profileName !in paths) {
                "The original bound profile must be registered and the smoke profile must be new"
            }
            val islandPath = directory.path + File.separator
            check(paths.values.none {
                val original = it.canonicalFile
                original == directory || original.path.startsWith(islandPath) ||
                    directory.path.startsWith(original.path + File.separator)
            }) { "The owned island must not overlap any original profile" }
            originalUserFiles = listOfNotNull(current, boundProfile).map { paths.getValue(it).canonicalFile }.distinct()
                .flatMap { user -> listOf("installation.yaml", "build/default.yaml", "user.yaml")
                    .map { OriginalDigest.capture(File(user, it)) } }
            // A normal Service load must not replay a pre-existing global move or prototype migration.
            check(!File(savedDirectory.file.parentFile, "rime-directory-operation.json").exists()) {
                "Complete the pending user directory operation before running this isolated fixture"
            }
            check(!File(context.filesDir, "rime/user").isDirectory ||
                File(config.root, "config/direct-rime-frontend-migrated").exists()) {
                "Complete the pending user prototype migration before running this isolated fixture"
            }
            transactionStarted = true
            check(!directory.exists() && directory.mkdir()) { "Refuse to reuse a smoke directory" }
            directoryCreated = true
            for (name in listOf("default.yaml", "direct_smoke.schema.yaml", "direct_smoke.dict.yaml")) {
                val target = File(directory, name)
                check(target.createNewFile()) { "Refuse to overwrite a smoke asset" }
                instrumentation.context.assets.open("smoke/" + name).use { source ->
                    FileOutputStream(target).use { output ->
                        source.copyTo(output)
                        if (name == "direct_smoke.schema.yaml") {
                            // EVO builds dictionaries only when the schema declares them explicitly.
                            output.write("\nbuild:\n  dictionaries:\n    direct_smoke: {}\n".toByteArray(Charsets.UTF_8))
                        }
                        output.fd.sync()
                    }
                }
            }
            repository.profileMutationLease().use {
                val registered = paths + (profileName to directory)
                val next = RimeDataDirectoryState(profileName, names + profileName,
                    registered.mapValues { it.value.absolutePath })
                val externalPaths = registered.filter { (name, file) ->
                    file.absolutePath != File(config.dataRoot, name).absolutePath
                }.mapValues { it.value.absolutePath }
                val temporary = next.portableConfig().substringBefore("ExternalPaths=") +
                    RimeDataDirectoryState.externalPathConfig(externalPaths)
                // Current must point at the island before normal Service loading can recover data.
                FileUtil.writeAtomically(savedDirectory.file, temporary)
                if (expectedEngineKind == RimeEngineKind.PLUGIN) {
                    check(repository.updateProfile(before.active, profileName)) { "The original plugin slot is no longer active" }
                } else {
                    check(repository.state() == before) { "The built-in fixture must not change engine metadata" }
                }
            }
            verifySelectedSlot()
            originalUserFiles.forEach(OriginalDigest::verify)
            Log.i("ReviewSessionFixes", "smoke_prepared records=2 assets=3 original_files=" + originalUserFiles.size +
                " current_is_island=true same_engine=true expected_engine_kind=" + expectedEngineKind.name)
        }

        fun requireCompiledArtifacts() {
            val table = File(directory, "build/direct_smoke.table.bin")
            val prism = File(directory, "build/direct_smoke.prism.bin")
            for (file in listOf(table, prism)) {
                check(!FilePathGuard.isRedirected(file) && file.isFile && file.length() > 0L) {
                    "The isolated smoke deployment did not generate a nonempty regular " + file.name
                }
            }
            Log.i("ReviewSessionFixes", "smoke_artifacts table_bytes=" + table.length() + " prism_bytes=" + prism.length())
        }

        fun verifySelectedSlot() {
            val before = checkNotNull(originalState)
            val state = repository.state()
            if (expectedEngineKind == RimeEngineKind.BUILTIN) {
                check(state == before) { "The built-in fixture must preserve the complete engine metadata" }
            } else {
                check(state.active == before.active && state.imported == before.imported &&
                    state.plugins == before.plugins.map {
                        if (it.packageName == before.active.id) it.copy(profile = profileName) else it
                    }) { "The smoke fixture must preserve the selected plugin and every authorization" }
            }
        }

        fun restore() {
            if (!transactionStarted) return
            check(Looper.myLooper() != Looper.getMainLooper()) { "Stop the native owner off Main" }
            // A failure here deliberately leaves both records and the UUID island for root recovery.
            FcitxDaemon.shutdownFcitx()
            val engine = daemonEngine()
            if (engine.isInitialized()) {
                val owner = engine.value as InputEngine
                check(owner.lifecycle.currentState == FcitxLifecycle.State.STOPPED && !owner.isReady && !owner.acceptsInput) {
                    "Native owner did not reach a completed stop; preserve the fixture for recovery"
                }
            }
            Log.i("ReviewSessionFixes", "smoke_stopped owner_stopped=true late_restart_blocked=true")
            val failures = mutableListOf<Throwable>()
            fun restorePart(name: String, action: () -> Unit) {
                try { action() } catch (error: Throwable) { failures += AssertionError("Smoke restoration failed: " + name, error) }
            }
            restorePart("directory record") { checkNotNull(directoryRecord).restore() }
            restorePart("engine record") { checkNotNull(engineRecord).restore() }
            restorePart("selected plugin and all authorizations") {
                check(repository.state() == checkNotNull(originalState)) { "Original engine metadata was not restored" }
            }
            originalUserFiles.forEach { original -> restorePart("original user file") { original.verify() } }
            // Keep evidence if restoration failed; never delete an island still referenced by a record.
            if (failures.isEmpty() && directoryCreated) restorePart("owned smoke directory") { deleteOwned(directory) }
            if (failures.isNotEmpty()) {
                val error = AssertionError("Smoke fixture had " + failures.size + " restoration failures")
                failures.forEach(error::addSuppressed)
                throw error
            }
            Log.i("ReviewSessionFixes", "smoke_restored records=2 original_files=" + originalUserFiles.size +
                " metadata_equal=true owned_cache_absent=" + !directory.exists())
        }

        private fun deleteOwned(file: File) {
            check(file.absolutePath == directory.absolutePath ||
                file.absolutePath.startsWith(directory.absolutePath + File.separator)) { "Refuse cleanup outside the owned island" }
            if (!FilePathGuard.isLeafRedirected(file) && file.isDirectory) {
                checkNotNull(file.listFiles()) { "Cannot enumerate an owned smoke directory" }.forEach(::deleteOwned)
            }
            check(file.delete() || (!file.exists() && !FilePathGuard.isLeafRedirected(file))) { "Could not remove an owned smoke entry" }
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val selectionField = FcitxInputMethodService::class.java.getDeclaredField("selection")
        .apply { isAccessible = true }

    private data class Fixture(
        val activity: RimeImportActivity,
        val editor: EditText,
        val service: FcitxInputMethodService,
        val imm: InputMethodManager
    )

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> mainValue(block: () -> T): T {
        val value = AtomicReference<T>()
        main { value.set(block()) }
        return value.get()
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { input -> input.readText().trim() }
    }
    // UiAutomation uses Runtime.exec(String), whose tokenization preserves shell quote characters.
    private fun imeComponentArgument(value: String): String {
        val pattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:[.][A-Za-z_][A-Za-z0-9_]*)*/[.]?[A-Za-z_$][A-Za-z0-9_$]*(?:[.][A-Za-z_$][A-Za-z0-9_$]*)*")
        require(pattern.matches(value) && ComponentName.unflattenFromString(value) != null) {
            "IME component must be a single valid whitespace-free package/class argument"
        }
        return value
    }
    private fun enabledImes(): Set<ComponentName> = shell("ime list -s").lineSequence()
        .mapNotNull(ComponentName::unflattenFromString).toSet()
    private fun await(message: String, block: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (mainValue(block)) return
            SystemClock.sleep(30)
        }
        fail(message)
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun inputView(): InputView? = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::descendants).filterIsInstance<InputView>()
        .firstOrNull { it.isShown && it.service.isInputViewShown }
    private fun tracker(service: FcitxInputMethodService) = selectionField.get(service) as CursorTracker
    private fun clipContents(clip: ClipData?): List<String?>? = clip?.let {
        buildList {
            add(it.description.label?.toString())
            add(it.description.mimeTypeCount.toString()); add(it.itemCount.toString())
            repeat(it.description.mimeTypeCount) { index -> add(it.description.getMimeType(index)) }
            repeat(it.itemCount) { index ->
                val item = it.getItemAt(index)
                add(item.text?.toString()); add(item.htmlText); add(item.uri?.toString()); add(item.intent?.toUri(0))
            }
        }
    }

    private fun assertSettled(fixture: Fixture, expected: Int) {
        await("Editor and production cursor state should confirm UTF-16 position $expected") {
            val selection = tracker(fixture.service)
            fixture.editor.selectionStart == expected && fixture.editor.selectionEnd == expected &&
                selection.current.start == expected && selection.current.end == expected &&
                selection.latest.start == expected && selection.latest.end == expected
        }
        main {
            assertEquals("A😀B", fixture.editor.text.toString())
            assertTrue(fixture.editor.hasFocus())
            assertTrue(fixture.imm.isActive(fixture.editor))
            assertNull("Use the ordinary application InputConnection", fixture.service.jsAppInputConnection)
            assertNotNull(fixture.service.applicationInputConnection)
            assertEquals(fixture.editor.id, fixture.service.currentInputEditorInfo.fieldId)
        }
    }

    private suspend fun arrows(fixture: Fixture, vararg names: String) {
        val jobs = mutableListOf<Job>()
        main {
            names.forEach { key ->
                jobs += fixture.service.postFcitxKeyJob { sendKey(key, KeyState.Virtual.state) }
            }
        }
        withTimeout(15_000) { jobs.joinAll() }
    }

    @Test fun ordinaryEditorArrowsMoveAcrossAnEntireSurrogatePair(): Unit = runBlocking {
        withEditor { fixture ->
            main { fixture.editor.setSelection(0) }
            assertSettled(fixture, 0)
            for (expected in listOf(1, 3, 4)) {
                arrows(fixture, "Right")
                assertSettled(fixture, expected)
            }
            for (expected in listOf(3, 1, 0)) {
                arrows(fixture, "Left")
                assertSettled(fixture, expected)
            }
        }
    }

    @Test fun rapidArrowsAtDocumentEdgesDoNotLeavePhantomPredictions(): Unit = runBlocking {
        withEditor { fixture ->
            assertSettled(fixture, 4)
            arrows(fixture, *(List(12) { "Right" } + "Left").toTypedArray())
            assertSettled(fixture, 3)
            arrows(fixture, "Left", "Left")
            assertSettled(fixture, 0)
            arrows(fixture, *(List(12) { "Left" } + "Right").toTypedArray())
            assertSettled(fixture, 1)
            arrows(fixture, "Right", "Right", "Right", "Left", "Left", "Left", "Right")
            assertSettled(fixture, 1)
        }
    }

    @Test fun queuedCandidateCharacterCannotCommitAfterARealEditorSwitch(): Unit = runBlocking {
        withEditor { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocker = mainValue {
                fixture.service.postFcitxJob { entered.complete(Unit); release.await() }
            }
            var candidate: Job? = null
            var replacement: EditText? = null
            try {
                withTimeout(10_000) { entered.await() }
                val originalToken = mainValue { checkNotNull(fixture.service.editorSession.capture()) }
                main {
                    candidate = fixture.service.commitCandidateCharacter("旧")
                    assertFalse("Candidate must be admitted behind the suspended real queue job", candidate!!.isCompleted)
                    replacement = EditText(fixture.activity).apply {
                        id = View.generateViewId()
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        setText("replacement")
                        setSelection(length())
                    }
                    fixture.activity.findViewById<FrameLayout>(android.R.id.content).addView(replacement,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                    assertTrue(replacement!!.requestFocus())
                    fixture.imm.showSoftInput(replacement, InputMethodManager.SHOW_IMPLICIT)
                }
                await("The actual new editor must invalidate the captured editor token before queue delivery") {
                    val next = checkNotNull(replacement)
                    next.hasFocus() && fixture.imm.isActive(next) && !originalToken.isCurrent() &&
                        fixture.service.currentInputEditorInfo?.fieldId == next.id &&
                        fixture.service.applicationInputConnection != null && fixture.service.jsAppInputConnection == null
                }
            } finally {
                release.complete(Unit)
                withTimeout(15_000) { blocker.join(); candidate?.join() }
            }
            instrumentation.waitForIdleSync()
            main {
                assertEquals("A😀B", fixture.editor.text.toString())
                assertEquals("replacement", checkNotNull(replacement).text.toString())
                assertTrue(checkNotNull(replacement).hasFocus())
            }
        }
    }

    @Test fun selectedPluginCommitsSmokeCandidateThroughTheActualEditor(): Unit = runBlocking {
        withEditor { fixture ->
            main { fixture.editor.setText(""); fixture.editor.setSelection(0) }
            await("The actual empty editor and production tracker must confirm the starting caret") {
                val selection = tracker(fixture.service)
                fixture.editor.length() == 0 && fixture.editor.selectionStart == 0 && fixture.editor.selectionEnd == 0 &&
                    selection.current.start == 0 && selection.current.end == 0 &&
                    selection.latest.start == 0 && selection.latest.end == 0
            }
            // These keys traverse the selected plugin's native session and the production commit path.
            arrows(fixture, "n", "i", "space")
            await("The native smoke candidate must reach the actual editor with a confirmed caret") {
                val selection = tracker(fixture.service)
                fixture.editor.text.toString() == "你" && fixture.editor.selectionStart == 1 && fixture.editor.selectionEnd == 1 &&
                    selection.current.start == 1 && selection.current.end == 1 &&
                    selection.latest.start == 1 && selection.latest.end == 1
            }
            main {
                assertEquals("你", fixture.editor.text.toString())
                assertTrue(fixture.editor.hasFocus())
                assertTrue(fixture.imm.isActive(fixture.editor))
                assertNull("Use the ordinary application InputConnection", fixture.service.jsAppInputConnection)
                assertNotNull(fixture.service.applicationInputConnection)
                assertEquals(fixture.editor.id, fixture.service.currentInputEditorInfo.fieldId)
            }
            checkNotNull(smokeProfile).verifySelectedSlot()
        }
    }

    private data class SmokeBinding(val ready: Boolean, val accepts: Boolean, val schemaMatches: Boolean, val generationValid: Boolean)

    private suspend fun requireSmokeBinding(fixture: Fixture) {
        val status = AtomicReference<Result<SmokeBinding>>()
        val job = mainValue {
            fixture.service.postFcitxJob {
                status.set(runCatching {
                    val previousGeneration = inputSession
                    setAddonSubConfig("rime", "deploy", RawConfig())
                    check(inputSession > previousGeneration) { "Real deployment did not replace the input generation" }
                    checkNotNull(smokeProfile).requireCompiledArtifacts()
                    val schema = getAddonSubConfig("rime", "current-schema").findByName("Schema")?.value
                    SmokeBinding(isReady, acceptsInput, schema == "direct_smoke", inputSession > 0L)
                })
            }
        }
        withTimeout(30_000) { job.join() }
        val value = checkNotNull(status.get()) { "The real service queue did not execute its smoke deployment/readiness check" }.getOrThrow()
        assertTrue("Smoke binding ready=" + value.ready + " acceptsInput=" + value.accepts +
            " current_schema_matches=" + value.schemaMatches + " generation_valid=" + value.generationValid,
            value.ready && value.accepts && value.schemaMatches && value.generationValid)
        checkNotNull(smokeProfile).verifySelectedSlot()
        Log.i("ReviewSessionFixes", "smoke_binding ready=true accepts_input=true current_schema_matches=true same_plugin=true")
    }

    private suspend fun withEditor(block: suspend (Fixture) -> Unit) {
        val prefs = AppPrefs.getInstance()
        val oldLayout = mainValue { prefs.keyboard.textKeyboardLayoutProfile.getValue() }
        val oldFloating = mainValue { prefs.candidates.mode.getValue() }
        val oldListening = mainValue { prefs.clipboard.clipboardListening.getValue() }
        val previousIme = shell("settings get secure default_input_method")
        val previousHard = shell("settings get secure show_ime_with_hard_keyboard")
        require(previousHard == "null" || previousHard == "0" || previousHard == "1") {
            "Unexpected show_ime_with_hard_keyboard setting (expected null, 0 or 1)"
        }
        if (previousIme.isNotBlank() && previousIme != "null") imeComponentArgument(previousIme)
        val previousEnabled = enabledImes()
        val component = ComponentName(context, FcitxInputMethodService::class.java)
        val componentId = imeComponentArgument(component.flattenToString())
        val profile = "review-session-${UUID.randomUUID()}"
        val layout = checkNotNull(UserConfigFiles.textKeyboardLayoutJson(profile))
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var activity: RimeImportActivity? = null
        var activeFixture: Fixture? = null
        var oldClip: ClipData? = null
        var oldClipContents: List<String?>? = null
        var clipboardCaptured = false
        var createdLayout = false
        var testFailure: Throwable? = null
        try {
            check(layout.parentFile!!.isDirectory || layout.parentFile!!.mkdirs())
            check(layout.createNewFile()) { "Refuse to overwrite a pre-existing test layout" }
            createdLayout = true
            context.assets.open("bundled/keyboard_layouts/TextKeyboardLayout.普蓝.json").use { source ->
                layout.outputStream().use { destination -> source.copyTo(destination) }
            }
            main {
                prefs.clipboard.clipboardListening.setValue(false)
                prefs.candidates.mode.setValue(FloatingCandidatesMode.Disabled)
                prefs.keyboard.textKeyboardLayoutProfile.setValue(profile)
            }
            shell("settings put secure show_ime_with_hard_keyboard 1")
            assertEquals("Hardware keyboard IME visibility must be applied", "1",
                shell("settings get secure show_ime_with_hard_keyboard"))
            shell("ime enable $componentId")
            assertTrue("The requested input method must be enabled", component in enabledImes())
            shell("ime set $componentId")
            assertEquals("The requested input method must be selected", component,
                ComponentName.unflattenFromString(shell("settings get secure default_input_method")))
            activity = instrumentation.startActivitySync(Intent(context, RimeImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RimeImportActivity
            val host = checkNotNull(activity)
            val editor = host.findViewById<EditText>(R.id.direct_rime_test_editor)
            await("The disposable editor activity should be in the foreground") { host.hasWindowFocus() }
            main {
                oldClip = clipboard.primaryClip?.let(::ClipData)
                oldClipContents = clipContents(oldClip)
                clipboardCaptured = true
                editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                editor.setText("A😀B")
                editor.setSelection(editor.length())
                assertTrue(editor.requestFocus())
                imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
            var service: FcitxInputMethodService? = null
            await("The real IME should bind the ordinary application editor") {
                val current = inputView()?.service
                val ready = current != null && editor.hasFocus() && imm.isActive(editor) &&
                    current.currentInputEditorInfo?.fieldId == editor.id &&
                    current.applicationInputConnection != null && current.jsAppInputConnection == null &&
                    current.editorSession.capture() != null
                if (ready) service = current
                ready
            }
            val fixture = Fixture(host, editor, checkNotNull(service), imm)
            activeFixture = fixture
            requireSmokeBinding(fixture)
            assertSettled(fixture, 4)
            block(fixture)
        } catch (error: Throwable) {
            testFailure = error
            activeFixture?.let { failed ->
                try {
                    val observation = mainValue {
                        val text = failed.editor.text.toString()
                        val selection = tracker(failed.service)
                        "smoke_failure editor_is_candidate=" + (text == "你") + " editor_is_raw_ni=" + (text == "ni") +
                            " editor_is_original=" + (text == "A😀B") + " editor_length=" + text.length +
                            " selection_start=" + failed.editor.selectionStart + " selection_end=" + failed.editor.selectionEnd +
                            " current_start=" + selection.current.start + " current_end=" + selection.current.end +
                            " latest_start=" + selection.latest.start + " latest_end=" + selection.latest.end
                    }
                    Log.e("ReviewSessionFixes", observation)
                } catch (diagnostic: Throwable) {
                    if (diagnostic !== error) error.addSuppressed(diagnostic)
                }
            }
            throw error
        } finally {
            val failures = mutableListOf<Throwable>()
            fun restore(name: String, action: () -> Unit) {
                try { action() } catch (error: Throwable) { failures += AssertionError("Could not restore $name", error) }
            }
            restore("clipboard") { main {
                if (clipboardCaptured) {
                    if (clipContents(clipboard.primaryClip) != oldClipContents) {
                        if (oldClip == null) clipboard.clearPrimaryClip() else clipboard.setPrimaryClip(oldClip!!)
                    }
                    assertEquals(oldClipContents, clipContents(clipboard.primaryClip))
                }
            } }
            restore("layout profile") { main {
                prefs.keyboard.textKeyboardLayoutProfile.setValue(oldLayout)
                assertEquals(oldLayout, prefs.keyboard.textKeyboardLayoutProfile.getValue())
            } }
            restore("candidate presentation") { main {
                prefs.candidates.mode.setValue(oldFloating)
                assertEquals(oldFloating, prefs.candidates.mode.getValue())
            } }
            restore("clipboard listening") { main {
                prefs.clipboard.clipboardListening.setValue(oldListening)
                assertEquals(oldListening, prefs.clipboard.clipboardListening.getValue())
            } }
            restore("test activity") { main { activity?.finish() }; instrumentation.waitForIdleSync() }
            restore("temporary layout") { if (createdLayout) { check(!layout.exists() || layout.delete()); assertFalse(layout.exists()) } }
            restore("input method") {
                if (previousIme.isNotBlank() && previousIme != "null") shell("ime set ${imeComponentArgument(previousIme)}")
                else shell("settings delete secure default_input_method")
            }
            restore("hardware keyboard setting") {
                if (previousHard == "null") shell("settings delete secure show_ime_with_hard_keyboard")
                else shell("settings put secure show_ime_with_hard_keyboard $previousHard")
            }
            restore("enabled input methods") { if (component !in previousEnabled) shell("ime disable $componentId") }
            restore("system input settings verification") {
                assertEquals(previousIme, shell("settings get secure default_input_method"))
                assertEquals(previousHard, shell("settings get secure show_ime_with_hard_keyboard"))
                assertEquals(previousEnabled, enabledImes())
            }
            if (failures.isNotEmpty()) {
                val cleanupError = AssertionError("${failures.size} session fixture restoration failures")
                failures.forEach(cleanupError::addSuppressed)
                if (testFailure != null) testFailure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }
}
