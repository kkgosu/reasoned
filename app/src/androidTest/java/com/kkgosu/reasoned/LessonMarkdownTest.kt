package com.kkgosu.reasoned

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.reasoned.ui.text.lessonMarkdown
import io.noties.markwon.ext.tables.TableRowSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LessonMarkdownTest {
    @Test
    fun headingsStayCloseToTheirBodyWithoutChangingParagraphSpacing() {
        val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
        val renderer = lessonMarkdown(app)
        for (level in 1..6) {
            val marker = "#".repeat(level)
            val rendered = renderer.toMarkdown("$marker Heading\n\nBody\n\nNext paragraph")
            assertEquals("Heading\nBody\n\nNext paragraph", rendered.toString())
            assertEquals(1, rendered.getSpans(0, rendered.length,
                io.noties.markwon.core.spans.HeadingSpan::class.java).size)
            assertEquals("Heading", renderer.toMarkdown("$marker Heading").toString())
        }
        assertEquals("Heading\nFirst\nSecond", renderer.toMarkdown("### Heading\n\n- First\n- Second").toString())
        assertEquals("Before\n\nHeading\nBody", renderer.toMarkdown("Before\n\n### Heading\n\nBody").toString())
        val table = renderer.toMarkdown("### Heading\n\n| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertTrue(table.toString().startsWith("Heading\n\u00a0"))
        assertEquals(2, table.getSpans(0, table.length, TableRowSpan::class.java).size)
    }

    @Test
    fun importantCalloutsKeepBodyFormattingAndLeaveOrdinaryQuotesAndCodeAlone() {
        val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
        val renderer = lessonMarkdown(app)
        val label = app.getString(R.string.markdown_important)
        for (opening in listOf("> [!IMPORTANT]\n> ", "> [!IMPORTANT] ")) {
            val tree = renderer.parse(opening + "A **key** point.\n>\n> Another paragraph.\n\nOutside.")
            repeat(2) { // Rendering the same parsed tree must not duplicate the label.
                val rendered = renderer.render(tree)
                assertEquals("$label\n\nA key point.\n\nAnother paragraph.\n\nOutside.", rendered.toString())
                val quotes = rendered.getSpans(0, rendered.length, io.noties.markwon.core.spans.BlockQuoteSpan::class.java)
                assertEquals(1, quotes.size)
                assertTrue(rendered.getSpanEnd(quotes.single()) <= rendered.toString().indexOf("Outside."))
                val bold = rendered.getSpans(0, rendered.length, io.noties.markwon.core.spans.StrongEmphasisSpan::class.java)
                assertEquals(2, bold.size)
                assertTrue(bold.any { rendered.subSequence(rendered.getSpanStart(it), rendered.getSpanEnd(it)).toString() == label })
            }
        }
        assertEquals(label, renderer.toMarkdown("> [!IMPORTANT]").toString())
        val list = renderer.toMarkdown("> [!IMPORTANT]\n>\n> - First\n> - Second")
        assertTrue(list.toString().startsWith("$label\n\nFirst"))
        assertFalse(list.contains("[!IMPORTANT]"))
        for (source in listOf(
            "[!IMPORTANT] Plain prose",
            "> Ordinary quote with [!IMPORTANT] inside.",
            "> [!IMPORTANT]suffix",
            "> [!NOTE]\n> Unhandled type stays literal.",
            "> `[!IMPORTANT]`",
            "```markdown\n> [!IMPORTANT]\n> Literal code.\n```",
            "> ```markdown\n> [!IMPORTANT]\n> ```",
        )) {
            val rendered = renderer.toMarkdown(source)
            assertTrue(rendered.contains(if (source.contains("[!NOTE]")) "[!NOTE]" else "[!IMPORTANT]"))
            assertEquals(0, rendered.getSpans(0, rendered.length, io.noties.markwon.core.spans.StrongEmphasisSpan::class.java).size)
        }
    }
}
