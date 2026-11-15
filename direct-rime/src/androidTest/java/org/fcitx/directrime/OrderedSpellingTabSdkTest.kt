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

    @Test fun literalZuanTabKeepsFuzzySingleSyllables(): Unit = runBlocking {
        selectTabWithAlgebra(
            fuzzyT9Algebra,
            keys = "9826", label = "zuan",
            before = setOf("转", "专", "装", "钻"),
            retained = setOf("转", "专", "装", "钻"), excluded = emptySet(),
            dictionaryEntries = fuzzyT9Entries, expectedTabs = listOf("zuan"),
            encoding = uppercaseT9Encoding
        )
    }

    @Test fun oneWayInitialThenFinalKeepsReachableSyllables(): Unit = runBlocking {
        selectOneWayZan(listOf("derive/^zh/z/", "derive/([āáǎàa])ng$/${'$'}1n/"))
    }

    @Test fun oneWayFinalThenInitialKeepsReachableSyllables(): Unit = runBlocking {
        selectOneWayZan(listOf("derive/([āáǎàa])ng$/${'$'}1n/", "derive/^zh/z/"))
    }

    @Test fun nextLiteralShuTabKeepsFuzzyPhrase(): Unit = runBlocking {
        selectTabWithAlgebra(
            fuzzyT9Algebra,
            keys = "9826748", label = "zuan",
            before = setOf("专属"), retained = setOf("专属"), excluded = emptySet(),
            dictionaryEntries = fuzzyT9Entries, expectedTabs = listOf("zuan"),
            encoding = uppercaseT9Encoding, nextLabel = "shu"
        )
    }

    private suspend fun selectOneWayZan(fuzzyRules: List<String>) {
        // Only zh -> z and ang -> an are allowed; nine-key collisions with
        // wan/yan must not become fuzzy relations in either rule order.
        selectTabWithAlgebra(
            fuzzyRules + listOf(toneNormalization, "derive/^(.*)$/\\U${'$'}1/"),
            keys = "926", label = "zan",
            before = setOf("赞", "站", "脏", "张", "湾", "烟"),
            retained = setOf("赞", "站", "脏", "张"), excluded = setOf("湾", "烟"),
            dictionaryEntries = "赞\tzan\t100\n站\tzhan\t100\n脏\tzang\t100\n" +
                "张\tzhang\t100\n湾\twan\t100\n烟\tyan\t100\n",
            expectedTabs = listOf("wan", "yan", "zan"), encoding = uppercaseT9Encoding
        )
    }

    private val toneNormalization =
        "xlit/āáǎàōóǒòēéěèīíǐìūúǔùǖǘǚǜüńňǹ/aaaaooooeeeeiiiiuuuuvvvvvnnn/"
    private val uppercaseT9Encoding =
        "xlit/ABCDEFGHIJKLMNOPQRSTUVWXYZ/22233344455566677778889999/"
    // The relevant ordered rules from the native tone-marked Wanxiang fixture.
    // Uppercase derive is an encoding branch, and must still supply literal tabs.
    private val fuzzyT9Algebra = listOf(
        "derive/^([zcs])h/${'$'}1/", "derive/^([zcs])([^h])/${'$'}1h${'$'}2/",
        "derive/([^jqxy])u([āáǎàa])ng$/${'$'}1u${'$'}2n/",
        "derive/([^jqxy])u([āáǎàa])n$/${'$'}1u${'$'}2ng/",
        "derive/([^iuy])([āáǎàa])ng$/${'$'}1${'$'}2n/",
        "derive/([^iuy])([āáǎàa])n$/${'$'}1${'$'}2ng/",
        toneNormalization, "derive/^(.*)$/\\U${'$'}1/"
    )
    private val fuzzyT9Entries =
        "转\tzhuǎn\t100\n专\tzhuān\t100\n装\tzhuāng\t100\n钻\tzuān\t100\n" +
        "速\tsù\t100\n属\tshǔ\t100\n转速\tzhuǎn sù\t1000\n专属\tzhuān shǔ\t1000\n"

    private suspend fun selectTabWithAlgebra(
        algebra: List<String>, keys: String, label: String,
        before: Set<String>, retained: Set<String>, excluded: Set<String>,
        dictionaryEntries: String = "皮\tpi\t100\n七\tqi\t100\n日\tri\t100\n" +
            "生\tsheng\t100\n沈\tshen\t100\n甥\tshēng\t80\n",
        expectedTabs: List<String>? = null,
        encoding: String = "xlit/abcdefghijklmnopqrstuvwxyz/22233344455566677778889999/",
        nextLabel: String? = null
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rules = (algebra + encoding)
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
                dictionaryEntries
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
            for ((selection, selectedLabel) in listOfNotNull(label, nextLabel).withIndex()) {
                val fullTabs = if (selection == 0) expectedTabs else listOf(selectedLabel)
                if (fullTabs != null) {
                    // These fixtures use multi-letter syllables; initial/raw-key tabs
                    // have one character. Native keeps the spans behind these labels.
                    assertEquals("Full syllable tabs for $keys", fullTabs,
                        state.tabs.filter { it.length > 1 })
                }
                val tab = state.tabs.indexOf(selectedLabel)
                assertTrue("Missing $selectedLabel tab: ${state.tabs.contentToString()}", tab >= 0)
                // Action 4 selects the current snapshot's label/span at the native
                // tab cursor, including the next syllable after the first selection.
                state = RimeRuntime.action(state, 4, tab)!!
                assertTrue(state.gateMessage, state.ready)
                assertTrue("Selecting a syllable tab must not commit text", state.commit.isEmpty())
                retained.forEach { candidate ->
                    assertTrue("After selecting $selectedLabel: ${state.candidates.contentToString()}",
                        candidate in state.candidates)
                }
                excluded.forEach { candidate ->
                    assertFalse("Unrelated $candidate after selecting $selectedLabel: ${state.candidates.contentToString()}",
                        candidate in state.candidates)
                }
            }
            RimeRuntime.end(generation)
        } finally {
            // Match the other SDK smoke tests: stop the fixture engine and restore the
            // deterministic smoke profile in this diagnostic APK's sandbox.
            assertTrue(RimeRuntime.deploy(context, smoke = true))
        }
    }
}
