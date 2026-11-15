package org.fcitx.directrime

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Run spelling-algebra tab constraints through this APK's real packaged librime. */
class OrderedSpellingTabSdkTest {
    @Test fun transformationBeforeDerivationDoesNotInventRelation(): Unit = runBlocking {
        selectTabWithAlgebra(
            listOf("xform/^qi$/ri/", "derive/^qi$/pi/"),
            keys = "74", label = "pi",
            before = setOf("皮", "七", "日"),
            retained = setOf("皮"), excluded = setOf("七", "日")
        )
    }

    @Test fun actualDerivationSurvivesTheSameNineKeyCode(): Unit = runBlocking {
        selectTabWithAlgebra(
            listOf("derive/^qi$/pi/"),
            keys = "74", label = "pi",
            before = setOf("皮", "七", "日"),
            retained = setOf("皮", "七"), excluded = setOf("日")
        )
    }

    @Test fun toneMarkedDerivationSurvivesLaterNormalization(): Unit = runBlocking {
        selectTabWithAlgebra(
            listOf("derive/^shēng$/shēn/", "xform/ē/e/"),
            keys = "7436", label = "shen",
            before = setOf("沈", "甥"),
            retained = setOf("沈", "甥"), excluded = emptySet()
        )
    }

    private suspend fun selectTabWithAlgebra(
        algebra: List<String>, keys: String, label: String,
        before: Set<String>, retained: Set<String>, excluded: Set<String>
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rules = (algebra + "xlit/abcdefghijklmnopqrstuvwxyz/22233344455566677778889999/")
            .joinToString("\n") { "    - $it" }
        val files = mapOf(
            "default.yaml" to "config_version: '1.0'\nschema_list:\n  - schema: sdk_ordered_tabs\n",
            "sdk_ordered_tabs.schema.yaml" to """
                schema:
                  schema_id: sdk_ordered_tabs
                  name: Ordered spelling SDK test
                  version: '1.0'
                engine:
                  processors: [speller]
                  segmentors: [abc_segmentor]
                  translators: [script_translator]
                speller:
                  alphabet: '1234567890'
                  algebra:
            """.trimIndent() + "\n" + rules + "\n" + """
                translator:
                  dictionary: sdk_ordered_tabs
                  enable_user_dict: false
                  enable_sentence: false
                  enable_completion: false
                menu:
                  page_size: 20
            """.trimIndent() + "\n",
            "sdk_ordered_tabs.dict.yaml" to
                "---\nname: sdk_ordered_tabs\nversion: '1.0'\nsort: by_weight\n...\n" +
                "皮\tpi\t100\n七\tqi\t100\n日\tri\t100\n" +
                "生\tsheng\t100\n沈\tshen\t100\n甥\tshēng\t80\n"
        )
        val profile = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip ->
                files.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        try {
            assertTrue("The ordered-spelling profile must deploy", RimeRuntime.deploy(
                context, profile = { ByteArrayInputStream(profile) }
            ))
            val generation = RimeRuntime.newGeneration()
            var state = RimeRuntime.begin(context, generation, "sdk.ordered-tabs")!!
            assertTrue(state.gateMessage, state.ready)
            assertEquals("sdk_ordered_tabs", state.schema)
            keys.forEach { state = RimeRuntime.key(generation, it.code)!! }
            before.forEach { candidate ->
                assertTrue("Before selecting $label: ${state.candidates.contentToString()}",
                    candidate in state.candidates)
            }
            val tab = state.tabs.indexOf(label)
            assertTrue("Missing $label tab: ${state.tabs.contentToString()}", tab >= 0)
            // Action 4 selects the current snapshot's tab label and span via Native.select_tab.
            state = RimeRuntime.action(state, 4, tab)!!
            assertTrue(state.gateMessage, state.ready)
            assertTrue("Selecting a syllable tab must not commit text", state.commit.isEmpty())
            retained.forEach { candidate ->
                assertTrue("After selecting $label: ${state.candidates.contentToString()}",
                    candidate in state.candidates)
            }
            excluded.forEach { candidate ->
                assertFalse("Unrelated $candidate after selecting $label: ${state.candidates.contentToString()}",
                    candidate in state.candidates)
            }
            RimeRuntime.end(generation)
        } finally {
            // Match the other SDK smoke tests: stop the fixture engine and restore the
            // deterministic smoke profile in this diagnostic APK's sandbox.
            assertTrue(RimeRuntime.deploy(context, smoke = true))
        }
    }
}
