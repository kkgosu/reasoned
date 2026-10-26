package com.kkgosu.learnharness

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.text.Spannable
import android.text.Spanned
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.utils.SpanUtils
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import org.commonmark.node.Node
import org.commonmark.node.Text
import java.util.regex.Pattern
import ru.noties.jlatexmath.JLatexMathDrawable
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
        override fun beforeSetText(textView: TextView, markdown: Spanned) {
            val text = markdown as? Spannable ?: return
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
