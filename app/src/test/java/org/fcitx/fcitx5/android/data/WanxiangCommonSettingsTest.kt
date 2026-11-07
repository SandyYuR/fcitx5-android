/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.yaml.snakeyaml.nodes.*
import java.io.File

class WanxiangCommonSettingsTest {
    @get:Rule val temp = TemporaryFolder()
    private val y = WanxiangYaml()
    private fun session(): WanxiangAdvancedSession {
        val default = "schema_list: [{schema: wanxiang}]\nmenu: {page_size: 6}\nkey_binder:\n  bindings:\n    - {when: composing, accept: Control+k, send: BackSpace}\n    - {when: has_menu, accept: minus, send: Page_Up}\n"
        val main = "schema: {schema_id: wanxiang}\ntranslator: {dictionary: wanxiang, enable_user_dict: true}\nmenu: {page_size: 6}\ncustom_phrase: {dictionary: custom_phrase}\nabbrev_phrase: {dictionary: wanxiang_abbrev}\nwanxiang_lookup: {key: '`'}\nwanxiang_reverse: {prefix: '`'}\nspeller: {alphabet: 'abc`'}\nrecognizer:\n  patterns:\n    wanxiang_reverse: '^`[A-Za-z;]*$'\nkey_binder:\n  bindings: [{when: composing, accept: Control+k, send: BackSpace}]\nswitches:\n  - {name: emoji}\n  - {name: full_shape, reset: 0}\n  - {options: [s2s, s2t, s2hk]}\n"
        return WanxiangAdvancedSession(WanxiangTarget("rime", "/unused"), false).apply {
            add(WanxiangAdvancedFiles.Snapshot("default.yaml", default, "", default, emptyMap()))
            add(WanxiangAdvancedFiles.Snapshot("wanxiang.schema.yaml", main, "", main, emptyMap()))
        }
    }
    @Test fun candidateCountAndFrequencyLearningApplyAcrossInstalledMainSchemes() {
        val s = session(); val common = WanxiangCommonSettings(s)
        common.set(WanxiangCommonSettings.Key.PAGE_SIZE, y.scalar(8))
        common.set(WanxiangCommonSettings.Key.USER_DICTIONARY, y.scalar(false))
        assertEquals("8", (s.value("default.yaml", "menu/page_size") as ScalarNode).value)
        assertEquals("8", (s.value("wanxiang.schema.yaml", "menu/page_size") as ScalarNode).value)
        assertFalse(y.boolean(s.value("wanxiang.schema.yaml", "translator/enable_user_dict")))
    }
    @Test fun changingPagingKeysRetainsUnrelatedBindingsAndUsesTheChosenPair() {
        val s = session(); val common = WanxiangCommonSettings(s)
        common.set(WanxiangCommonSettings.Key.PAGING, y.scalar(1))
        val bindings = s.value("default.yaml", "key_binder/bindings") as SequenceNode
        val accepts = bindings.value.map { (y.member(it as MappingNode, "accept") as ScalarNode).value }
        assertTrue("Control+k" in accepts); assertTrue("comma" in accepts); assertTrue("period" in accepts)
        assertFalse("minus" in accepts)
    }
    @Test fun duplicateSecondAndThirdCandidateKeysAreRejected() {
        val common = WanxiangCommonSettings(session())
        common.set(WanxiangCommonSettings.Key.SECOND_KEY, y.string("semicolon"))
        assertTrue(runCatching { common.set(WanxiangCommonSettings.Key.THIRD_KEY, y.string("semicolon")) }.isFailure)
    }
    @Test fun reverseLookupChangesItsKeyPrefixPatternAndAlphabetTogether() {
        val s = session(); val common = WanxiangCommonSettings(s)
        common.set(WanxiangCommonSettings.Key.REVERSE_KEY, y.string("+"))
        assertEquals("+", (s.value("wanxiang.schema.yaml", "wanxiang_reverse/prefix") as ScalarNode).value)
        assertEquals("^\\+[A-Za-z;]*$", (s.value("wanxiang.schema.yaml", "recognizer/patterns/wanxiang_reverse") as ScalarNode).value)
        assertEquals("abc+", (s.value("wanxiang.schema.yaml", "speller/alphabet") as ScalarNode).value)
        assertTrue(runCatching { common.set(WanxiangCommonSettings.Key.REVERSE_KEY, y.string("a")) }.isFailure)
    }
    @Test fun importedMemorySwitchesExcludeResetSwitchesAndTheFirstGroupedOption() {
        val common = WanxiangCommonSettings(session())
        assertEquals(listOf("emoji", "s2t", "s2hk"), (common.importSwitches() as SequenceNode).value.map { (it as ScalarNode).value })
    }
    @Test fun grammarRecommendationsAreDraftedAlongWithTheirTranslatorParameters() {
        val s = session(); val common = WanxiangCommonSettings(s)
        common.set(WanxiangCommonSettings.Key.GRAMMAR, y.scalar(1))
        assertEquals("wanxiang-lts-zh-hans", (y.get(s.value("wanxiang.schema.yaml", "grammar")!!, "language") as ScalarNode).value)
        assertFalse(y.boolean(s.value("wanxiang.schema.yaml", "translator/contextual_suggestions")))
        assertEquals("8", (s.value("wanxiang.schema.yaml", "translator/max_homophones") as ScalarNode).value)
        assertTrue(s.changes().containsKey("wanxiang.custom.yaml"))
    }
    @Test fun clearingGrammarParametersPreservesTheWholeDraftWhenAParentPatchCannotBeSplit() {
        val s = session()
        val main = s.documents.getValue("wanxiang.schema.yaml").snapshot
        s.add(main.copy(custom = "patch:\n  grammar: {language: personal}\n  translator:\n    __include: local:/translator\n    contextual_suggestions: true\n", effective = null))
        s.change("default.yaml", "menu/page_size", y.scalar(8))
        val before = s.draft()
        val failure = runCatching { WanxiangCommonSettings(s).set(WanxiangCommonSettings.Key.GRAMMAR, y.scalar(2)) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalArgumentException)
        assertEquals(before, s.draft())
    }
    @Test fun primarySchemeFollowsTheEnabledOrderRatherThanSourceFileOrder() {
        val s = session()
        val main = s.documents.getValue("wanxiang.schema.yaml").snapshot
        s.add(main.copy(file = "wanxiang_pro.schema.yaml"))
        s.change("default.yaml", "schema_list", y.parse("[{schema: wanxiang_pro}, {schema: wanxiang}]"))
        assertEquals("wanxiang_pro.schema.yaml", WanxiangCommonSettings(s).primary)
    }

    private val dictionaries = listOf(
        Triple(WanxiangCommonSettings.Key.PHRASE_DICTIONARY, WanxiangCommonSettings.Key.PHRASE_SCHEMAS, "custom_phrase/dictionary"),
        Triple(WanxiangCommonSettings.Key.ABBREV_DICTIONARY, WanxiangCommonSettings.Key.ABBREV_SCHEMAS, "abbrev_phrase/dictionary"))

    private fun dictionarySession(path: String, direct: Boolean = false, other: String? = null): WanxiangAdvancedSession {
        val initial = session()
        return WanxiangAdvancedSession(WanxiangTarget("rime", temp.newFolder().canonicalPath), direct).apply {
            initial.documents.values.forEach { doc ->
                val source = y.document(doc.snapshot.source)
                if (doc.snapshot.file == "wanxiang.schema.yaml") y.set(source, path, y.string("personal"))
                add(doc.snapshot.copy(source = y.dump(source), effective = null))
            }
            if (other != null) {
                val source = y.document(documents.getValue("wanxiang.schema.yaml").snapshot.source)
                y.set(source, "schema/schema_id", y.string("wanxiang_pro"))
                y.set(source, path, y.string(other))
                add(WanxiangAdvancedFiles.Snapshot("wanxiang_pro.schema.yaml", y.dump(source), "", null, emptyMap()))
            }
        }
    }

    private fun reopen(session: WanxiangAdvancedSession): WanxiangAdvancedSession {
        val updates = session.changes()
        return WanxiangAdvancedSession(session.target, session.direct).apply {
            session.documents.values.forEach { doc ->
                val old = doc.snapshot
                add(old.copy(source = updates[old.file] ?: old.source,
                    custom = WanxiangAdvancedFiles.patchFile(old.file)?.let(updates::get) ?: old.custom, effective = null))
            }
        }
    }

    @Test fun dictionariesWithoutProducerSchemasKeepTheirNamesAndAssociationsAfterRenaming() {
        for ((dictionary, associations, path) in dictionaries) for (direct in listOf(false, true)) {
            val s = dictionarySession(path, direct)
            val body = "private\tcode\t100\n"
            File(s.target.path, "personal.dict.yaml").writeText("name: personal\nversion: '1'\n...\n$body")
            val common = WanxiangCommonSettings(s)
            assertEquals("personal", (common.get(dictionary) as ScalarNode).value)
            assertEquals(listOf("wanxiang"), (common.get(associations) as SequenceNode).value.map { (it as ScalarNode).value })
            common.set(dictionary, y.string("renamed"))
            assertEquals("renamed", (WanxiangCommonSettings(s).get(dictionary) as ScalarNode).value)
            assertEquals("renamed", s.text("wanxiang.schema.yaml", path))
            assertTrue(s.changes().getValue("renamed.dict.yaml").endsWith(body))
            val reopened = WanxiangCommonSettings(reopen(s))
            assertEquals("renamed", (reopened.get(dictionary) as ScalarNode).value)
            assertEquals(listOf("wanxiang"), (reopened.get(associations) as SequenceNode).value.map { (it as ScalarNode).value })
        }
    }

    @Test fun differentConsumerDictionariesFollowThePrimarySchemeAndPreserveOtherAssociations() {
        for ((dictionary, associations, path) in dictionaries) {
            val s = dictionarySession(path, other = "work")
            s.change("default.yaml", "schema_list", y.parse("[{schema: wanxiang_pro}, {schema: wanxiang}]"))
            File(s.target.path, "work.dict.yaml").writeText("name: work\n...\nprivate\tcode\n")
            val common = WanxiangCommonSettings(s)
            assertEquals("work", (common.get(dictionary) as ScalarNode).value)
            assertEquals(listOf("wanxiang_pro"), (common.get(associations) as SequenceNode).value.map { (it as ScalarNode).value })
            assertTrue(common.help(dictionary).contains("wanxiang_pro"))
            assertTrue(common.help(associations).contains("work"))
            common.set(dictionary, y.string("renamed"))
            assertEquals("renamed", s.text("wanxiang_pro.schema.yaml", path))
            assertEquals("personal", s.text("wanxiang.schema.yaml", path))
            common.set(associations, y.sequence(emptyList()))
            assertEquals("", s.text("wanxiang_pro.schema.yaml", path))
            assertEquals("personal", s.text("wanxiang.schema.yaml", path))
        }
    }

    @Test fun movingAnAssociationUsesTheChosenDictionaryForTheWholeChange() {
        for ((_, associations, path) in dictionaries) {
            val s = dictionarySession(path, other = "work")
            WanxiangCommonSettings(s).set(associations, y.sequence(listOf(y.string("wanxiang_pro"))))
            assertEquals("", s.text("wanxiang.schema.yaml", path))
            assertEquals("personal", s.text("wanxiang_pro.schema.yaml", path))
        }
    }

    @Test fun disablingAllConsumersKeepsTheSnapshotDictionaryAvailableInTheDraft() {
        for ((dictionary, associations, path) in dictionaries) {
            val s = dictionarySession(path)
            WanxiangCommonSettings(s).set(associations, y.sequence(emptyList()))
            val common = WanxiangCommonSettings(s)
            assertEquals("personal", (common.get(dictionary) as ScalarNode).value)
            assertTrue((common.get(associations) as SequenceNode).value.isEmpty())
            common.set(associations, y.sequence(listOf(y.string("wanxiang"))))
            assertEquals("personal", s.text("wanxiang.schema.yaml", path))
        }
    }

    @Test fun deselectedPhraseAndAbbreviationAssociationsStayDisabledAfterReopening() {
        for (direct in listOf(false, true)) {
            val s = session().let { original ->
                WanxiangAdvancedSession(original.target, direct).apply {
                    original.documents.values.forEach { add(it.snapshot) }
                }
            }
            val common = WanxiangCommonSettings(s)
            for (key in listOf(WanxiangCommonSettings.Key.PHRASE_SCHEMAS, WanxiangCommonSettings.Key.ABBREV_SCHEMAS)) {
                assertEquals(1, (common.get(key) as SequenceNode).value.size)
                common.set(key, y.sequence(emptyList()))
                assertTrue((common.get(key) as SequenceNode).value.isEmpty())
            }
            val updates = s.changes()
            val restored = WanxiangAdvancedSession(s.target, direct).apply {
                s.documents.values.forEach { doc ->
                    val old = doc.snapshot
                    add(old.copy(source = updates[old.file] ?: old.source,
                        custom = WanxiangAdvancedFiles.patchFile(old.file)?.let(updates::get) ?: old.custom, effective = null))
                }
            }
            val reopened = WanxiangCommonSettings(restored)
            for (key in listOf(WanxiangCommonSettings.Key.PHRASE_SCHEMAS, WanxiangCommonSettings.Key.ABBREV_SCHEMAS)) {
                assertTrue((reopened.get(key) as SequenceNode).value.isEmpty())
                reopened.set(key, y.sequence(listOf(y.string("wanxiang"))))
                assertEquals(listOf("wanxiang"), (reopened.get(key) as SequenceNode).value.map { (it as ScalarNode).value })
            }
        }
    }
}
