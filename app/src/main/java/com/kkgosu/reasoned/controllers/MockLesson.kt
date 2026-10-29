package com.kkgosu.reasoned.controllers

import android.os.Bundle
import com.kkgosu.reasoned.BuildConfig
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.ReasonedApplication
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.NativeResponses
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.model.LessonDisplayItem.Role
import com.kkgosu.reasoned.model.LessonDisplayItem.TextDisplayItem
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeQuestion
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.storage.SessionStore
import com.kkgosu.reasoned.utils.objects
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only authored responses through the real runtime, in a separate in-memory SQLite store. */
internal class MockLesson(private val app: ReasonedApplication, saved: Bundle? = null) {
    val sessionId = FIXTURE
    val store: SessionStore
    val runtime: SessionRuntime
    var requests = 0
        private set

    init {
        check(BuildConfig.DEBUG)
        store = SessionStore(app, null)
        val restored = saved?.takeIf { it.getString("fixture") == FIXTURE }
        val chat = restored?.getString("native")?.let(NativeChat::parse) ?: initialChat()
        val projected = store.saveNative(RuntimeSession(
            id = sessionId, goal = text(R.string.sample_session_title), model = MODEL,
            items = listOf(TextDisplayItem(1, text(R.string.sample_session_title), Role.TITLE)),
            nativeRecord = chat,
        ))
        if (restored != null) store.save(projected.copy(
            draft = restored.getString("draft").orEmpty(),
            selectedValues = restored.getStringArrayList("selectedValues").orEmpty(),
            confidence = restored.getInt("confidence", NativeQuestion.DEFAULT_CONFIDENCE),
            expanded = restored.getBoolean("expanded"),
            readingThinking = restored.getString("readingThinking").orEmpty(),
            dontKnow = restored.getBoolean("dontKnow"),
        ))
        runtime = SessionRuntime(app, store, ModelProvider { payload, _ ->
            requests++
            delay(350) // A cancellable local pause makes Stop/reopening testable without a provider.
            reply(payload)
        }, researchFor = { _, _, _ -> throw ProviderFailure("sample_offline_only") })
    }

    fun session(): RuntimeSession = runtime.state.value ?: requireNotNull(store.load(sessionId))

    // Bound the activity's saved sample Bundle, including repeated Stop/new-note experiments.
    fun atLimit(state: RuntimeSession) = requireNotNull(state.nativeRecord).snapshot().getJSONArray("messages").length() >= 40

    fun save(): Bundle = with(session()) {
        Bundle().apply {
            putString("fixture", FIXTURE)
            putString("native", requireNotNull(nativeRecord).raw)
            putString("draft", draft)
            putStringArrayList("selectedValues", ArrayList(selectedValues))
            putInt("confidence", confidence)
            putBoolean("expanded", expanded)
            putString("readingThinking", readingThinking)
            putBoolean("dontKnow", dontKnow)
        }
    }

    private fun initialChat(): NativeChat {
        val intro = listOf(
            "Offline, authored test content — not model output or learner evidence.",
            text(R.string.lesson_intro),
            "${'$'}${'$'}\nf(x) = x^2 \\quad f'(x) = 2x\n${'$'}${'$'}",
            text(R.string.lesson_step),
        ).joinToString("\n\n")
        val first = response(tool = "probe", call = "sample_probe", args = question())
        // Keep each authored Markdown specimen in its own real transcript row.
        first.put("output", JSONArray((listOf(intro) + RENDERING_SAMPLES).map(::message) + first.getJSONArray("output").objects()))
        val now = System.currentTimeMillis()
        return NativeChat.parse(JSONObject().put("id", sessionId).put("version", 1).put("kind", "teach")
            .put("messages", JSONArray().put(NativeResponses.assistant(first, MODEL, "api", now))).toString())
    }

    private fun question() = JSONObject().put("question", text(R.string.lesson_question))
        .put("options", JSONArray(app.resources.getStringArray(R.array.lesson_options).take(3).mapIndexed { i, label ->
            JSONObject().put("label", label).put("value", listOf("downhill", "uphill", "stay")[i])
        })).put("correctAnswer", "downhill").put("explanation", text(R.string.lesson_correct))

    private fun reply(payload: JSONObject): JSONObject {
        check(payload.getString("model") == MODEL)
        val input = payload.getJSONArray("input").objects()
        val calls = input.filter { it.optString("type") == "function_call" }
        val results = input.filter { it.optString("type") == "function_call_output" }
        if (calls.none { it.optString("name") == "probe_done" }) return response(tool = "probe_done", call = "sample_assessment",
            args = JSONObject().put("assessment", JSONObject().put("strands", JSONArray(listOf(
                "Recognizing the function" to "Authored sample: recognizes the quadratic curve; transfer is not measured.",
                "Slope and direction" to "Authored sample: connects the derivative with downhill motion; this is not a learner verdict.",
                "Step size" to "Authored sample: a small step still needs a fresh check."
            ).map { (strand, assessment) -> JSONObject().put("strand", strand).put("assessment", assessment) }))
                .put("summary", "Offline sample assessment, not evidence of your knowledge. Next: inspect the plan, then send a note to begin the teaching check.")))
        if (calls.none { it.optString("name") == "plan_init" }) return response(
            text = "Authored sample plan. Open Learning plan to inspect it; send any note to continue.",
            tool = "plan_init", call = "sample_plan", args = JSONObject().put("mermaid", """
                graph TD
                function["Read the function"] --> slope["Find the slope"]
                slope --> update["Take a small step"]
                update --> compare["Compare the result"]
            """.trimIndent()))
        val quizzes = calls.filter { it.optString("name") == "quiz" }
        if (quizzes.isEmpty()) return response(tool = "quiz", call = "sample_quiz_1",
            args = question().put("type", "predict").put("nodeId", "slope"))
        val last = results.lastOrNull()
        val quizResult = last?.takeIf { it.optString("call_id") == quizzes.last().getString("call_id") }
            ?.getString("output")?.let(::JSONObject)
        if (quizResult != null && quizzes.last().getString("call_id") != "sample_check") {
            if (!quizResult.optBoolean("correct") && quizzes.size < 3) return response(
                text = text(if (quizResult.optBoolean("dontKnow")) R.string.lesson_uncertain else R.string.lesson_repair),
                tool = "quiz", call = "sample_quiz_${quizzes.size + 1}",
                args = question().put("type", "predict").put("nodeId", "slope"))
            return response(text = text(if (quizResult.optBoolean("correct")) R.string.lesson_correct else R.string.lesson_reveal),
                tool = "quiz", call = "sample_check",
                args = question().put("question", "Offline check: which direction does a small gradient step take?")
                    .put("type", "check").put("nodeId", "update"))
        }
        return response(text = "Offline authored reply — no model request or automatic assessment.\n\n" + text(R.string.lesson_mock_reply))
    }

    private fun text(id: Int) = app.getString(id)

    companion object {
        private const val FIXTURE = "native-sample-probe-plan-teach-v4"
        private const val MODEL = "sample-offline"
        private fun message(text: String) = JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
        private fun response(text: String = "", tool: String? = null, call: String = "", args: JSONObject = JSONObject()) = JSONObject()
            .put("status", "completed").put("output", JSONArray().apply {
                if (text.isNotEmpty()) put(message(text))
                if (tool != null) put(JSONObject().put("type", "function_call").put("status", "completed")
                    .put("name", tool).put("call_id", call).put("arguments", args.toString()))
            })

        val RENDERING_SAMPLES = listOf(
            """
                ## Markdown / LaTeX rendering samples
                Offline, authored test content — not model output or learner evidence.

                #### Heading spacing
                This paragraph should follow the heading without an empty line.

                ### A longer heading that wraps onto multiple lines on a narrow screen
                The body should stay close to the final heading line, including at large font sizes.

                ### Emphasis and punctuation
                Да: **The information you sent me was useful.**

                **Bold**, *italic*, ***bold italic***, __also bold__, _also italic_.
                **Жирный текст**, *курсив*, **bold with *nested italic* inside**.
                Literal escaped markers: \*\*not bold\*\*. Unclosed marker stays literal: **unfinished
            """.trimIndent(),
            """
                ### Lists, quotes and links
                - Plain item with **emphasis**.
                - Second item with `inline code`.
                  - Nested item with *italic text*.

                1. First numbered item.
                2. Second numbered item.

                > A quotation with **bold** and inline math ${'$'}x^2${'$'}.

                [Example link — opens a browser only when tapped](https://example.org)
            """.trimIndent(),
            """
                ### Tables: wrapping, alignment and emphasis
                | Unspecified information | Specific information |
                | --- | --- |
                | I need information about the trip. | **The information** in your email is out of date. |
                | Мне нужна информация о поездке. | Информация в твоём письме устарела. |

                | Left | Center | Right |
                | :--- | :---: | ---: |
                | `a/b` | *middle* | 123.45 |
                | escaped pipe: a\|b | **bold** | 9 |

                ### Wide table: swipe horizontally
                | Topic | Explanation | Example | Formula | Notes |
                | --- | --- | --- | --- | --- |
                | Articles | A long explanation should wrap within a single capped-width column, not widen it indefinitely. | **The information** you sent me was useful. | ${'$'}x^2${'$'} | Scroll to reach this last column. |
                | Переносы | Длинный русский текст тоже переносится внутри ячейки. | `literal code` | ${'$'}\frac{1}{2}${'$'} | Последний столбец доступен свайпом. |

                Text below the table must stay within the screen and must not scroll sideways.
            """.trimIndent(),
            """
                ### Code: literal markers and wrapping
                Inline: `**not bold**`, `${'$'}not math${'$'}`, `a_very_long_identifier_that_wraps_on_a_narrow_phone_screen`.

                ```kotlin
                val cost = "${'$'}5"
                // **literal stars**, not Markdown
                fun square(x: Int) = x * x
                ```

                    Indented code: **literal**, ${'$'}x${'$'}
            """.trimIndent(),
            """
                ### Inline math: baselines and surrounding punctuation
                Before ${'$'}x^2${'$'}, after. Кириллица рядом с ${'$'}\alpha + \beta${'$'}.

                Fraction ${'$'}\frac{x^2}{y_1}${'$'}, root ${'$'}\sqrt{a^2+b^2}${'$'}, and vector ${'$'}\vec{v}${'$'}.

                **Bold prose** with ${'$'}A=(130,140)${'$'}: text continues on the same baseline.
                Plain currency is not a formula: ${'$'}5.
            """.trimIndent(),
            """
                ### Display math: fractions, sums, matrices and multiple lines
                ${'$'}${'$'}
                x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
                ${'$'}${'$'}

                ${'$'}${'$'}
                \sum_{i=1}^{n} i = \frac{n(n+1)}{2}
                ${'$'}${'$'}

                ${'$'}${'$'}
                \begin{pmatrix}a & b \\ c & d\end{pmatrix}
                ${'$'}${'$'}

                ${'$'}${'$'}
                \begin{aligned}f(x) &= x^2 \\ f'(x) &= 2x\end{aligned}
                ${'$'}${'$'}

                Text after the final formula should remain visible and selectable.
            """.trimIndent()
        )
    }
}
