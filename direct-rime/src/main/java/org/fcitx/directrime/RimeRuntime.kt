package org.fcitx.directrime

import android.content.Context
import android.os.Trace
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One process-global runtime, one explicit thread owner, at most one live editor session. */
internal object RimeRuntime {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "rime-main") }.asCoroutineDispatcher()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val epoch = AtomicLong()
    @Volatile var blocked = true
        private set
    private var started = false
    fun newGeneration() = epoch.incrementAndGet()
    fun current(generation: Long) = generation == epoch.get()

    private fun copyAssets(context: Context, source: String, target: File) {
        val children = context.assets.list(source).orEmpty()
        if (children.isEmpty()) context.assets.open(source).use { input ->
            target.parentFile!!.mkdirs(); target.outputStream().use { input.copyTo(it) }
        } else {
            target.mkdirs()
            children.forEach { copyAssets(context, "$source/$it", File(target, it)) }
        }
    }
    private fun start(context: Context) {
        if (started) return
        val root = File(context.filesDir, "rime").apply { mkdirs() }
        val shared = File(root, "shared")
        copyAssets(context, "shared", shared)
        val user = File(root, "user").apply { mkdirs() }
        Trace.beginSection("rime.direct.startup")
        try { Native.start(shared.path, user.path); started = true }
        finally { Trace.endSection() }
    }
    suspend fun begin(context: Context, generation: Long, program: String): Snapshot? = withContext(dispatcher) {
        if (!current(generation)) return@withContext null
        start(context)
        if (!current(generation)) return@withContext null
        Native.begin(generation, program).also { blocked = false }
    }
    suspend fun end(generation: Long) = withContext(dispatcher) { if (started) Native.end(generation) }
    fun release(generation: Long, invalidation: Long) {
        lifecycleScope.launch {
            if (!started || !current(invalidation)) return@launch
            blocked = true
            Native.end(generation)
            Native.stop() // finalize persists dictionaries before the service goes away
            started = false
        }
    }

    suspend fun key(generation: Long, sym: Int, mask: Int = 0, release: Boolean = false): Snapshot? {
        // Check before enqueue AND on the owner. Keys pressed during deploy must
        // be swallowed now, not replayed after the maintenance thread finishes.
        if (blocked || !current(generation)) return null
        return withContext(dispatcher) {
            if (blocked || !current(generation)) null
            else Native.key(generation, sym, mask, release)
        }
    }
    suspend fun action(snapshot: Snapshot, action: Int, index: Int = 0, value: String = ""): Snapshot? {
        if (blocked || !current(snapshot.generation)) return null
        return withContext(dispatcher) {
            if (blocked || !current(snapshot.generation)) null
            else Native.action(snapshot.generation, snapshot.revision, action, index, value)
        }
    }
    suspend fun schemas(context: Context) = withContext(dispatcher) { start(context); Native.schemas() }

    suspend fun deploy(context: Context, profile: (() -> InputStream)? = null, smoke: Boolean = false,
                       restorePrevious: Boolean = false): Boolean {
        blocked = true
        newGeneration() // Immediately invalidates queued keys and UI results.
        return withContext(dispatcher) {
            val root = File(context.filesDir, "rime").apply { mkdirs() }
            try {
                if (started) { Native.stop(); started = false }
                if (restorePrevious) ProfileArchive.restorePrevious(File(root, "user"))
                val staging = File(root, "incoming")
                if (profile != null || smoke) {
                    staging.deleteRecursively()
                    if (smoke) copyAssets(context, "smoke", staging)
                    else ProfileArchive.extract(profile!!.invoke(), staging)
                    ProfileArchive.install(staging, File(root, "user"))
                }
                start(context)
                Trace.beginSection("rime.direct.deploy")
                try { Native.deploy() } finally { Trace.endSection() }
            } finally {
                // Require a fresh Android input binding after deploy; LabActivity
                // explicitly tells the user to switch back to the editor.
                blocked = true
            }
        }
    }
}
