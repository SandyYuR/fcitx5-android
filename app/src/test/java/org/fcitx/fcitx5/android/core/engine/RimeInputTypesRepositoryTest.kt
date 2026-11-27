/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import org.fcitx.fcitx5.android.data.WanxiangYaml
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.File

class RimeInputTypesRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val schema = """
        schema:
          schema_id: wanxiang
          name: 万象拼音
        menu:
          page_size: 5
        input_types:
          enabled: [zrm] #启用哪些build就部署哪些
          reset: zrm
          options:
            - {option: pinyin, label: 全拼}
            - {option: zrm, label: 自然码}
            - {option: flypy, label: 小鹤双拼}
        translator:
          dictionary: wanxiang
    """.trimIndent() + "\n"

    private val custom = """
        # 本文件的工作目录是在用户根目录
        patch:
          # 启用清单决定可部署、可切换的输入类型
          input_types/enabled: [zrm]
          input_types/reset: zrm
          menu/page_size: 6
    """.trimIndent() + "\n"

    private fun user(schemaText: String = schema, customText: String? = custom): File {
        val dir = temp.newFolder()
        File(dir, "wanxiang.schema.yaml").writeText(schemaText)
        if (customText != null) File(dir, "wanxiang.custom.yaml").writeText(customText)
        return dir
    }

    private fun scalar(node: org.yaml.snakeyaml.nodes.Node?) = (node as ScalarNode).value

    private fun patchInputTypes(file: File = File(temp.root, "wanxiang.custom.yaml")): Pair<List<String>, String?> {
        val yaml = WanxiangYaml()
        val root = yaml.document(file.readText())
        val patch = yaml.member(root, "patch") as MappingNode
        val enabled = (yaml.member(patch, "input_types/enabled") as SequenceNode).value
            .map { (it as ScalarNode).value }
        val reset = (yaml.member(patch, "input_types/reset") as ScalarNode).value
        return enabled to reset
    }

    @Test fun readsDeclaredTypesAndAppliesThePatch() {
        val dir = user()
        val types = requireNotNull(RimeInputTypesRepository.read(dir))

        assertEquals(listOf("pinyin", "zrm", "flypy"), types.types.map { it.option })
        assertEquals(listOf("全拼", "自然码", "小鹤双拼"), types.types.map { it.label })
        assertEquals(listOf("zrm"), types.enabled)
        assertEquals("zrm", types.reset)
        assertEquals("自然码", types.current?.label)
        assertEquals("自然码", types.summary())
    }

    @Test fun fallsBackToSchemaWhenThePatchIsMissing() {
        val dir = user(customText = null)
        val types = requireNotNull(RimeInputTypesRepository.read(dir))

        assertEquals(listOf("zrm"), types.enabled)
        assertEquals("zrm", types.reset)
        assertNull(RimeInputTypesRepository.read(temp.newFolder("empty")))
    }

    @Test fun unknownTypesAreDroppedFromTheEffectiveSelection() {
        val dir = user(customText = "patch:\n  input_types/enabled: [dnsp]\n  input_types/reset: dnsp\n")
        val types = requireNotNull(RimeInputTypesRepository.read(dir))

        // dnsp is not in options, so the declaration falls back to nothing usable.
        assertTrue(types.enabled.isEmpty())
        assertNull(types.reset)
        assertNull(types.current)
    }

    @Test fun selectRewritesOnlyResetAndKeepsEverythingElse() {
        val original = custom.replace("[zrm]", "[zrm, flypy]")
        val dir = user(customText = original)
        val state = RimeInputTypesRepository.select(dir, "flypy")
        val file = File(dir, "wanxiang.custom.yaml")
        val text = file.readText()

        assertEquals(listOf("zrm", "flypy"), state.enabled)
        assertEquals("flypy", state.reset)
        assertEquals("小鹤双拼", state.summary())
        assertEquals(listOf("zrm", "flypy") to "flypy", patchInputTypes(file))
        assertEquals(original.replace("input_types/reset: zrm", "input_types/reset: flypy"), text)
        assertTrue(text.contains("# 本文件的工作目录是在用户根目录"))
        assertTrue(text.contains("# 启用清单决定可部署、可切换的输入类型"))
        assertTrue(text.contains("menu/page_size: 6"))
    }

    @Test fun selectIsIdempotentAndRejectsUnknownOptionsWithoutTouchingTheFile() {
        val dir = user(customText = custom.replace("[zrm]", "[zrm, pinyin]"))
        val file = File(dir, "wanxiang.custom.yaml")
        val before = file.readText()

        RimeInputTypesRepository.select(dir, "pinyin")
        val once = file.readText()
        RimeInputTypesRepository.select(dir, "pinyin")
        assertEquals(once, file.readText())
        assertTrue(once.contains("input_types/enabled: [zrm, pinyin]"))

        val rejected = runCatching { RimeInputTypesRepository.select(dir, "unknown") }
        assertTrue(rejected.isFailure)
        assertEquals(once, file.readText())
        assertTrue(before != once)
    }

    @Test fun selectAcceptsTheNestedForm() {
        val dir = user(
            customText = "patch:\n" +
                "  input_types:\n" +
                "    options:\n" +
                "      - {option: pinyin, label: 全拼}\n" +
                "      - {option: zrm, label: 自然码}\n" +
                "      - {option: flypy, label: 小鹤双拼}\n" +
                "    enabled: [zrm, pinyin]\n" +
                "    reset: zrm\n" +
                "  menu/page_size: 6\n"
        )
        RimeInputTypesRepository.select(dir, "pinyin")

        val text = File(dir, "wanxiang.custom.yaml").readText()
        // The nested form is edited in place, not duplicated with dotted keys.
        assertTrue(text.contains("enabled:"))
        assertTrue(!text.contains("input_types/enabled:"))
        val yaml = WanxiangYaml()
        val patch = yaml.member(yaml.document(text), "patch") as MappingNode
        val group = yaml.member(patch, "input_types") as MappingNode
        assertEquals("pinyin", scalar(yaml.member(group, "reset")))
        assertEquals(listOf("zrm", "pinyin"), (yaml.member(group, "enabled") as SequenceNode).value.map { (it as ScalarNode).value })
    }

    @Test fun selectCreatesThePatchWhenTheFileIsAbsent() {
        val dir = user(customText = null)
        RimeInputTypesRepository.select(dir, "zrm")

        val file = File(dir, "wanxiang.custom.yaml")
        assertTrue(file.isFile)
        assertEquals("patch:\n  input_types/reset: zrm\n", file.readText())
    }

    @Test fun readBindsToTheActiveSchemaInsteadOfTheFirstFile() {
        val dir = user()
        File(dir, "english.schema.yaml").writeText(
            schema.replace("wanxiang", "english").replace("自然码", "英文自然码")
        )
        File(dir, "english.custom.yaml").writeText("patch:\n  input_types/enabled: [flypy]\n  input_types/reset: flypy\n")

        val english = requireNotNull(RimeInputTypesRepository.read(dir, "english"))
        assertEquals("english", english.schemaId)
        assertEquals("小鹤双拼", english.summary())
        assertEquals(listOf("flypy"), english.enabled)
        assertEquals("english.custom.yaml", english.customFile.name)

        // An id that is not a plain schema file name never escapes the user directory.
        assertNull(RimeInputTypesRepository.read(dir, "../wanxiang"))
    }

    @Test fun selectWritesTheCustomFileOfTheActiveSchemaOnly() {
        val dir = user()
        File(dir, "english.schema.yaml").writeText(
            schema.replace("wanxiang", "english").replace("自然码", "英文自然码")
                .replace("enabled: [zrm]", "enabled: [zrm, pinyin]")
        )
        val wanxiangCustom = File(dir, "wanxiang.custom.yaml")
        val before = wanxiangCustom.readText()

        val state = RimeInputTypesRepository.select(dir, "pinyin", "english")
        assertEquals("english", state.schemaId)
        assertEquals(listOf("zrm", "pinyin"), state.enabled)
        assertTrue(before == wanxiangCustom.readText())
        assertEquals("patch:\n  input_types/reset: pinyin\n", File(dir, "english.custom.yaml").readText())
    }

    @Test fun patchHeaderWithATrailingCommentAndBlockEnabledListIsPreserved() {
        val dir = user(customText = "patch: # user patches\n  input_types/enabled:\n    - zrm\n    - flypy\n  input_types/reset: zrm\n")
        RimeInputTypesRepository.select(dir, "flypy")

        val file = File(dir, "wanxiang.custom.yaml")
        val text = file.readText()
        assertTrue(text.contains("patch: # user patches"))
        assertTrue(text.contains("- zrm"))
        assertEquals(listOf("zrm", "flypy") to "flypy", patchInputTypes(file))
    }

    @Test fun nestedBlockEnabledListIsPreservedInPlace() {
        val dir = user(
            customText = "patch:\n" +
                "  input_types:\n" +
                "    options:\n" +
                "      - {option: pinyin, label: 全拼}\n" +
                "      - {option: zrm, label: 自然码}\n" +
                "      - {option: flypy, label: 小鹤双拼}\n" +
                "    enabled:\n" +
                "      - zrm\n" +
                "      - pinyin\n" +
                "    reset: zrm\n" +
                "  menu/page_size: 6\n"
        )
        RimeInputTypesRepository.select(dir, "pinyin")

        val text = File(dir, "wanxiang.custom.yaml").readText()
        assertTrue(text.contains("- zrm"))
        val yaml = WanxiangYaml()
        val patch = yaml.member(yaml.document(text), "patch") as MappingNode
        val group = yaml.member(patch, "input_types") as MappingNode
        assertEquals(listOf("zrm", "pinyin"), (yaml.member(group, "enabled") as SequenceNode).value.map { (it as ScalarNode).value })
        assertEquals("pinyin", scalar(yaml.member(group, "reset")))
    }


    @Test fun customOptionsOverrideTheSchemaCatalog() {
        val dir = user(
            customText = "patch:\n" +
                "  input_types/options:\n" +
                "    - {option: pinyin, label: 全拼}\n" +
                "    - {option: zrm, label: 自然码}\n" +
                "  input_types/enabled: [zrm]\n" +
                "  input_types/reset: zrm\n"
        )
        val types = requireNotNull(RimeInputTypesRepository.read(dir))
        assertEquals(listOf("pinyin", "zrm"), types.types.map { it.option })
        assertEquals("自然码", types.summary())

        // Validation must use the effective catalog: flypy only exists in the schema file.
        assertTrue(runCatching { RimeInputTypesRepository.select(dir, "flypy") }.isFailure)
    }

    @Test fun arrayPathPatchChangesTheEffectiveCatalog() {
        val dir = user(
            customText = "patch:\n" +
                "  input_types/options/@0/option: removed_pinyin\n" +
                "  input_types/enabled: [zrm]\n" +
                "  input_types/reset: zrm\n"
        )
        val types = requireNotNull(RimeInputTypesRepository.read(dir))
        assertEquals(listOf("removed_pinyin", "zrm", "flypy"), types.types.map { it.option })
        assertEquals("自然码", types.summary())

        // pinyin no longer exists after the list patch, so it must not be offered or written.
        assertTrue(runCatching { RimeInputTypesRepository.select(dir, "pinyin") }.isFailure)
        assertEquals(listOf("zrm"), RimeInputTypesRepository.read(dir)?.enabled)

        // Selecting keeps the item-level override that produced the effective catalog.
        val selected = RimeInputTypesRepository.select(dir, "zrm")
        assertEquals(listOf("removed_pinyin", "zrm", "flypy"), selected.types.map { it.option })
        assertTrue(File(dir, "wanxiang.custom.yaml").readText().contains("input_types/options/@0/option: removed_pinyin"))
    }

    @Test fun wholeGroupPatchIsAppliedBeforeChildPaths() {
        // Rime sorts patch keys: "input_types" precedes "input_types/options/@0/option".
        // The group replaces the schema block first, then the child path edits its options.
        val dir = user(
            customText = "patch:\n" +
                "  input_types/options/@0/option: removed_pinyin\n" +
                "  input_types:\n" +
                "    enabled: [zrm]\n" +
                "    reset: zrm\n" +
                "    options:\n" +
                "      - {option: pinyin, label: 全拼}\n" +
                "      - {option: zrm, label: 自然码}\n"
        )
        val types = requireNotNull(RimeInputTypesRepository.read(dir))
        assertEquals(listOf("removed_pinyin", "zrm"), types.types.map { it.option })
        assertEquals(listOf("zrm"), types.enabled)
        assertEquals("zrm", types.reset)
        assertEquals("自然码", types.summary())
    }

    @Test fun wholeGroupPatchWithoutResetFallsBackToTheFirstEnabledType() {
        val dir = user(
            customText = "patch:\n" +
                "  input_types:\n" +
                "    enabled: [flypy]\n" +
                "    options:\n" +
                "      - {option: pinyin, label: 全拼}\n" +
                "      - {option: flypy, label: 小鹤双拼}\n"
        )
        val types = requireNotNull(RimeInputTypesRepository.read(dir))
        assertEquals(listOf("flypy"), types.enabled)
        assertNull(types.reset)
        assertEquals("小鹤双拼", types.current?.label)
        assertEquals("小鹤双拼", types.summary())
    }

    @Test fun missingEnabledListMeansEveryDeclaredTypeIsUsable() {
        val dir = user(
            customText = "patch:\n" +
                "  input_types:\n" +
                "    options:\n" +
                "      - {option: pinyin, label: 全拼}\n" +
                "      - {option: zrm, label: 自然码}\n"
        )
        val types = requireNotNull(RimeInputTypesRepository.read(dir))
        assertEquals(listOf("pinyin", "zrm"), types.enabled)
        assertNull(types.reset)
        assertEquals("全拼", types.current?.label)
    }

    @Test fun sameIndentBlockEnabledListIsPreserved() {
        val dir = user(customText = "patch:\n  input_types/enabled:\n  - zrm\n  - flypy\n  input_types/reset: zrm\n")
        RimeInputTypesRepository.select(dir, "flypy")

        val file = File(dir, "wanxiang.custom.yaml")
        val text = file.readText()
        assertTrue(text.contains("- zrm"))
        assertTrue(text.contains("input_types/reset: flypy"))
        assertEquals(listOf("zrm", "flypy") to "flypy", patchInputTypes(file))
    }

    @Test fun choicesAreTheEnabledAndCompiledIntersection() {
        val types = requireNotNull(RimeInputTypesRepository.read(user(customText =
            custom.replace("[zrm]", "[zrm, pinyin]")))).copy(deployed = listOf("pinyin", "flypy"), selected = "pinyin")
        assertEquals(listOf("pinyin"), types.selectableTypes.map { it.option })
        assertEquals("全拼", types.current?.label)
    }

    @Test fun disabledChoicesAreRejectedBeforeApplyingOrWriting() {
        val dir = user()
        val file = File(dir, "wanxiang.custom.yaml")
        val original = file.readText()
        var called = false
        assertTrue(runCatching { RimeInputTypesRepository.select(dir, "pinyin") { called = true; true } }.isFailure)
        assertTrue(!called)
        assertEquals(original, file.readText())
    }

    @Test fun rejectionAndCancellationRestoreExactBytesAndRemoveNewFiles() {
        val original = custom.replace("[zrm]", "[zrm, pinyin]").replace("\n", "\r\n").trimEnd('\r', '\n')
        val dir = user(customText = original)
        val file = File(dir, "wanxiang.custom.yaml")
        assertTrue(runCatching { RimeInputTypesRepository.select(dir, "pinyin") { false } }.isFailure)
        assertEquals(original, file.readText())
        assertTrue(runCatching { RimeInputTypesRepository.select(dir, "pinyin") {
            throw java.util.concurrent.CancellationException("cancelled")
        } }.isFailure)
        assertEquals(original, file.readText())
        val fresh = user(customText = null)
        assertTrue(runCatching { RimeInputTypesRepository.select(fresh, "zrm") { false } }.isFailure)
        assertTrue(!File(fresh, "wanxiang.custom.yaml").exists())
    }

    @Test fun resetPreservesCrLfCommentsOrderAndMissingFinalNewline() {
        val original = "# header\r\npatch:\r\n  menu/page_size: 7\r\n" +
            "  input_types/enabled: [zrm, pinyin] # leave alone\r\n" +
            "  input_types/reset: zrm  # preference\r\n  translator/enable_sentence: false"
        val dir = user(customText = original)
        RimeInputTypesRepository.select(dir, "pinyin")
        assertEquals(original.replace("reset: zrm", "reset: pinyin"), File(dir, "wanxiang.custom.yaml").readText())
    }

    @Test fun mixedLineEndingsAreRejectedWithoutReformattingTheFile() {
        val original = "patch:\r\n  input_types/enabled: [zrm, pinyin]\n  input_types/reset: zrm\r\n"
        val dir = user(customText = original)
        val file = File(dir, "wanxiang.custom.yaml")
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), file.readBytes())
        var applied = false
        val failure = runCatching { RimeInputTypesRepository.select(dir, "pinyin") { applied = true; true } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("换行"))
        assertTrue(!applied)
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test fun forcedResetPathIsEditedWithoutChangingOtherKeys() {
        val original = "patch:\n  input_types/enabled: [zrm, pinyin]\n" +
            "  input_types/reset: zrm\n  input_types/reset/=: zrm # forced\n  menu/page_size: 7\n"
        val dir = user(customText = original)
        RimeInputTypesRepository.select(dir, "pinyin")
        assertEquals(original.replace("reset/=: zrm", "reset/=: pinyin"), File(dir, "wanxiang.custom.yaml").readText())
    }

    @Test fun resetIgnoresTheSameKeyInsideUnrelatedNestedPatches() {
        val original = "patch:\n  input_types/enabled: [zrm, pinyin]\n" +
            "  other_settings:\n    input_types/reset: keep\n    reset: untouched\n" +
            "  input_types/reset: zrm # active preference\n"
        val dir = user(customText = original)
        RimeInputTypesRepository.select(dir, "pinyin")
        assertEquals(original.replace("reset: zrm", "reset: pinyin"), File(dir, "wanxiang.custom.yaml").readText())
    }

    @Test fun invalidOrDuplicatePatchFormsFailWithoutTouchingTheFile() {
        val ambiguous = user(
            customText = "patch:\n  input_types:\n    enabled: [zrm]\n    reset: zrm\n  input_types/enabled: [zrm]\n"
        )
        val file = File(ambiguous, "wanxiang.custom.yaml")
        val before = file.readText()
        assertTrue(runCatching { RimeInputTypesRepository.select(ambiguous, "pinyin") }.isFailure)
        assertEquals(before, file.readText())

        val duplicate = user(customText = "patch:\n  input_types/reset: zrm\n  input_types/reset: zrm\n")
        assertTrue(runCatching { RimeInputTypesRepository.select(duplicate, "pinyin") }.isFailure)
    }
}
