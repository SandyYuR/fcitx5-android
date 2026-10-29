/* SPDX-License-Identifier: LGPL-2.1-or-later */
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataDescriptorIncrementalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun movingAnUnchangedFileUpdatesTheDescriptorPath() {
        val root = temporary.root
        File(root, "settings.gradle").writeText("rootProject.name = 'descriptor-fixture'")
        File(root, "build.gradle").writeText("""
            plugins { id 'org.fcitx.fcitx5.android.data-descriptor' apply false }
            tasks.register('descriptor', DataDescriptorPlugin.class.classLoader.loadClass('DataDescriptorPlugin${'$'}DataDescriptorTask')) {
                inputDir.set(layout.projectDirectory.dir('assets'))
                outputFile.set(layout.projectDirectory.file('assets/descriptor.json'))
                excludes.set([])
                symlinks.set([:])
            }
        """.trimIndent())
        val old = File(root, "assets/a/sample.txt").apply { parentFile.mkdirs(); writeText("identical bytes") }
        val moved = File(root, "assets/b/sample.txt").apply { parentFile.mkdirs() }
        fun run() = GradleRunner.create().withProjectDir(root).withPluginClasspath()
            .withArguments("descriptor", "--stacktrace", "--max-workers=1").build()
        run()
        // The output is inside the input tree; let Gradle observe it before checking no-op behavior.
        run()
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":descriptor")!!.outcome)
        assertTrue(old.renameTo(moved))
        assertEquals(TaskOutcome.SUCCESS, run().task(":descriptor")!!.outcome)
        val descriptor = File(root, "assets/descriptor.json").readText()
        assertTrue(descriptor.contains("b/sample.txt"))
        assertFalse(descriptor.contains("a/sample.txt"))
    }
}
