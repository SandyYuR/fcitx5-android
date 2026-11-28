/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data

import org.junit.Assert.*
import org.junit.Test
import org.yaml.snakeyaml.comments.CommentLine
import org.yaml.snakeyaml.comments.CommentType
import org.yaml.snakeyaml.nodes.Node

class WanxiangYamlBudgetTest {
    private val yaml = WanxiangYaml()

    @Test(timeout = 5_000) fun compactExponentialAliasesAreRejectedBeforeValueConstruction() {
        val text = buildString {
            append("a0: &a0 [seed]\n")
            for (i in 1..25) append("a$i: &a$i [*a${i - 1}, *a${i - 1}]\n")
        }
        assertTrue(runCatching { yaml.parse(text) }.isFailure)
    }

    @Test fun aShallowMemoizedSubgraphIsStillCheckedOnADeeperPath() {
        val shared = yaml.sequence(listOf(yaml.string("leaf")))
        var deep: Node = shared
        repeat(39) { deep = yaml.sequence(listOf(deep)) }
        val graph = yaml.sequence(listOf(shared, deep))
        assertTrue(runCatching { yaml.dump(graph) }.isFailure)
    }

    @Test fun scalarAliasesHaveAnExpandedCharacterBudget() {
        val shared = yaml.string("x".repeat(WanxiangYaml.MAX_TEXT / 2 + 1))
        assertTrue(runCatching { yaml.dump(yaml.sequence(listOf(shared, shared))) }.isFailure)
    }

    @Test fun smallSharedMergeGraphsRemainEditableAndComparable() {
        val text = "base: &base {color: blue, enabled: true}\nfirst: {<<: *base, name: first}\nsecond: {<<: *base, name: second}\n"
        val first = yaml.parse(text)
        val second = yaml.parse(text)
        assertTrue(yaml.equivalent(first, second))
        assertTrue(yaml.equivalent(first, yaml.parse(yaml.dump(first))))
        assertFalse(yaml.equivalent(first, yaml.parse(text.replace("name: second", "name: changed"))))
    }

    @Test fun serializedCommentAndFormattingBytesAlsoRespectTheTextLimit() {
        val node = yaml.string("small")
        node.blockComments = listOf(org.yaml.snakeyaml.comments.CommentLine(
            null, null, "x".repeat(WanxiangYaml.MAX_TEXT), org.yaml.snakeyaml.comments.CommentType.BLOCK
        ))
        assertTrue(runCatching { yaml.dump(node) }.isFailure)
    }

    @Test fun overflowingWritesNeverAccumulateBeyondTheWriterBudget() {
        val writer = WanxiangYaml.BoundedWriter(4)
        writer.write("ab")
        writer.write(charArrayOf('_', 'c', 'd', '_'), 1, 2)
        assertEquals("abcd", writer.toString())
        listOf<() -> Unit>(
            { writer.write('e'.code) },
            { writer.write("excess") },
            { writer.write("excess", 1, 2) },
            { writer.write(charArrayOf('e')) },
            { writer.write(charArrayOf('_', 'e'), 1, 1) },
        ).forEach { write ->
            assertSerializedLimit(write)
            assertEquals("abcd", writer.toString())
        }
        writer.write("", 0, 0)
        assertEquals("abcd", writer.toString())
    }

    @Test(timeout = 5_000) fun sharedLargeCommentsAreRejectedDuringDumpEmission() {
        assertSerializedLimit { yaml.dump(sharedLargeCommentGraph()) }
    }

    @Test(timeout = 5_000) fun equivalentRejectsSharedLargeCommentsBeforeLoadingEitherOperand() {
        val commented = sharedLargeCommentGraph()
        assertSerializedLimit { yaml.equivalent(commented, yaml.string("small")) }
        assertSerializedLimit { yaml.equivalent(yaml.string("small"), commented) }
    }

    private fun sharedLargeCommentGraph(): Node {
        val comments = listOf(CommentLine(null, null, "x".repeat(WanxiangYaml.MAX_TEXT / 4), CommentType.BLOCK))
        return yaml.sequence(List(8) { index -> yaml.string("value-$index").apply { blockComments = comments } })
    }

    private fun assertSerializedLimit(operation: () -> Unit) {
        val failure = runCatching(operation).exceptionOrNull()
        // A later SnakeYAML code-point-limit error is not evidence of bounded emission.
        assertTrue("Expected bounded-writer rejection, got $failure", failure is IllegalArgumentException)
        assertEquals("Serialized YAML exceeds the editing limit", failure?.message)
    }

    @Test fun editorCreatedCyclesAndDeepGraphsFailBeforeEmissionOrEquality() {
        val cycle = yaml.sequence(emptyList())
        cycle.value.add(cycle)
        assertTrue(runCatching { yaml.dump(cycle) }.isFailure)
        assertTrue(runCatching { yaml.equivalent(cycle, cycle) }.isFailure)
        var deep: Node = yaml.string("leaf")
        repeat(41) { deep = yaml.sequence(listOf(deep)) }
        assertTrue(runCatching { yaml.dump(deep) }.isFailure)
    }
}
