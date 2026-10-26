package com.kkgosu.learnharness

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.latex.JLatexMathNode
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import org.commonmark.node.Node
import org.commonmark.node.Text
import java.util.regex.Pattern

/** Native spans only: no WebView, HTML or remote image loader. */
internal fun lessonMarkdown(context: Context): Markwon = Markwon.builder(context)
    .usePlugin(MarkwonInlineParserPlugin.create { it.addInlineProcessor(DollarMathProcessor()) })
    .usePlugin(JLatexMathPlugin.create(context.resources.getDimension(R.dimen.text_16)) { builder ->
        builder.inlinesEnabled(true)
            .errorHandler { _, _ -> null } // Never log failed formula/lesson content.
        builder.theme().inlineTextColor(context.getColor(R.color.foreground))
            .blockTextColor(context.getColor(R.color.foreground))
    })
    .usePlugin(object : AbstractMarkwonPlugin() {
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

/** Registered before the library's $$ processor so single-dollar math cannot swallow later text. */
private class DollarMathProcessor : InlineProcessor() {
    private val math = Pattern.compile("^(\\${'$'}{1,2})(?=\\S)(.*?\\S)\\1(?!\\${'$'})")
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
