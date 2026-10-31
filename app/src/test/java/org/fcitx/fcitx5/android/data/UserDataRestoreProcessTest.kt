package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Real host OS process termination; this does not model physical power loss. */
class UserDataRestoreProcessTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun killedWriterIsRecoveredFromDiskAtEveryReplacementAndCommitWindow() {
        val classpath = (generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
            .filter { it.protocol == "file" }.map { File(it.toURI()).path }.toList() +
            System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
        for (window in listOf("staging", "1", "2", "3", "4", "committed")) {
            val home = temp.newFolder("window-$window")
            val log = File(home, "child.log")
            val arguments = File(home, "java.args").apply {
                writeText(listOf("-Xmx64m", "-cp", classpath,
                    UserDataRestoreProcessFixture::class.java.name, home.path, window)
                    .joinToString("\n") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
            }
            val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
                "@${arguments.path}")
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                val ready = File(home, "ready")
                while (!ready.exists() && child.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue("Writer did not reach $window: ${log.readText()}", ready.exists())
                child.destroyForcibly()
                assertTrue(child.waitFor(10, TimeUnit.SECONDS))
                val result = UserDataRestoreJournal(File(home, "journal")).recover()
                assertTrue(result.pending.isEmpty()); assertTrue(result.warnings.isEmpty())
                assertEquals(window == "committed", result.committed)
                for (name in listOf("one", "two"))
                    assertEquals(if (window == "committed") "new-$name" else "old-$name", File(home, "roots/$name/data").readText())
                assertFalse(File(home, "roots").listFiles()!!.any { it.name.startsWith('.') })
            } finally {
                if (child.isAlive) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) }
            }
        }
    }
}

internal object UserDataRestoreProcessFixture {
    @JvmStatic fun main(args: Array<String>) {
        val home = File(args[0]); val window = args[1]
        val roots = File(home, "roots").apply { mkdir() }
        val state = File(home, "journal").apply { mkdir() }
        fun pause(): Nothing {
            File(home, "ready").writeText(window)
            while (true) Thread.sleep(1000)
        }
        var count = 0
        val targets = listOf("one", "two").associateWith { name ->
            File(roots, name).apply { mkdir(); File(this, "data").writeText("old-$name") }
        }
        val transaction = UserDataRestoreTransaction(UserDataRestoreJournal(state, afterPersist = {
            if (it == "COMMITTED" && window == "committed") pause()
        })) { from, to ->
            check(from.renameTo(to))
            if ((++count).toString() == window) pause()
            true
        }
        for ((name, target) in targets) {
            transaction.stage(target, false) {
                File(it, "data").writeText("new-$name")
                if (window == "staging") pause()
            }
        }
        transaction.commit()
        error("Writer did not pause at $window")
    }
}
