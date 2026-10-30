package com.kkgosu.reasoned

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.ui.text.MarkdownContentView
import com.kkgosu.reasoned.ui.text.lessonMarkdown
import com.kkgosu.reasoned.ui.text.markdownBody
import com.kkgosu.reasoned.ui.text.setLessonMarkdown
import com.kkgosu.reasoned.ui.text.setToolMarkdown
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

    @Test
    fun sampleSessionIncludesNativeMarkdownAndMathFixtures() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
        val lesson = com.kkgosu.reasoned.controllers.MockLesson(app)
        try {
            val opening = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { lesson.runtime.open(lesson.sessionId) }
            kotlinx.coroutines.withTimeout(8000) { opening.join() }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val renderer = lessonMarkdown(app)
                val samples = lesson.session().items.map { renderer.toMarkdown(it.text) }
                fun count(type: Class<*>) = samples.sumOf { it.getSpans(0, it.length, type).size }
                assertTrue(count(TableRowSpan::class.java) >= 6)
                assertTrue(count(io.noties.markwon.core.spans.StrongEmphasisSpan::class.java) >= 8)
                assertTrue(count(io.noties.markwon.core.spans.CodeSpan::class.java) >= 4)
                assertTrue(count(io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan::class.java) >= 10)
                assertTrue(lesson.session().items.any { it.text.contains("> [!IMPORTANT]") })
                assertTrue(samples.any { it.toString().contains(app.getString(R.string.markdown_important)) &&
                    !it.toString().contains("[!IMPORTANT]") &&
                    it.getSpans(0, it.length, io.noties.markwon.core.spans.BlockQuoteSpan::class.java).isNotEmpty() })
                val state = lesson.session()
                assertEquals(state.items.size, state.items.map { it.id }.distinct().size)
                assertEquals(0, lesson.requests)
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { lesson.runtime.stop() }.join()
            lesson.store.close()
        }
    }

    @Test
    fun nativeTablesCapColumnsWrapAndScrollWithoutMovingProse() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
            val renderer = lessonMarkdown(app)
            val density = app.resources.displayMetrics.density
            val width = (320 * density).toInt()
            val limit = app.resources.getDimensionPixelSize(R.dimen.table_column_max_width)
            val gutter = app.resources.getDimensionPixelSize(R.dimen.space_24)
            for (size in listOf(16f, 32f)) for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val content = MarkdownContentView(app).apply { layoutDirection = direction }
                val text = TextView(app).apply {
                    setTextAppearance(R.style.TextAppearance_Harness_Prose)
                    textSize = size
                    setTextIsSelectable(true)
                    setPaddingRelative(gutter, 0, gutter, 0)
                }
                content.addView(text)
                val long = "Long cell content must wrap inside a bounded column rather than expand forever."
                renderer.setLessonMarkdown(text, "Before\n\n" +
                    "| One | Two | Three | Four | Five |\n| --- | :---: | ---: | --- | --- |\n" +
                    "| **Bold** | $long | $long | ${'$'}x^2${'$'} | last |\n\nAfter")
                content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                content.layout(0, 0, width, content.measuredHeight)
                assertEquals(3, content.childCount)
                assertEquals("Before", text.text.toString())
                assertEquals("After", (content.getChildAt(2) as TextView).text.toString())
                val scroll = content.getChildAt(1) as HorizontalScrollView
                val table = scroll.getChildAt(0) as TableLayout
                assertEquals(width, scroll.width)
                assertEquals(gutter, scroll.paddingStart)
                assertEquals(gutter, scroll.paddingEnd)
                assertFalse(scroll.clipToPadding)
                assertEquals(gutter, (content.getChildAt(2) as TextView).paddingStart)
                assertTrue(table.width > scroll.width)
                val header = table.getChildAt(0) as TableRow
                val row = table.getChildAt(1) as TableRow
                for (column in 0..4) {
                    val cell = row.getChildAt(column) as TextView
                    assertTrue("Column $column wider than cap", cell.width <= limit)
                    assertEquals(header.getChildAt(column).width, cell.width)
                    assertTrue(cell.isTextSelectable)
                }
                assertTrue((row.getChildAt(1) as TextView).lineCount > 1)
                val bold = (row.getChildAt(0) as TextView).text as Spanned
                assertEquals(1, bold.getSpans(0, bold.length, io.noties.markwon.core.spans.StrongEmphasisSpan::class.java).size)
                val math = (row.getChildAt(3) as TextView).text as Spanned
                assertEquals(1, math.getSpans(0, math.length, io.noties.markwon.image.AsyncDrawableSpan::class.java).size)
                scroll.scrollTo(Int.MAX_VALUE, 0)
                assertTrue(scroll.scrollX > 0)
                assertEquals(0, text.scrollX)
                assertEquals(0, content.getChildAt(2).scrollX)
                text.markdownBody.visibility = View.GONE
                assertEquals(View.GONE, content.visibility)
                text.markdownBody.visibility = View.VISIBLE
                renderer.setLessonMarkdown(text, "Replacement without a table")
                assertEquals(1, content.childCount)
                assertEquals(View.VISIBLE, text.visibility)
                assertEquals("Replacement without a table", text.text.toString())
                renderer.setToolMarkdown(text, "| A | B |\n| --- | --- |\n| 1 | 2 |")
                content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                content.layout(0, 0, width, content.measuredHeight)
                val small = content.getChildAt(1) as HorizontalScrollView
                assertTrue(small.getChildAt(0).width <= small.width)
                assertEquals(View.GONE, text.visibility) // A table-first document has no blank prose row.
                renderer.setLessonMarkdown(text, "```text\n| Not | A table |\n| --- | --- |\n```")
                assertEquals(1, content.childCount)
                assertTrue(text.text.contains("| Not | A table |"))
                renderer.setLessonMarkdown(text, "> | A | B |\n> | --- | --- |\n> | 1 | 2 |")
                assertEquals(1, content.childCount) // Nested tables preserve their quote via span fallback.
                renderer.setLessonMarkdown(text, "| A | B |\n| --- | --- |\n" + "| 1 | 2 |\n".repeat(256))
                assertEquals(1, content.childCount) // Large model output cannot create unbounded cell views.
                val fallback = text.text as Spanned
                assertEquals(257, fallback.getSpans(0, fallback.length, TableRowSpan::class.java).size)
            }
        }
    }

    @Test
    fun transcriptTablesFollowDisclosuresAndClearOnTitleRebind() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
            val adapter = com.kkgosu.reasoned.ui.adapters.LessonAdapter(lessonMarkdown(app))
            val item = com.kkgosu.reasoned.model.LessonDisplayItem.TextDisplayItem(1,
                "Before\n\n| A | B |\n| --- | --- |\n| 1 | 2 |",
                com.kkgosu.reasoned.model.LessonDisplayItem.Role.THINKING)
            adapter.submit(listOf(item))
            val holder = adapter.onCreateViewHolder(android.widget.FrameLayout(app), adapter.getItemViewType(0))
            adapter.onBindViewHolder(holder, 0)
            val text = holder.itemView.findViewById<TextView>(R.id.item_text)
            val content = text.markdownBody as MarkdownContentView
            assertEquals(2, content.childCount)
            assertEquals(View.GONE, content.visibility)
            holder.itemView.findViewById<View>(R.id.item_label).performClick()
            assertEquals(View.VISIBLE, content.visibility)
            adapter.submit(listOf(item.copy(text = "Title", role = com.kkgosu.reasoned.model.LessonDisplayItem.Role.TITLE)))
            adapter.onBindViewHolder(holder, 0)
            assertEquals(1, content.childCount)
            assertEquals(View.VISIBLE, text.visibility)
            assertEquals("Title", text.text.toString())
        }
    }

    @Test
    fun probeAssessmentKeepsStrandsInSeparateBlocksAndSummaryLast() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
            val assessment = com.kkgosu.reasoned.model.ProbeAssessment(
                (1..3).map { com.kkgosu.reasoned.model.ProbeAssessment.Strand("Strand $it", "Evidence **$it**") },
                "Overall conclusion"
            )
            val row = com.kkgosu.reasoned.model.LessonDisplayItem.TextDisplayItem(
                1, assessment.transcript(), com.kkgosu.reasoned.model.LessonDisplayItem.Role.ASSESSMENT)
            val state = com.kkgosu.reasoned.model.RuntimeSession(
                goal = "Test", model = "offline", items = listOf(row), assessment = assessment)
            val item = state.displayItems.single()
            assertEquals(item, com.kkgosu.reasoned.model.LessonDisplayItem.restore(item.save()))
            val adapter = com.kkgosu.reasoned.ui.adapters.LessonAdapter(lessonMarkdown(app))
            adapter.submit(state.displayItems)
            val holder = adapter.onCreateViewHolder(android.widget.FrameLayout(app), adapter.getItemViewType(0))
            adapter.onBindViewHolder(holder, 0)
            val strands = holder.itemView.findViewById<android.widget.LinearLayout>(R.id.assessment_strands)
            assertEquals(3, strands.childCount)
            repeat(3) { index ->
                val block = strands.getChildAt(index) as android.widget.LinearLayout
                assertTrue(block.background is android.graphics.drawable.GradientDrawable)
                assertEquals("Strand ${index + 1}", (block.getChildAt(0) as TextView).text.toString())
                val body = (block.getChildAt(1) as MarkdownContentView).getChildAt(0) as TextView
                assertEquals("Evidence ${index + 1}", body.text.toString())
                assertTrue(body.isTextSelectable)
            }
            val summary = holder.itemView.findViewById<TextView>(R.id.assessment_summary)
            assertEquals("Overall conclusion", summary.text.toString())
            val parent = summary.markdownBody.parent as android.view.ViewGroup
            assertTrue(parent.indexOfChild(strands) < parent.indexOfChild(summary.markdownBody))
            adapter.submit(state.copy(assessment = assessment.copy(strands = assessment.strands.take(1), summary = "")).displayItems)
            adapter.onBindViewHolder(holder, 0)
            assertEquals(1, strands.childCount)
            assertEquals(View.GONE, summary.markdownBody.visibility)
        }
    }

    @Test
    fun tableRowsRenderWithinTheProseWidth() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = ApplicationProvider.getApplicationContext<ReasonedApplication>()
            val renderer = lessonMarkdown(app)
            val source = """
                | Без указания на определённые сведения | Понятно, какие именно сведения |
                | --- | --- |
                | I need information about the trip. | **The information** in your email is out of date. |
                | Мне нужна информация о поездке. | Информация в твоём письме устарела. |
            """.trimIndent()
            for (textSize in listOf(16f, 32f)) {
                val view = TextView(app).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setTextAppearance(R.style.TextAppearance_Harness_Prose)
                    this.textSize = textSize
                }
                renderer.setMarkdown(view, source)
                val text = view.text as Spanned
                assertEquals(3, text.getSpans(0, text.length, TableRowSpan::class.java).size)
                assertFalse(text.contains("---"))
                val width = (320 * app.resources.displayMetrics.density).toInt()
                // TableRowSpan learns the available width on its first draw, then requests reflow.
                repeat(2) {
                    view.text = view.text
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    view.layout(0, 0, width, view.measuredHeight)
                    val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                    } finally {
                        bitmap.recycle()
                    }
                }
                assertTrue(view.height > view.lineHeight * 3)
            }
        }
    }
}
