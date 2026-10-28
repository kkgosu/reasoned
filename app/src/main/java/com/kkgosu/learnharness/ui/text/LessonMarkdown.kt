package com.kkgosu.learnharness.ui.text

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.widget.TextView
import com.kkgosu.learnharness.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.utils.SpanUtils
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Text
import ru.noties.jlatexmath.JLatexMathDrawable
import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Native text/math spans and bounded static diagram previews; no remote image loader or live lesson HTML. */
internal fun lessonMarkdown(context: Context): Markwon = Markwon.builder(context)
    .usePlugin(MarkwonInlineParserPlugin.create { it.addInlineProcessor(DollarMathProcessor()) })
    .usePlugin(JLatexMathPlugin.create(context.resources.getDimension(R.dimen.text_16)) { builder ->
        builder.inlinesEnabled(true)
            .errorHandler { _, _ -> null } // Never log failed formula/lesson content.
        builder.theme().inlineTextColor(context.getColor(R.color.foreground))
            .blockTextColor(context.getColor(R.color.foreground))
    })
    .usePlugin(DiagramPlugin(context))
    .usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
            val padding = context.resources.getDimensionPixelSize(R.dimen.space_4)
            builder.setFactory(Code::class.java) { config, _ ->
                InlineCodeBackgroundSpan(
                    config.theme(),
                    padding
                )
            }
            builder.setFactory(FencedCodeBlock::class.java) { config, _ ->
                CodeBlockBackgroundSpan(
                    config.theme(),
                    padding
                )
            }
            builder.setFactory(IndentedCodeBlock::class.java) { config, _ ->
                CodeBlockBackgroundSpan(
                    config.theme(),
                    padding
                )
            }
        }

        override fun beforeSetText(textView: TextView, markdown: Spanned) {
            val text = markdown as? Spannable ?: return
            text.getSpans(0, text.length, InlineCodeBackgroundSpan::class.java)
                .forEach { it.view = textView }
            val padding = context.resources.getDimensionPixelSize(R.dimen.space_4)
            text.getSpans(0, text.length, MetricAffectingSpan::class.java)
                .filter { it is CodeSpan || it is CodeBlockSpan }.forEach { span ->
                    text.setSpan(
                        CodeLineHeightSpan(textView, span, padding),
                        text.getSpanStart(span), text.getSpanEnd(span), text.getSpanFlags(span)
                    )
                }
            text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java).forEach { span ->
                // Markwon 4.6.2 uses the base class for blocks and a private subclass for inlines.
                if (span.javaClass != JLatexAsyncDrawableSpan::class.java) {
                    val replacement = InlineMathSpan(MarkwonTheme.create(textView.context), span)
                    text.setSpan(
                        replacement,
                        text.getSpanStart(span),
                        text.getSpanEnd(span),
                        text.getSpanFlags(span)
                    )
                    text.removeSpan(span)
                }
            }
        }

        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            builder.linkResolver { view, link ->
                val uri = Uri.parse(link)
                if (uri.scheme?.lowercase() in listOf("http", "https")) {
                    try {
                        view.context.startActivity(
                            Intent(Intent.ACTION_VIEW, uri).addCategory(
                                Intent.CATEGORY_BROWSABLE
                            )
                        )
                    } catch (_: ActivityNotFoundException) { /* No browser installed. */
                    }
                }
            }
        }
    }).build()

/** Measure actual code glyphs, not the paragraph's font box or extra line spacing. */
private fun codeInkBounds(
    basePaint: TextPaint,
    text: Spanned,
    start: Int,
    end: Int,
    paint: TextPaint,
    bounds: Rect
) {
    paint.set(basePaint)
    text.getSpans(start, end, MetricAffectingSpan::class.java)
        .forEach { it.updateMeasureState(paint) }
    val line = text.subSequence(start, end).toString().trimEnd('\n', '\r')
    paint.getTextBounds(line, 0, line.length, bounds)
    if (bounds.isEmpty) {
        bounds.top = kotlin.math.floor(paint.ascent()).toInt()
        bounds.bottom = ceil(paint.descent()).toInt()
    }
}

/** Reserve only the space needed so backgrounds also fit at the first/last line of a TextView. */
private class CodeLineHeightSpan(
    private val view: TextView, private val code: MetricAffectingSpan, private val padding: Int
) : LineHeightSpan {
    private val paint = TextPaint()
    private val ink = Rect()

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        v: Int,
        fm: Paint.FontMetricsInt
    ) {
        val spanned = text as Spanned
        val from = maxOf(start, spanned.getSpanStart(code))
        val to = minOf(end, spanned.getSpanEnd(code))
        if (from >= to) return
        codeInkBounds(view.paint, spanned, from, to, paint, ink)
        if (code !is CodeBlockSpan || start <= spanned.getSpanStart(code)) fm.ascent =
            minOf(fm.ascent, ink.top - padding)
        if (code !is CodeBlockSpan || end >= spanned.getSpanEnd(code)) fm.descent =
            maxOf(fm.descent, ink.bottom + padding)
        fm.top = minOf(fm.top, fm.ascent)
        fm.bottom = maxOf(fm.bottom, fm.descent)
    }
}

private class CodeBlockBackgroundSpan(theme: MarkwonTheme, private val padding: Int) :
    CodeBlockSpan(theme) {
    private val codePaint = TextPaint()
    private val ink = Rect()

    override fun drawLeadingMargin(
        canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout
    ) {
        val spanned = text as Spanned
        val from = maxOf(start, spanned.getSpanStart(this))
        val to = minOf(end, spanned.getSpanEnd(this))
        if (from >= to) return
        codeInkBounds(layout.paint, spanned, from, to, codePaint, ink)
        val backgroundTop =
            if (start <= spanned.getSpanStart(this)) baseline + ink.top - padding else top
        val backgroundBottom =
            if (end >= spanned.getSpanEnd(this)) baseline + ink.bottom + padding else bottom
        super.drawLeadingMargin(
            canvas,
            paint,
            x,
            dir,
            backgroundTop,
            baseline,
            backgroundBottom,
            text,
            start,
            end,
            first,
            layout
        )
    }
}

/** Native selection geometry keeps wrapping, shaping and mixed-direction code intact. */
private class InlineCodeBackgroundSpan(private val theme: MarkwonTheme, private val padding: Int) :
    CodeSpan(theme), LineBackgroundSpan {
    var view: TextView? = null
    private val codePaint = TextPaint()
    private val ink = Rect()
    private val path = Path()
    private val lineClip = Path()
    private val selectionBounds = RectF()

    override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        ds.bgColor = 0
    }

    override fun drawBackground(
        canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lineNumber: Int
    ) {
        val layout = view?.layout ?: return
        val spanned = text as Spanned
        val from = maxOf(start, spanned.getSpanStart(this))
        val to = minOf(end, spanned.getSpanEnd(this))
        if (from >= to) return
        codeInkBounds(layout.paint, spanned, from, to, codePaint, ink)
        path.reset()
        layout.getSelectionPath(from, to, path)
        lineClip.reset()
        lineClip.addRect(
            left.toFloat(),
            top.toFloat(),
            right.toFloat(),
            bottom.toFloat(),
            Path.Direction.CW
        )
        if (!path.op(lineClip, Path.Op.INTERSECT) || path.isEmpty) return
        path.computeBounds(selectionBounds, true)
        codePaint.color = theme.getCodeBackgroundColor(codePaint)
        codePaint.style = Paint.Style.FILL
        // Keep native horizontal/bidi geometry; give only the background an explicit vertical inset.
        val save = canvas.save()
        canvas.translate(0f, (baseline + ink.top - padding).toFloat())
        canvas.scale(1f, (ink.height() + padding * 2) / selectionBounds.height())
        canvas.translate(0f, -selectionBounds.top)
        canvas.drawPath(path, codePaint)
        canvas.restoreToCount(save)
    }
}

/** Keep TeX's own baseline, not the midpoint of a line including extra paragraph spacing. */
private class InlineMathSpan(theme: MarkwonTheme, private val original: AsyncDrawableSpan) :
    AsyncDrawableSpan(theme, original.drawable, ALIGN_BASELINE, false) {
    private fun baseline(): Float? = (drawable.result as? JLatexMathDrawable)?.icon()?.baseLine
        ?.times(drawable.bounds.height())

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val baseline = baseline() ?: return original.getSize(paint, text, start, end, fm)
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            fm.ascent = minOf(fm.ascent, -ceil(baseline).toInt())
            fm.descent = maxOf(fm.descent, ceil(drawable.bounds.height() - baseline).toInt())
            fm.top = minOf(fm.top, fm.ascent)
            fm.bottom = maxOf(fm.bottom, fm.descent)
        }
        return original.getSize(paint, text, start, end, null)
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float,
        top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        drawable.initWithKnownDimensions(SpanUtils.width(canvas, text), paint.textSize)
        val baseline = baseline()
        if (baseline == null) original.draw(canvas, text, start, end, x, top, y, bottom, paint)
        else {
            val alignedTop = y - baseline.roundToInt()
            original.draw(
                canvas,
                text,
                start,
                end,
                x,
                alignedTop,
                y,
                alignedTop + drawable.bounds.height(),
                paint
            )
        }
    }
}

/** Registered before the library's $$ processor so single-dollar math cannot swallow later text. */
private class DollarMathProcessor : InlineProcessor() {
    private val math = Pattern.compile(
        "^(\\${'$'}{1,2})(?=\\S|[\\x08\\t\\x0b\\f\\r\\n][A-Za-z])(.*?\\S)\\1(?!\\${'$'})",
        Pattern.DOTALL
    )

    override fun specialCharacter() = '$'
    override fun parse(): Node {
        val source = match(math) ?: run { index++; return Text("\$") }
        val delimiter = if (source.startsWith("\$\$")) 2 else 1
        // ponytail: oversized inline formulas stay readable as source; add a dedicated math viewport if needed.
        if (source.length > 2000) return Text(source)
        return JLatexMathNode().apply {
            latex(
                source.substring(
                    delimiter,
                    source.length - delimiter
                )
            )
        }
    }
}
