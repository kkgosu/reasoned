package com.kkgosu.reasoned.ui.text

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import com.kkgosu.reasoned.R
import io.noties.markwon.Markwon
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow as MarkdownRow
import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.node.Paragraph

internal fun Markwon.setLessonMarkdown(view: TextView, source: String) =
    setLessonTree(view, parse(source))

internal fun Markwon.setLessonTree(view: TextView, tree: Node) {
    val container = view.parent as? MarkdownContentView
    if (container == null) setParsedMarkdown(view, render(tree))
    else container.render(this, tree)
}

/** Disclosure/visibility belongs to the whole document, not just its first text segment. */
internal val TextView.markdownBody: View get() = parent as? MarkdownContentView ?: this

/** Native prose plus independently scrolling tables; the first XML TextView supplies typography. */
class MarkdownContentView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    init { orientation = VERTICAL }

    internal fun reset() {
        if (childCount > 1) removeViews(1, childCount - 1)
        getChildAt(0).visibility = VISIBLE
    }

    internal fun render(markwon: Markwon, tree: Node) {
        reset()
        val original = getChildAt(0) as TextView
        val blocks = generateSequence(tree.firstChild) { it.next }.toList()
        val tables = blocks.filterIsInstance<TableBlock>()
        // ponytail: cap native cell views; oversized/nested tables keep Markwon's existing span fallback.
        if (tables.isEmpty() || tables.sumOf { table -> rows(table).sumOf { cells(it).size } } > 512) {
            markwon.setParsedMarkdown(original, markwon.render(tree))
            return
        }
        markwon.setParsedMarkdown(original, markwon.render(Document()))
        original.visibility = GONE
        var prose = Document()
        fun append(view: View) {
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (childCount > 1 || original.visibility == VISIBLE) {
                    topMargin = resources.getDimensionPixelSize(R.dimen.space_8)
                }
            })
        }
        fun flushProse() {
            if (prose.firstChild == null) return
            val text = if (childCount == 1) original.apply { visibility = VISIBLE }
                else textView(original).also(::append)
            markwon.setParsedMarkdown(text, markwon.render(prose))
            prose = Document()
        }
        blocks.forEach { block ->
            block.unlink()
            if (block is TableBlock) {
                flushProse()
                append(tableView(markwon, original, block))
            } else prose.appendChild(block)
        }
        flushProse()
    }

    private fun rows(table: TableBlock): List<Node> =
        generateSequence(table.firstChild) { it.next }.flatMap { section ->
            generateSequence(section.firstChild) { it.next }
        }.filterIsInstance<MarkdownRow>().toList()

    private fun cells(row: Node) = generateSequence(row.firstChild) { it.next }.filterIsInstance<TableCell>().toList()

    private fun textView(original: TextView) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, original.textSize)
        typeface = original.typeface
        setTextColor(original.textColors)
        setLinkTextColor(original.linkTextColors)
        setLineSpacing(original.lineSpacingExtra, original.lineSpacingMultiplier)
        includeFontPadding = original.includeFontPadding
        textDirection = original.textDirection
        textAlignment = original.textAlignment
        gravity = original.gravity
        breakStrategy = original.breakStrategy
        hyphenationFrequency = original.hyphenationFrequency
        setTextIsSelectable(original.isTextSelectable)
    }

    private fun tableView(markwon: Markwon, original: TextView, table: TableBlock): View {
        val border = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        fun divider() = GradientDrawable().apply {
            setColor(context.getColor(R.color.border)); setSize(border, border)
        }
        val padding = resources.getDimensionPixelSize(R.dimen.space_8)
        val layout = TableLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.border))
            setPadding(border, border, border, border)
            dividerDrawable = divider()
            showDividers = SHOW_DIVIDER_MIDDLE
        }
        rows(table).forEachIndexed { index, row ->
            val nativeRow = TableRow(context).apply {
                isBaselineAligned = false
                dividerDrawable = divider()
                showDividers = SHOW_DIVIDER_MIDDLE
            }
            cells(row).forEach { cell ->
                val text = textView(original).apply {
                    maxWidth = resources.getDimensionPixelSize(R.dimen.table_column_max_width)
                    setPadding(padding, padding, padding, padding)
                    setBackgroundColor(context.getColor(if (cell.isHeader || index % 2 == 0) R.color.surface else R.color.background))
                    if (cell.isHeader) setTypeface(typeface, Typeface.BOLD)
                    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = cell.isHeader
                    gravity = Gravity.TOP or when (cell.alignment) {
                        TableCell.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
                        TableCell.Alignment.RIGHT -> Gravity.END
                        else -> Gravity.START
                    }
                    textAlignment = TEXT_ALIGNMENT_GRAVITY
                }
                nativeRow.addView(text, TableRow.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
                val paragraph = Paragraph()
                while (cell.firstChild != null) paragraph.appendChild(cell.firstChild)
                val content = Document().apply { appendChild(paragraph) }
                markwon.setParsedMarkdown(text, markwon.render(content))
            }
            layout.addView(nativeRow)
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(layout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }
}
