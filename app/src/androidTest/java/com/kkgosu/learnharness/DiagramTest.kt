package com.kkgosu.learnharness

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import io.noties.markwon.image.AsyncDrawableSpan
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Local fixtures only: no live provider, CDN, screenshot capture or credentials. */
class DiagramTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val svg =
        """<svg xmlns="http://www.w3.org/2000/svg" width="80" height="80" viewBox="0 0 80 80"><rect width="80" height="80" fill="#0000ff"/><text x="20" y="40">A</text></svg>"""

    private fun completed(
        text: String = "",
        name: String? = null,
        args: JSONObject = JSONObject(),
        id: Int = 1
    ): JSONObject {
        val output = if (name == null) JSONObject().put("type", "message").put("role", "assistant")
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "output_text").put("text", text))
            )
        else JSONObject().put("type", "function_call").put("name", name)
            .put("call_id", "fixture-$id").put("arguments", args.toString())
        return JSONObject().put("status", "completed").put("output", JSONArray().put(output))
            .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5))
    }

    @Test
    fun vendoredEngineAndVisualPromptMatchPinnedSources() {
        val bytes = context.assets.open("diagrams/mermaid.min.js").use { it.readBytes() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals("8d8e0eec56d3a83b4b3c87f42050845546dee93ebe1875d2117c12e6947c0cb3", sha)
        val source =
            context.assets.open("learnharness/src/agent/subagents/visual-maker.ts").bufferedReader()
                .use { it.readText() }
        val projected =
            context.assets.open("agents/visual-maker.md").bufferedReader().use { it.readText() }
        assertEquals(
            source.substringAfter("const INSTRUCTIONS = `").substringBefore("`;") + "\n",
            projected
        )
    }

    @Test
    fun staticSourcesRejectActiveMarkupResourcesAndConfigOverrides() {
        validateDiagram("svg", svg)
        validateDiagram(
            "svg",
            svg.replace(
                "<text",
                "<defs><marker id=\"arrow\"><path d=\"M0 0 L2 2\"/></marker></defs><text"
            ).replace("x=\"20\"", "style=\"marker-end:url('#arrow')\" x=\"20\"")
        )
        validateDiagram("mermaid", "flowchart LR\n A[\"First\"] --> B[\"Next\"]")
        for (bad in listOf(
            svg.replace("<text", "<script>alert(1)</script><text"),
            svg.replace("<text", "<foreignObject/><text"),
            svg.replace("<text", "<image href=\"https://example.invalid/a\"/><text"),
            svg.replace("<text", "<use href=\"#recursive\"/><text"),
            svg.replace("<text", "<animate attributeName=\"x\"/><text"),
            svg.replace("<text", "<style>@import 'https://example.invalid/style';</style><text"),
            svg.replace("<text", "<style>.a{fill:url(https://example.invalid/image)}</style><text"),
            svg.replace(
                "<text",
                "<style>.a{fill:u\\72l(https://example.invalid/image)}</style><text"
            ),
            svg.replace("<text", "<text onload=\"alert(1)\""),
            "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///private'>]>$svg",
            svg.replace("0 0 80 80", "0 0 NaN 80"),
        )) try {
            validateDiagram("svg", bad); fail("Unsafe SVG accepted")
        } catch (_: ProviderFailure) {
        }
        for (bad in listOf(
            "%%{init: {'securityLevel':'loose'}}%%\nflowchart LR; A-->B",
            "---\nconfig: {}\n---\nflowchart LR; A-->B"
        ))
            try {
                validateDiagram("mermaid", bad); fail("Config override accepted")
            } catch (_: ProviderFailure) {
            }
        val markdown = lessonMarkdown(context)
        val rendered = markdown.toMarkdown("Before\n\n```svg\n$svg\n```\n\nAfter")
        assertEquals(1, rendered.getSpans(0, rendered.length, AsyncDrawableSpan::class.java).size)
        assertTrue(rendered.toString().contains("Before")); assertTrue(
            rendered.toString().contains("After")
        )
        val plan = StudyPlan(
            listOf(
                StudyPlan.Node("end", "Основа \"[A]\" & <B> `C` %%{init: {}}", ""),
                StudyPlan.Node("next", "Следующий шаг", "")
            ), listOf("end" to "next")
        )
        val planMarkdown = plan.diagramMarkdown()
        assertTrue(planMarkdown.contains("n0 --> n1"))
        assertTrue(planMarkdown.contains("#34;[A]#34; #38; #60;B#62;"))
        assertFalse(planMarkdown.contains("%%{"))
        validateDiagram("mermaid", planMarkdown.removePrefix("```mermaid\n").removeSuffix("```"))
        val planSpans = markdown.toMarkdown(planMarkdown)
        assertEquals(1, planSpans.getSpans(0, planSpans.length, AsyncDrawableSpan::class.java).size)
        val code = markdown.toMarkdown("```kotlin\nval answer = 42\n```")
        assertEquals("val answer = 42", code.toString())
        assertEquals(
            1,
            code.getSpans(
                0,
                code.length,
                io.noties.markwon.core.spans.CodeBlockSpan::class.java
            ).size
        )
        assertTrue(code.getSpans(0, code.length, AsyncDrawableSpan::class.java).isEmpty())
        assertEquals(
            "  first();\n\n  second();\n",
            markdown.toMarkdown("```glsl\n  first();\n\n  second();\n\n```").toString()
        )
        assertEquals(
            "Before\n\nval answer = 42\n\nAfter",
            markdown.toMarkdown("Before\n\n```kotlin\nval answer = 42\n```\n\nAfter").toString()
        )
        assertEquals("\u00a0", markdown.toMarkdown("```text\n```").toString())
    }

    @Test
    fun inlineAndBlockCodeHaveEqualVisibleTopAndBottomInsets() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val markdown = lessonMarkdown(context)
            val padding = context.resources.getDimensionPixelSize(R.dimen.space_4)
            val samples = listOf(
                ".xy", "vec2 position = gl_FragCoord.xy;", "one\ntwo",
                List(12) { "position" }.joinToString(" "), "עברית xy", "Привет 世界"
            )
            for (source in samples) for (format in listOf(
                "inline",
                "inline-bold",
                "fenced",
                "indented"
            )) {
                val block = format == "fenced" || format == "indented"
                if (!block && source.contains('\n')) continue
                for (scale in listOf(1f, 2f)) for (width in listOf(
                    360,
                    720
                )) for (standalone in listOf(false, true)) {
                    val view = android.widget.TextView(context).apply {
                        setTextColor(Color.WHITE)
                        typeface = context.resources.getFont(R.font.prose)
                        setTextSize(
                            android.util.TypedValue.COMPLEX_UNIT_PX,
                            context.resources.getDimension(R.dimen.text_16) * scale
                        )
                        setLineSpacing(0f, 1.5f)
                        setTextIsSelectable(true)
                    }
                    val code = when (format) {
                        "fenced" -> "```text\n$source\n```"
                        "indented" -> "    " + source.replace("\n", "\n    ")
                        "inline-bold" -> "**`$source`**"
                        else -> "`$source`"
                    }
                    markdown.setMarkdown(view, if (standalone) code else "Before\n\n$code\n\nAfter")
                    view.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(
                            width,
                            android.view.View.MeasureSpec.EXACTLY
                        ),
                        android.view.View.MeasureSpec.makeMeasureSpec(
                            0,
                            android.view.View.MeasureSpec.UNSPECIFIED
                        )
                    )
                    view.layout(0, 0, width, view.measuredHeight)
                    val text = view.text as android.text.Spanned
                    val span: Any = if (block) text.getSpans(
                        0, text.length,
                        io.noties.markwon.core.spans.CodeBlockSpan::class.java
                    ).single()
                    else text.getSpans(
                        0,
                        text.length,
                        io.noties.markwon.core.spans.CodeSpan::class.java
                    ).single()
                    val firstLine = view.layout.getLineForOffset(text.getSpanStart(span))
                    val lastLine = view.layout.getLineForOffset(text.getSpanEnd(span) - 1)
                    val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(android.graphics.Canvas(bitmap)) // In-memory fixture, not a device screenshot.
                        var backgroundTop = bitmap.height
                        var backgroundBottom = -1
                        var inkTop = bitmap.height
                        var inkBottom = -1
                        for (y in maxOf(0, view.layout.getLineTop(firstLine) - padding) until minOf(
                            bitmap.height,
                            view.layout.getLineBottom(lastLine) + padding
                        )) {
                            for (x in 0 until width) {
                                val alpha = Color.alpha(bitmap.getPixel(x, y))
                                if (alpha == 25) {
                                    backgroundTop = minOf(backgroundTop, y)
                                    backgroundBottom = maxOf(backgroundBottom, y + 1)
                                } else if (alpha >= 240) {
                                    inkTop = minOf(inkTop, y)
                                    inkBottom = maxOf(inkBottom, y + 1)
                                }
                            }
                        }
                        val label =
                            "format=$format scale=$scale width=$width standalone=$standalone source=$source"
                        assertTrue("Missing code glyphs: $label", inkBottom > inkTop)
                        assertTrue(
                            "Top code inset: $label got=${inkTop - backgroundTop}",
                            kotlin.math.abs(inkTop - backgroundTop - padding) <= 2
                        )
                        assertTrue(
                            "Bottom code inset: $label got=${backgroundBottom - inkBottom}",
                            kotlin.math.abs(backgroundBottom - inkBottom - padding) <= 2
                        )
                        if (block && lastLine > firstLine) {
                            for (y in view.layout.getLineBottom(firstLine) until view.layout.getLineTop(
                                lastLine
                            )) {
                                assertTrue(
                                    "Gap in multiline code background: $label",
                                    Color.alpha(bitmap.getPixel(width - 1, y)) > 0
                                )
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun nativeRendererUsesOfflineMermaidAndInertSvgImages() = runBlocking {
        val renderer = DiagramRenderer(context)
        val image = renderer.render("svg", svg)
        assertEquals(80, image.width); assertEquals(80, image.height)
        assertEquals(Color.BLUE, image.getPixel(5, 5)); image.recycle()
        val graph = renderer.render(
            "mermaid",
            "flowchart LR\n A[\"First\"] --> B[\"Next\"]",
            targetPixels = 2048
        )
        assertEquals(2048, maxOf(graph.width, graph.height)); graph.recycle()
        val plan = StudyPlan(
            listOf(
                StudyPlan.Node("end", "Основа \"[A]\" & <B>", ""),
                StudyPlan.Node("next", "Next", "")
            ), listOf("end" to "next")
        )
        val planImage = renderer.render(
            "mermaid",
            plan.diagramMarkdown().removePrefix("```mermaid\n").removeSuffix("```")
        )
        assertTrue(planImage.width > 0 && planImage.height > 0); planImage.recycle()
    }

    @Test
    fun imageTapDoesNotSelectOrRefollowChatAndReusesVisibleBitmap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val markdown = lessonMarkdown(context)
            val text = android.widget.TextView(context).apply { setTextIsSelectable(true) }
            val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
            try {
                markdown.setMarkdown(text, "Before\n\n```svg Fixture\n$svg\n```\n\nAfter")
                val rendered = text.text as android.text.Spanned
                assertFalse(rendered.toString().contains("Open diagram"))
                val span =
                    rendered.getSpans(0, rendered.length, AsyncDrawableSpan::class.java).single()
                span.drawable.setResult(
                    android.graphics.drawable.BitmapDrawable(
                        context.resources,
                        bitmap
                    ).apply { setBounds(0, 0, 80, 80) })
                text.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        1000,
                        android.view.View.MeasureSpec.EXACTLY
                    ),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                        0,
                        android.view.View.MeasureSpec.UNSPECIFIED
                    )
                )
                text.layout(0, 0, 1000, text.measuredHeight)
                val start = rendered.getSpanStart(span)
                val line = text.layout.getLineForOffset(start)
                val x = text.layout.getPrimaryHorizontal(start) + 4
                val y = (text.layout.getLineTop(line) + text.layout.getLineBottom(line)) / 2f
                val time = android.os.SystemClock.uptimeMillis()
                var clicks = 0
                text.setOnClickListener { clicks++ }
                DiagramPlugin.cache.evictAll() // Visible images can survive eviction: a tap must retain this exact bitmap.
                val selection = android.text.Selection.getSelectionStart(text.text)
                for (action in listOf(
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_UP
                )) {
                    val event = android.view.MotionEvent.obtain(
                        time,
                        time + if (action == android.view.MotionEvent.ACTION_UP) 20 else 0,
                        action,
                        x,
                        y,
                        0
                    )
                    try {
                        assertTrue(text.dispatchTouchEvent(event))
                    } finally {
                        event.recycle()
                    }
                    assertEquals(selection, android.text.Selection.getSelectionStart(text.text))
                }
                assertEquals(1, clicks)
                assertSame(bitmap, DiagramPlugin.cache.get(span.drawable.destination))
                val rows = listOf(LessonDisplayItem.TextDisplayItem(1, "Unchanged"))
                val adapter = LessonAdapter()
                assertTrue(adapter.submit(rows)); assertFalse(adapter.submit(rows.toList()))
            } finally {
                io.noties.markwon.image.AsyncDrawableScheduler.unschedule(text)
                DiagramPlugin.cache.evictAll(); bitmap.recycle()
            }
        }
    }

    @Test
    fun makerForcesImagesAfterInitialWriteAndEveryRevisionBeforeDelivery() = runBlocking {
        var requests = 0
        val renders = mutableListOf<String>()
        var tokens = 0L
        val maker = VisualMaker(
            context,
            ModelProvider { request, _ ->
                requests++
                assertFalse(request.toString().contains("NEVER_SEND_SESSION_CONTEXT"))
                if (requests >= 3) assertTrue(
                    request.getJSONArray("input").objects().any { message ->
                        message.optJSONArray("content")?.objects()?.any {
                            it.optString("type") == "input_image" &&
                                    it.optString("image_url").startsWith("data:image/png;base64,")
                        } == true
                    })
                when (requests) {
                    1 -> completed(
                        name = "write_svg",
                        args = JSONObject().put("svg", svg),
                        id = requests
                    )

                    2 -> completed("Finished without rendering.") // Must force a PNG and another model request.
                    3 -> completed(
                        name = "edit_svg",
                        args = JSONObject().put("old_text", "x=\"20\"").put("new_text", "x=\"30\""),
                        id = requests
                    )

                    4 -> completed("Finished after changing the source.") // Must force the revised PNG too.
                    else -> completed("Inspected the final render: the label is inside the blue square.")
                }
            },
            render = { source ->
                renders += source; Bitmap.createBitmap(
                8,
                8,
                Bitmap.Config.ARGB_8888
            )
            })
        val result = maker.make(
            RuntimeSession(goal = "NEVER_SEND_SESSION_CONTEXT", model = "fake"),
            "Show a labeled blue square"
        ) { input, _ -> tokens += input }
        assertEquals(5, requests); assertEquals(2, renders.size); assertEquals(50L, tokens)
        assertEquals(svg, renders[0]); assertEquals(
        result.svg,
        renders[1]
    ); assertTrue(result.svg.contains("x=\"30\""))
        assertTrue(result.notes.startsWith("Inspected"))
        var writes = 0
        val neverLooks = VisualMaker(
            context,
            ModelProvider { _, _ ->
                completed(
                    name = "write_svg",
                    args = JSONObject().put("svg", svg),
                    id = ++writes
                )
            },
            render = { fail("Unrequested render"); error("unreachable") })
        try {
            neverLooks.make(
                RuntimeSession(goal = "Fixture", model = "fake"),
                "Square"
            ) { _, _ -> }; fail("Uninspected figure delivered")
        } catch (failure: ProviderFailure) {
            assertEquals("visual_step_limit", failure.reason)
        }
        assertEquals(8, writes)
    }
}
