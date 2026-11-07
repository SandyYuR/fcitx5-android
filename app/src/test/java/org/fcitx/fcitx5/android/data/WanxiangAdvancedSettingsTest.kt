/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.ui.main.wanxiang.WanxiangTarget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.yaml.snakeyaml.nodes.*
import java.io.File

class WanxiangAdvancedSettingsTest {
    @get:Rule val temp = TemporaryFolder()
    private val y = WanxiangYaml()
    private fun snapshot(name: String, source: String, custom: String = "", effective: String? = null) =
        WanxiangAdvancedFiles.Snapshot(name, source, custom, effective, mapOf(name to "fixture", (WanxiangAdvancedFiles.patchFile(name) ?: name) to if (custom.isEmpty()) null else "custom"))
    private fun session(vararg snapshots: WanxiangAdvancedFiles.Snapshot, direct: Boolean = false) =
        WanxiangAdvancedSession(WanxiangTarget("rime", temp.root.path), direct).apply { snapshots.forEach(::add) }
    private fun scalar(node: Node?) = (node as ScalarNode).value
    private val source = "# scheme comment\nschema: {schema_id: wanxiang, name: Test}\nmenu:\n  page_size: 6 # candidates\ntranslator:\n  dictionary: wanxiang\n  enable_user_dict: true\nthird_party:\n  enabled: true\n  options: ['true', 1, false]\n"

    @Test fun aPatchChangesOnlyTheRequestedPathAndRetainsUnknownDataAndComments() {
        val s = session(snapshot("wanxiang.schema.yaml", source, "# personal comment\npatch:\n  third_party/options/+: ['extra']\nother_metadata: keep\n"))
        s.change("wanxiang.schema.yaml", "menu/page_size", y.scalar(8))
        val changes = s.changes()
        assertEquals(setOf("wanxiang.custom.yaml"), changes.keys)
        val result = y.document(changes.getValue("wanxiang.custom.yaml"))
        val patch = y.member(result, "patch") as MappingNode
        assertEquals("8", scalar(y.member(patch, "menu/page_size")))
        assertNotNull(y.member(patch, "third_party/options/+"))
        assertEquals("keep", scalar(y.member(result, "other_metadata")))
        assertTrue(changes.values.single().contains("# personal comment"))
        assertEquals(1, s.edits.size)
    }

    @Test fun directWritesRetainCommentsAndUnknownTypedValues() {
        val s = session(snapshot("wanxiang.schema.yaml", source), direct = true)
        s.change("wanxiang.schema.yaml", "menu/page_size", y.scalar(9))
        val text = s.changes().getValue("wanxiang.schema.yaml")
        assertTrue(text.contains("# scheme comment")); assertTrue(text.contains("# candidates"))
        val root = y.document(text)
        assertEquals("9", scalar(y.get(root, "menu/page_size")))
        val values = y.get(root, "third_party/options") as SequenceNode
        assertEquals(listOf(Tag.STR, Tag.INT, Tag.BOOL), values.value.map { it.tag })
    }

    @Test fun scalarFieldsUseRimesCompiledViewButDirectiveEditorsKeepReferences() {
        val s = session(snapshot("wanxiang.schema.yaml", source + "speller:\n  algebra:\n    __patch: [wanxiang_algebra:/base/全拼]\n",
            effective = source.replace("enable_user_dict: true", "enable_user_dict: false") + "speller:\n  algebra: ['derive/a/b/']\n"))
        val fields = s.fields("wanxiang.schema.yaml")
        assertEquals("false", fields.first { it.path == "translator/enable_user_dict" }.value)
        assertTrue(fields.first { it.path == "speller/algebra" }.value.contains("wanxiang_algebra:/base/全拼"))
    }

    @Test fun changingAnOverrideBackToAnUnambiguousSourceValueRemovesTheOverride() {
        val s = session(snapshot("wanxiang.schema.yaml", source, "patch: {'menu/page_size': 8}\n",
            source.replace("page_size: 6", "page_size: 8")))
        s.change("wanxiang.schema.yaml", "menu/page_size", y.scalar(6))
        val patch = y.member(y.document(s.changes().values.single()), "patch") as MappingNode
        assertNull(y.member(patch, "menu/page_size"))
    }

    @Test fun aSourceValueStillOverridesAnEarlierParentChange() {
        val s = session(snapshot("wanxiang.schema.yaml", source))
        s.change("wanxiang.schema.yaml", "menu", y.document("page_size: 9\nother: true\n"))
        s.change("wanxiang.schema.yaml", "menu/page_size", y.scalar(6))
        assertEquals("6", scalar(s.value("wanxiang.schema.yaml", "menu/page_size")))
        val patch = y.member(y.document(s.changes().values.single()), "patch") as MappingNode
        assertEquals("6", scalar(y.member(patch, "menu/page_size")))
        assertEquals(listOf("menu", "menu/page_size"), patch.value.map { y.key(it.keyNode) })
    }

    @Test fun customOnlyFieldsRemainVisibleAndEditableAfterSavingAndReopening() {
        val s = session(snapshot("wanxiang.schema.yaml", source))
        s.change("wanxiang.schema.yaml", "translator/custom_setting", y.string("original"))
        s.change("wanxiang.schema.yaml", "my_extension/enabled", y.scalar(true))
        val saved = s.changes().getValue("wanxiang.custom.yaml")
        val reopened = session(snapshot("wanxiang.schema.yaml", source, saved))
        val fields = reopened.fields("wanxiang.schema.yaml").associateBy { it.path }
        assertEquals("original", fields.getValue("translator/custom_setting").value)
        assertEquals(WanxiangYaml.Type.BOOLEAN, fields.getValue("my_extension/enabled").type)
        reopened.change("wanxiang.schema.yaml", "translator/custom_setting", y.string("edited"))
        val patch = y.member(y.document(reopened.changes().getValue("wanxiang.custom.yaml")), "patch") as MappingNode
        assertEquals("edited", scalar(y.member(patch, "translator/custom_setting")))
        assertTrue(y.boolean(y.member(patch, "my_extension/enabled")))
    }

    @Test fun removingAnOverridePreservesUnrelatedOverrides() {
        val s = session(snapshot("wanxiang.schema.yaml", source, "patch: {'menu/page_size': 8, 'translator/enable_user_dict': false}\n"))
        s.reset("wanxiang.schema.yaml", "menu/page_size")
        val patch = y.member(y.document(s.changes().values.single()), "patch") as MappingNode
        assertNull(y.member(patch, "menu/page_size"))
        assertNotNull(y.member(patch, "translator/enable_user_dict"))
    }

    @Test fun removingAnEqualsOverrideRestoresTheSourceAndPreservesOtherFields() {
        val s = session(snapshot("wanxiang.schema.yaml", source,
            "patch: {'menu/page_size/=': 8, 'translator/enable_user_dict': false}\n"))
        s.reset("wanxiang.schema.yaml", "menu/page_size")
        val saved = s.changes().values.single()
        val patch = y.member(y.document(saved), "patch") as MappingNode
        assertNull(y.member(patch, "menu/page_size/="))
        val reopened = session(snapshot("wanxiang.schema.yaml", source, saved))
        assertEquals("6", scalar(reopened.value("wanxiang.schema.yaml", "menu/page_size")))
        assertFalse(y.boolean(reopened.value("wanxiang.schema.yaml", "translator/enable_user_dict")))
    }

    @Test fun removingANestedReplacementRestoresOnlyTheSelectedSourceField() {
        val original = source.replace("page_size: 6 # candidates", "page_size: 6 # candidates\n  hidden: original")
        for (parent in listOf("menu", "menu/=")) {
            val s = session(snapshot("wanxiang.schema.yaml", original,
                "patch:\n  '$parent':\n    page_size: 8\n    # keep this setting\n    other: true\n  'menu/other': false\n"))
            s.reset("wanxiang.schema.yaml", "menu/page_size")
            val saved = s.changes().values.single()
            assertTrue(saved.contains("keep this setting"))
            val patch = y.member(y.document(saved), "patch") as MappingNode
            assertNull(y.member(patch, parent))
            assertNull(y.member(patch, "menu/page_size"))
            val reopened = session(snapshot("wanxiang.schema.yaml", original, saved))
            assertEquals("6", scalar(reopened.value("wanxiang.schema.yaml", "menu/page_size")))
            assertFalse(y.boolean(reopened.value("wanxiang.schema.yaml", "menu/other")))
            assertEquals(Tag.NULL, reopened.value("wanxiang.schema.yaml", "menu/hidden")!!.tag)
        }
    }

    @Test fun removingANestedMergePreservesTheOtherMergeEntries() {
        val original = source + "extension: {nested: {first: original, second: original}, other: original}\n"
        val s = session(snapshot("wanxiang.schema.yaml", original,
            "patch:\n  extension/+:\n    nested: {first: changed, second: changed}\n    other: changed\n"))
        s.reset("wanxiang.schema.yaml", "extension/nested/first")
        val patch = y.member(y.document(s.changes().values.single()), "patch") as MappingNode
        val merge = y.member(patch, "extension/+")!!
        assertNull(y.get(merge, "nested/first"))
        assertEquals("changed", scalar(y.get(merge, "nested/second")))
        assertEquals("changed", scalar(y.get(merge, "other")))
        assertEquals("original", scalar(s.value("wanxiang.schema.yaml", "extension/nested/first")))
        val reopened = session(snapshot("wanxiang.schema.yaml", original, s.changes().values.single()))
        assertEquals("original", scalar(reopened.value("wanxiang.schema.yaml", "extension/nested/first")))
        assertEquals("changed", scalar(reopened.value("wanxiang.schema.yaml", "extension/nested/second")))
        assertEquals("changed", scalar(reopened.value("wanxiang.schema.yaml", "extension/other")))
    }

    @Test fun aLaterParentEditReplacesSavedAndPendingChildPatches() {
        val s = session(snapshot("wanxiang.schema.yaml", source,
            "patch: {'menu/page_size/=': 8, 'menu/other': true}\n"))
        s.change("wanxiang.schema.yaml", "menu/page_size", y.scalar(7))
        s.change("wanxiang.schema.yaml", "menu", y.document("page_size: 9\n"))
        val saved = s.changes().values.single()
        val patch = y.member(y.document(saved), "patch") as MappingNode
        assertEquals(listOf("menu"), patch.value.map { y.key(it.keyNode) })
        assertEquals("9", scalar(session(snapshot("wanxiang.schema.yaml", source, saved)).value("wanxiang.schema.yaml", "menu/page_size")))
    }

    @Test fun aParentResetCancelsPendingDescendantEditsAndRemovesSavedChildren() {
        val s = session(snapshot("wanxiang.schema.yaml", source, "patch: {'menu/page_size/=': 8}\n"))
        s.change("wanxiang.schema.yaml", "menu/page_size", y.scalar(7))
        s.change("wanxiang.schema.yaml", "menu/other", y.scalar(true))
        s.reset("wanxiang.schema.yaml", "menu")
        assertEquals("6", scalar(s.value("wanxiang.schema.yaml", "menu/page_size")))
        assertNull(s.value("wanxiang.schema.yaml", "menu/other"))
        val patch = y.member(y.document(s.changes().values.single()), "patch") as MappingNode
        assertTrue(patch.value.isEmpty())
    }

    @Test fun cancellingANewOverrideLeavesNoDraftChanges() {
        val s = session(snapshot("wanxiang.schema.yaml", source))
        s.change("wanxiang.schema.yaml", "menu/page_size", y.scalar(8))
        s.reset("wanxiang.schema.yaml", "menu/page_size")
        assertFalse(s.dirty)
        assertTrue(s.changes().isEmpty())
    }

    @Test fun complexParentPatchesAreRejectedBeforeChangingTheDraft() {
        val customs = listOf(
            "patch: {menu: {page_size: 8, other: true}, 'menu/+': {other: false}}\n",
            "patch: {menu: {__include: 'other:/menu', page_size: 8}}\n"
        )
        customs.forEach { custom ->
            val s = session(snapshot("wanxiang.schema.yaml", source, custom))
            s.change("wanxiang.schema.yaml", "translator/enable_user_dict", y.scalar(false))
            val before = s.draft()
            val changes = s.changes()
            assertTrue(runCatching { s.reset("wanxiang.schema.yaml", "menu/page_size") }.isFailure)
            assertEquals(before, s.draft())
            assertEquals(changes, s.changes())
        }
    }

    @Test fun invalidCandidateCountsEmptySchemaListsAndIdentityEditsAreRejected() {
        val s = session(snapshot("wanxiang.schema.yaml", source))
        assertTrue(runCatching { s.change("wanxiang.schema.yaml", "menu/page_size", y.scalar(0)) }.isFailure)
        assertTrue(runCatching { s.change("wanxiang.schema.yaml", "schema_list", y.sequence(emptyList())) }.isFailure)
        assertTrue(runCatching { s.change("wanxiang.schema.yaml", "schema/schema_id", y.string("other")) }.isFailure)
        assertFalse(s.dirty)
    }

    @Test fun draftsKeepEditsAndTheirOriginalFingerprintsAcrossSerialization() {
        val s = session(snapshot("wanxiang.schema.yaml", source))
        s.change("wanxiang.schema.yaml", "menu/page_size", y.scalar(7))
        val restored = WanxiangAdvancedSession.restore(Json.decodeFromString(Json.encodeToString(s.draft())))
        assertEquals(s.changes(), restored.changes())
        assertEquals(s.plan(), restored.plan())
    }

    @Test fun duplicateKeysUnsafeTagsAndRecursiveAliasesAreRejected() {
        for (text in listOf("a: 1\na: 2\n", "a: !!javax.script.ScriptEngineManager {}", "a: &loop [*loop]"))
            assertTrue(text, runCatching { y.document(text) }.isFailure)
    }

    @Test fun stringsStayStringsAndDecimalsAcceptWholeNumbers() {
        assertEquals(Tag.STR, y.value("false", WanxiangYaml.Type.STRING).tag)
        assertEquals(Tag.STR, y.value("123", WanxiangYaml.Type.STRING).tag)
        assertEquals(Tag.FLOAT, y.value("7", WanxiangYaml.Type.DECIMAL).tag)
        assertTrue(runCatching { y.value(".nan", WanxiangYaml.Type.DECIMAL) }.isFailure)
    }

    @Test fun fragmentRoundTripsKeepInlineCommentsListsAndMultilineText() {
        val root = y.document("number: 6 # inline\nlist: # list comment\n  - key: value\n    other: true\ntext: |-\n  first\n  second\nmissing:\n")
        for (name in listOf("number", "list", "text", "missing")) {
            val node = y.member(root, name)!!
            assertTrue(name, y.equivalent(node, y.parse(y.dump(node))))
            assertTrue(name, y.equivalent(node, y.clone(node)))
        }
        assertTrue(y.dump(y.member(root, "number")!!).contains("# inline"))
    }

    @Test fun commentedFlowMappingsInListsAndFlowListsWithInlineCommentsRoundTrip() {
        val root = y.document("bindings:\n  # first binding\n  - {when: always, accept: Shift+space, select: .next} # cycle\n  # second binding\n  - {when: has_menu, accept: Control+e, toggle: english}\nlookup:\n  tags: [abc, user_dict] # lookup tags\n")
        for (node in listOf(root, y.member(root, "bindings")!!, y.get(root, "lookup/tags")!!)) {
            val text = y.dump(node)
            assertTrue(y.equivalent(node, y.parse(text)))
            assertTrue(y.equivalent(node, y.clone(node)))
        }
        assertTrue(y.dump(root).contains("first binding"))
        assertTrue(y.dump(root).contains("lookup tags"))
    }

    @Test fun runtimeStateDoesNotInvalidateAnEditingSnapshotButSourceChangesDo() {
        val root = temp.newFolder()
        File(root, "wanxiang.schema.yaml").writeText(source)
        File(root, "user.yaml").writeText("state: old")
        val snapshot = WanxiangAdvancedFiles.snapshot(root, "wanxiang.schema.yaml")
        File(root, "user.yaml").writeText("state: new")
        assertFalse(snapshot.expected.containsKey("user.yaml"))
        WanxiangAdvancedFiles.verify(root, snapshot.expected)
        File(root, "wanxiang.schema.yaml").appendText("new_key: true\n")
        assertTrue(runCatching { WanxiangAdvancedFiles.verify(root, snapshot.expected) }.exceptionOrNull() is WanxiangEditConflict)
    }

    @Test fun aCustomFileCreatedElsewhereAfterLoadingIsDetected() {
        val root = temp.newFolder()
        File(root, "wanxiang.schema.yaml").writeText(source)
        val snapshot = WanxiangAdvancedFiles.snapshot(root, "wanxiang.schema.yaml")
        File(root, "wanxiang.custom.yaml").writeText("patch: {new: true}")
        assertTrue(runCatching { WanxiangAdvancedFiles.verify(root, snapshot.expected) }.isFailure)
    }

    @Test fun dictionaryRenamingCopiesTheBodyAndKeepsTheOriginal() {
        val root = temp.root
        val body = "word\tcode\t100\n# private body\n"
        File(root, "custom_phrase.dict.yaml").writeText("# dictionary\nname: custom_phrase\nversion: '1'\n...\n$body")
        val s = session(snapshot("wanxiang.schema.yaml", source + "custom_phrase: {dictionary: custom_phrase}\n"))
        s.prepareDictionary("custom_phrase", "personal")
        s.change("wanxiang.schema.yaml", "custom_phrase/dictionary", y.string("personal"))
        val text = s.changes().getValue("personal.dict.yaml")
        assertTrue(text.endsWith(body))
        assertTrue(text.contains("# dictionary"))
        assertTrue(File(root, "custom_phrase.dict.yaml").readText().endsWith(body))
        assertTrue(s.plan().expected.containsKey("personal.dict.yaml")); assertNull(s.plan().expected["personal.dict.yaml"])
    }

    @Test fun anExistingDictionaryIsNeverReplacedByRenaming() {
        File(temp.root, "personal.dict.yaml").writeText("keep existing")
        val s = session(snapshot("wanxiang.schema.yaml", source))
        s.prepareDictionary("wanxiang", "personal")
        s.change("wanxiang.schema.yaml", "translator/dictionary", y.string("personal"))
        assertFalse(s.changes().containsKey("personal.dict.yaml"))
        assertEquals("keep existing", File(temp.root, "personal.dict.yaml").readText())
    }

    @Test fun multipleRenamesBeforeSavingKeepTheDictionaryBodyAndOnlyCreateTheFinalName() {
        val body = "word\tcode\t100\n"
        File(temp.root, "wanxiang.dict.yaml").writeText("name: wanxiang\nversion: '1'\n...\n$body")
        val s = session(snapshot("wanxiang.schema.yaml", source))
        s.prepareDictionary("wanxiang", "first")
        s.change("wanxiang.schema.yaml", "translator/dictionary", y.string("first"))
        s.prepareDictionary("first", "second")
        s.change("wanxiang.schema.yaml", "translator/dictionary", y.string("second"))
        assertTrue(s.changes().getValue("second.dict.yaml").endsWith(body))
        assertFalse(s.changes().containsKey("first.dict.yaml"))
        assertTrue(File(temp.root, "wanxiang.dict.yaml").isFile)
    }
    @Test fun aMissingOriginalDictionaryCannotCreateAnEmptyReplacement() {
        val s = session(snapshot("wanxiang.schema.yaml", source))
        assertTrue(runCatching { s.prepareDictionary("wanxiang", "personal") }.isFailure)
        assertFalse(s.dirty)
        assertTrue(s.changes().isEmpty())
    }

    @Test fun openingSettingsPreservesTheDeployedDefaultSchemaAndUserDatabasesByteForByte() {
        val root = temp.newFolder("deployed")
        File(root, "build").mkdirs()
        File(root, "wanxiang.userdb").mkdirs()
        File(root, "default.yaml").writeText("schema_list: [{schema: wanxiang}]\n")
        File(root, "wanxiang.schema.yaml").writeText(source)
        File(root, "build/default.yaml").writeText("schema_list: [{schema: wanxiang}]\nmenu: {page_size: 8}\n")
        File(root, "build/wanxiang.schema.yaml").writeText(source)
        File(root, "wanxiang.userdb/CURRENT").writeText("live database")
        val before = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to (it.readText() to it.lastModified()) }
        repeat(3) {
            assertNotNull(WanxiangAdvancedFiles.snapshot(root, "default.yaml").effective)
            assertNotNull(WanxiangAdvancedFiles.snapshot(root, "wanxiang.schema.yaml").effective)
        }
        val after = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to (it.readText() to it.lastModified()) }
        assertEquals(before, after)
    }

    @Test fun aSharedDefaultWithoutAUserCopyCanBeEditedThroughACustomPatch() {
        val user = temp.newFolder("shared-user")
        val shared = temp.newFolder("shared-data")
        val defaults = "schema_list: [{schema: wanxiang}]\nmenu: {page_size: 6}\n"
        File(shared, "default.yaml").writeText(defaults)
        File(user, "wanxiang.schema.yaml").writeText(source)
        assertTrue("default.yaml" in WanxiangAdvancedFiles.files(user))
        val snapshot = WanxiangAdvancedFiles.snapshot(user, "default.yaml", shared)
        assertEquals(defaults, snapshot.source)
        assertNull(snapshot.effective)
        assertTrue(snapshot.expected.containsKey("default.yaml")); assertNull(snapshot.expected["default.yaml"])
        val s = WanxiangAdvancedSession(WanxiangTarget("rime", user.path), false).apply { add(snapshot) }
        s.change("default.yaml", "menu/page_size", y.scalar(8))
        assertEquals(setOf("default.custom.yaml"), s.changes().keys)
        assertFalse(File(user, "default.yaml").exists())
        assertFalse(File(user, "build").exists())
        assertEquals(defaults, File(shared, "default.yaml").readText())
        WanxiangAdvancedFiles.verify(user, snapshot.expected)
    }

    @Test fun aDamagedExistingDeploymentIsRepairedButAFreshOrHealthyProfileIsNotRebuilt() {
        val user = temp.newFolder("repair")
        File(user, "wanxiang.schema.yaml").writeText(source)
        assertFalse(WanxiangAdvancedFiles.needsDeploymentRepair(user))
        File(user, "installation.yaml").writeText("installation_id: test\n")
        File(user, "build").mkdirs()
        assertTrue(WanxiangAdvancedFiles.needsDeploymentRepair(user))
        File(user, "build/default.yaml").writeText("schema_list: [{schema: wanxiang}]\n")
        assertFalse(WanxiangAdvancedFiles.needsDeploymentRepair(user))
    }
}
