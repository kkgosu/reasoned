package com.kkgosu.reasoned.ui.text

import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.scilab.forge.jlatexmath.MacroInfo
import org.scilab.forge.jlatexmath.SymbolAtom
import org.scilab.forge.jlatexmath.TeXFormula

/** Recovery on a fresh native parse tree ONLY. Never serialize it into saved arguments or answers.
 * CommonMark owns code boundaries (including nested lists/quotes); we never guess them with regex.
 * Document escapes become visible line breaks, not reparsed Markdown or executable markup. */
internal fun Markwon.setToolMarkdown(view: TextView, source: String) {
    val tree = parse(source)
    ToolContentPresentation.recover(tree)
    setParsedMarkdown(view, render(tree))
}

internal object ToolContentPresentation {
    // Read the installed renderer's vocabulary, not a second, gradually drifting TeX command list.
    private val commands: Set<String> by lazy {
        TeXFormula() // Initialize built-ins before taking an immutable snapshot.
        (MacroInfo.Commands.keys + SymbolAtom.symbols.keys + TeXFormula.predefinedTeXFormulas.keys +
                TeXFormula.predefinedTeXFormulasAsString.keys).toSet()
    }
    private val word = Regex("[A-Za-z]+")
    private val documentEscape = Regex("(\\\\+)(n[A-Za-z]*)")

    // TeXParser handles environment delimiters in its first pass, outside the command maps.
    private fun known(name: String) = name == "begin" || name == "end" || name in commands
    private fun escaped(text: String, index: Int): Boolean {
        var start = index
        while (start > 0 && text[start - 1] == '\\') start--
        return (index - start) % 2 == 1
    }

    fun recover(root: Node) {
        val editable = mutableListOf<Node>()
        fun visit(node: Node) {
            when (node) {
                is Code, is FencedCodeBlock, is IndentedCodeBlock -> return
                is Text, is JLatexMathNode, is JLatexMathBlock -> editable += node
            }
            var child = node.firstChild
            while (child != null) {
                visit(child); child = child.next
            }
        }
        visit(root)
        fun content(node: Node): String = when (node) {
            is Text -> node.literal
            is JLatexMathNode -> node.latex()
            is JLatexMathBlock -> node.latex()
            else -> ""
        }

        val text = editable.joinToString("\u0000", transform = ::content)
        // A declared macro is never evidence for decoding its n-prefix into a newline.
        val declared =
            Regex("\\\\(?:newcommand|renewcommand|providecommand|def|gdef|edef)\\*?\\s*\\{?\\s*\\\\(n[A-Za-z]+)")
                .findAll(text).map { it.groupValues[1] }.toSet()
        val candidates = documentEscape.findAll(text).count {
            it.groupValues[1].length % 2 == 1 && !known(it.groupValues[2]) && it.groupValues[2] !in declared
        }
        val layout = candidates >= 2 || text.contains("\\n\\n") || (candidates > 0 &&
                Regex("(\\\\+)([A-Za-z]+)").findAll(text)
                    .any { it.groupValues[1].length % 2 == 0 && known(it.groupValues[2]) })

        fun decode(value: String) = if (!layout) value else documentEscape.replace(value) { match ->
            val (slashes, name) = match.destructured
            val token = value.substring(0, match.range.first)
                .takeLastWhile { !it.isWhitespace() } + match.value
            if (slashes.length % 2 == 0 || known(name) || name in declared ||
                Regex("^(?:[A-Za-z]:[\\\\/]|https?://|/)").containsMatchIn(token)
            ) match.value
            else slashes.dropLast(1) + "\n" + name.drop(1)
        }
        editable.forEach { node ->
            when (node) {
                is Text -> node.literal = decode(node.literal)
                is JLatexMathNode -> node.latex(
                    repairMath(
                        outsideComments(node.latex(), ::decode),
                        false
                    )
                )

                is JLatexMathBlock -> node.latex(
                    repairMath(
                        outsideComments(node.latex(), ::decode),
                        true
                    )
                )
            }
        }
    }

    private data class Range(
        val start: Int,
        val end: Int,
        val factor: Int,
        val comment: Boolean = false
    )

    /** Original TeX structure: native rows are literal; encoded environments supply an escape factor. */
    private fun ranges(text: String): List<Range> {
        data class Open(val name: String, val start: Int, val factor: Int)

        val open = mutableListOf<Open>()
        val ranges = mutableListOf<Range>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '%' && !escaped(text, i)) {
                val factor = open.lastOrNull()?.factor
                    ?: ranges.lastOrNull { i in it.start until it.end }?.factor
                var start = i
                while (start > 0 && text[start - 1] == '\\') start--
                val count = i - start
                val native =
                    open.any { it.factor == 1 } || ranges.any { it.factor == 1 && i in it.start until it.end }
                if ((factor != null && factor > 1 && count % factor == 0 && count / factor % 2 == 1) ||
                    (!native && numericPercent(text, i))
                ) {
                    i++; continue
                }
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it + 1 }
                ranges += Range(i, end, 1, true); i = end; continue
            }
            if (text[i] != '\\') {
                i++; continue
            }
            var end = i + 1
            while (end < text.length && text[end] == '\\') end++
            val count = end - i
            val marker = Regex("^(begin|end)\\s*\\{([^{}]+)\\}").find(text.substring(end))
            if (marker != null) {
                val factor = if (count % 2 == 1) 1 else count
                val name = marker.groupValues[2]
                if (marker.groupValues[1] == "begin") open += Open(name, i, factor)
                else if (open.lastOrNull()
                        ?.let { it.name == name && it.factor == factor } == true
                ) {
                    val start = open.removeAt(open.lastIndex)
                    ranges += Range(start.start, end + marker.value.length, factor)
                }
                i = end + marker.value.length; continue
            }
            val substack = Regex("^substack\\s*\\{").find(text.substring(end))
            if (substack != null) {
                var depth = 1
                var j = end + substack.value.length
                while (j < text.length && depth > 0) {
                    if (text[j] == '%' && !escaped(text, j)) {
                        j = text.indexOf('\n', j).let { if (it < 0) text.length else it }; continue
                    }
                    if (!escaped(text, j)) {
                        if (text[j] == '{') depth++; if (text[j] == '}') depth--
                    }
                    j++
                }
                if (depth == 0) ranges += Range(i, j, if (count % 2 == 1) 1 else count)
            }
            i = end
        }
        return ranges.sortedBy { it.start }
    }

    private fun numericPercent(text: String, percent: Int): Boolean {
        var start = percent
        while (start > 0 && text[start - 1] == '\\') start--
        if (percent - start < 2 || (percent - start) % 2 != 0 || !Regex("\\d[ \\t]*$").containsMatchIn(
                text.substring(0, start)
            )
        ) return false
        val tail = text.substring(percent + 1)
        return Regex("^[\\s.)\\]}]*$").matches(tail) || (!tail.contains('\n') && !tail.contains('\r') && !Regex(
            "^[ \\t]*[A-Za-z]"
        ).containsMatchIn(tail))
    }

    private fun outsideComments(text: String, transform: (String) -> String): String = buildString {
        var start = 0
        ranges(text).filter { it.comment }.forEach {
            append(transform(text.substring(start, it.start))); append(
            text.substring(
                it.start,
                it.end
            )
        ); start = it.end
        }
        append(transform(text.substring(start)))
    }

    fun repairMath(source: String, display: Boolean): String {
        // Same bounded formula size as the native inline renderer; no macro definitions are evaluated here.
        if (source.length > 8000) return source
        val restored = outsideComments(source) { part ->
            Regex("[\\x08\\t\\x0b\\x0c\\r\\n]").replace(part) { match ->
                val char = match.value[0]
                val suffix = word.find(part, match.range.first + 1)
                    ?.takeIf { it.range.first == match.range.first + 1 }?.value
                val prefix = when (char) {
                    '\b' -> 'b'; '\t' -> 't'; '\u000b' -> 'v'; '\u000c' -> 'f'; '\r' -> 'r'; else -> 'n'
                }
                if ((display && char in "\r\n") || (char == '\r' && part.getOrNull(match.range.first + 1) == '\n') ||
                    (char == '\n' && part.getOrNull(match.range.first - 1) == '\r') ||
                    suffix == null || !known(prefix + suffix)
                ) match.value else "\\$prefix"
            }
        }
        val contexts = ranges(restored)
        return Regex("\\\\+").replace(restored) { match ->
            val run = match.value
            val context = contexts.filter { match.range.first in it.start until it.end }
            if (run.length % 2 != 0 || context.any { it.comment || it.factor == 1 }) return@replace run
            val next = restored.getOrNull(match.range.last + 1)
            val name = word.find(restored, match.range.last + 1)
                ?.takeIf { it.range.first == match.range.last + 1 }?.value
            val encoded = context.lastOrNull { it.factor > 1 }
            val symbol = next != null && next in ",;:!|{}%"
            when {
                encoded != null && run.length % encoded.factor == 0 &&
                        (run.length % (2 * encoded.factor) == 0 || (name != null && known(name)) || symbol) -> "\\".repeat(
                    run.length / encoded.factor
                )

                next == '%' -> if (numericPercent(restored, match.range.last + 1)) "\\" else run
                name != null -> if (name.length > 1 && known(name)) "\\" else run
                symbol -> "\\"
                else -> run
            }
        }
    }
}
