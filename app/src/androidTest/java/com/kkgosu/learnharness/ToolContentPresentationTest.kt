package com.kkgosu.learnharness

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.learnharness.model.RuntimeQuestion
import com.kkgosu.learnharness.ui.text.ToolContentPresentation
import com.kkgosu.learnharness.ui.text.lessonMarkdown
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolContentPresentationTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private fun nodes(root: Node): List<Node> = buildList {
        add(root)
        var child = root.firstChild
        while (child != null) {
            addAll(nodes(child)); child = child.next
        }
    }

    private fun math(root: Node) = nodes(root).mapNotNull {
        when (it) {
            is JLatexMathNode -> it.latex(); is JLatexMathBlock -> it.latex(); else -> null
        }
    }

    private fun code(root: Node) = nodes(root).mapNotNull {
        when (it) {
            is Code -> it.literal; is FencedCodeBlock -> it.literal; is IndentedCodeBlock -> it.literal; else -> null
        }
    }

    private fun prose(root: Node) =
        nodes(root).filterIsInstance<Text>().joinToString("") { it.literal }

    @Test
    fun repairsKnownJsonSwallowedCommandsAndOverEscapingWithoutInventingUnknownCommands() {
        val examples = listOf(
            "x=\nu" to "x=\\nu",
            "\u000crac{1}{2}" to "\\frac{1}{2}",
            "\text{word}" to "\\text{word}",
            "\rangle" to "\\rangle",
            "x\t + y" to "x\t + y",
            "x\r\nu" to "x\r\nu",
            "\\alpha+\\frac{1}{2}" to "\\alpha+\\frac{1}{2}",
            "\\\\alpha+\\\\frac{1}{2}" to "\\alpha+\\frac{1}{2}",
            "\\\\unknownword{x}" to "\\\\unknownword{x}",
            "50\\\\%" to "50\\%",
            "x\\\\% explanation" to "x\\\\% explanation",
            "\\\\alpha % preserve \\\\frac{1}{2}\n+1" to "\\alpha % preserve \\\\frac{1}{2}\n+1"
        )
        examples.forEach { (source, expected) ->
            val result = ToolContentPresentation.repairMath(source, display = false)
            assertEquals(source, expected, result)
            assertEquals(
                "Recovery must be idempotent: $source",
                result,
                ToolContentPresentation.repairMath(result, false)
            )
        }
        assertEquals("x=\nu", ToolContentPresentation.repairMath("x=\nu", display = true))
        assertEquals("x=\nabla", ToolContentPresentation.repairMath("x=\nabla", display = true))
        assertEquals("a".repeat(8001), ToolContentPresentation.repairMath("a".repeat(8001), false))
    }

    @Test
    fun nativeRowsNestedStructuresCommentsAndDisplayNewlinesRemainLiteral() {
        val literal = listOf(
            "\\begin{matrix}a\\\\b\\end{matrix}",
            "\\begin{aligned}a&=b\\\\[2pt]c&=d\\end{aligned}",
            "\\sum_{\\substack{i=1\\\\j=1}} x_{ij}",
            "\\begin{matrix}a% preserve \\\\alpha\n\\\\b\\end{matrix}"
        )
        literal.forEach { assertEquals(it, ToolContentPresentation.repairMath(it, true)) }
        val encoded = "\\\\begin{matrix}a\\\\\\\\b\\\\end{matrix}"
        val expected = literal[0]
        assertEquals(expected, ToolContentPresentation.repairMath(encoded, true))
        assertEquals(expected, ToolContentPresentation.repairMath(expected, true))
        assertEquals(
            "\\substack{i=1\\\\j=1}",
            ToolContentPresentation.repairMath("\\\\substack{i=1\\\\\\\\j=1}", false)
        )
    }

    @Test
    fun nativeCodeBoundariesAndLinkDestinationsAreNotReparsedOrRewritten() {
        val renderer = lessonMarkdown(app)
        val source = "Heading\\n\\nThen \$\\\\frac{1}{2}\$.\n\n" +
                "`literal \\n \\n \$\\\\alpha\$`\n\n" +
                "> - Nested code:\n>\n>   ```text\n>   literal \\n\\n \$\\\\alpha\$\n>   ```\n\n" +
                "    indented literal \\n\\n \$\\\\alpha\$\n\n" +
                "[source](https://example.org/path\\n\\n) and C:\\new\\notes\n\n" +
                "\$\u000crac{1}{2}+x=\nu\$\n\n\$\$\n\\alpha\nu\n\$\$"
        val tree = renderer.parse(source)
        val originalCode = code(tree)
        val destinations = nodes(tree).filterIsInstance<Link>().map { it.destination }
        assertTrue(originalCode.size >= 3)
        ToolContentPresentation.recover(tree)
        assertEquals(originalCode, code(tree))
        assertEquals(destinations, nodes(tree).filterIsInstance<Link>().map { it.destination })
        assertTrue(prose(tree).contains("Heading\n\nThen"))
        assertTrue(prose(tree).contains("C:\\new\\notes"))
        val displayed = math(tree)
        assertTrue(displayed.any { it.contains("\\frac{1}{2}") })
        assertTrue(displayed.any { it.contains("x=\\nu") })
        assertTrue(displayed.any { it.contains("\\alpha\nu") }) // Physical display-math newline, not \\nu.
        ToolContentPresentation.recover(tree)
        assertEquals(displayed, math(tree)); assertEquals(originalCode, code(tree))
    }

    @Test
    fun displayRecoveryNeverChangesSavedOptionIdentityCommitmentOrGrading() {
        val renderer = lessonMarkdown(app)
        val label = "\$\\\\alpha\$"
        val q = RuntimeQuestion(
            "saved",
            "Question\\n\\nDetails",
            listOf(label, "Other"),
            "opaque-a",
            "\$\u000crac{1}{2}\$",
            values = listOf("opaque-a", "opaque-b"),
            correctValues = listOf("opaque-a"),
            requiresChoice = true
        )
        val original = q.json().toString()
        val answer = q.answer("Learner \\n remains literal", -1, 80, "", false, listOf("opaque-a"))
        val savedAnswer = answer.toString()
        listOf(
            q.text,
            label,
            q.explanation
        ).forEach { ToolContentPresentation.recover(renderer.parse(it)) }
        assertEquals(original, q.json().toString())
        assertEquals(savedAnswer, answer.toString())
        assertTrue(answer.getBoolean("correct"))
        assertEquals(label, answer.getString("answer"))
        assertEquals("opaque-a", answer.getJSONArray("selectedValues").getString(0))
        assertFalse(q.answer("", -1, 80, "", false, listOf("opaque-b")).getBoolean("correct"))
    }
}
