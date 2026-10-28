package com.kkgosu.reasoned.controllers

import android.content.res.Resources
import android.os.Bundle
import com.kkgosu.reasoned.R
import com.kkgosu.reasoned.model.LessonDisplayItem
import com.kkgosu.reasoned.model.LessonDisplayItem.AnsweredDisplayItem
import com.kkgosu.reasoned.model.LessonDisplayItem.FormulaDisplayItem
import com.kkgosu.reasoned.model.LessonDisplayItem.Role
import com.kkgosu.reasoned.model.LessonDisplayItem.TextDisplayItem
import com.kkgosu.reasoned.model.StudyPlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One bounded, authored UI fixture. Not an AI provider or educational assessment engine. */
class MockLesson(private val resources: Resources, saved: Bundle? = null) {
    enum class Dock { COMPOSER, QUESTION }

    data class State(
        val items: List<LessonDisplayItem>,
        val dock: Dock = Dock.COMPOSER,
        val expanded: Boolean = false,
        val selection: Int = -1,
        val confidence: Int = 50,
        val thinking: String = "",
        val draft: String = "",
        val streamTarget: String = "",
        val streaming: Boolean = false,
        val afterStream: Dock = Dock.QUESTION,
        val nextId: Long = 7,
        val attempts: Int = 0,
        val notes: Int = 0,
    )

    private val mutableState =
        MutableStateFlow(
            saved?.takeIf { it.getString("fixture") == FIXTURE }?.let(::restore)
                ?: initial()
        )
    val state = mutableState.asStateFlow()

    internal val plan = StudyPlan(
        listOf(
            StudyPlan.Node("function", "Read the function", "Recognize the quadratic curve and the starting point."),
            StudyPlan.Node("slope", "Find the slope", "Use the derivative to choose the direction of travel."),
            StudyPlan.Node("update", "Take a small step", "Subtract the slope times the learning rate."),
            StudyPlan.Node("compare", "Compare the result", "Check whether the new point has a lower function value.")
        ),
        listOf("function" to "slope", "slope" to "update", "update" to "compare")
    )

    /** Authored position tags for the preview only; never persisted as a real session or graph evidence. */
    internal fun planProtocol(state: State): String = JSONArray(plan.nodes.take(state.attempts + 2)
        .mapIndexed { index, node ->
            JSONObject().put("type", "function_call").put("name", "explain")
                .put("call_id", "sample-$index")
                .put("arguments", JSONObject().put("nodeId", node.id).toString())
        }).toString()

    private fun initial() = State(
        items = listOf(
            TextDisplayItem(1, text(R.string.lesson_heading), Role.TITLE),
            TextDisplayItem(2, text(R.string.lesson_intro)),
            FormulaDisplayItem(
                3,
                text(R.string.lesson_function),
                text(R.string.lesson_function_spoken)
            ),
            TextDisplayItem(4, text(R.string.lesson_step)),
            FormulaDisplayItem(
                5,
                text(R.string.lesson_update),
                text(R.string.lesson_update_spoken)
            ),
        ) + RENDERING_SAMPLES.mapIndexed { index, markdown ->
            TextDisplayItem(6L + index, markdown)
        } + TextDisplayItem(6L + RENDERING_SAMPLES.size, ""),
        streamTarget = text(R.string.lesson_stream),
        streaming = true,
        nextId = 7L + RENDERING_SAMPLES.size,
    )

    fun expand(expanded: Boolean) = mutableState.update { it.copy(expanded = expanded) }
    fun select(selection: Int) =
        mutableState.update { it.copy(selection = selection.coerceIn(-1, 3)) }

    fun confidence(confidence: Int) =
        mutableState.update { it.copy(confidence = confidence.coerceIn(0, 100)) }

    fun thinking(text: String) = mutableState.update { it.copy(thinking = text.take(MAX_INPUT)) }
    fun draft(text: String) = mutableState.update { it.copy(draft = text.take(MAX_INPUT)) }
    fun stop() = mutableState.update { it.copy(streaming = false) }
    fun resume() = mutableState.update { it.copy(streaming = it.streamTarget.isNotEmpty()) }

    suspend fun stream() {
        while (state.value.streaming) {
            delay(60)
            advanceStream()
        }
    }

    fun advanceStream() = mutableState.update { current ->
        if (!current.streaming) return@update current
        val last = current.items.last() as TextDisplayItem
        val end = (last.text.length + 12).coerceAtMost(current.streamTarget.length)
        val complete = end == current.streamTarget.length
        current.copy(
            items = current.items.dropLast(1) + last.copy(text = current.streamTarget.take(end)),
            streaming = !complete,
            streamTarget = if (complete) "" else current.streamTarget,
            dock = if (complete) current.afterStream else current.dock,
        )
    }

    fun submitAnswer() = mutableState.update { current ->
        if (current.dock != Dock.QUESTION || current.selection !in 0..3) return@update current
        val correct = current.selection == 0
        val attempts = current.attempts + 1
        val feedback = when {
            correct -> R.string.lesson_correct
            attempts >= MAX_ATTEMPTS -> R.string.lesson_reveal
            current.selection == 3 -> R.string.lesson_uncertain
            else -> R.string.lesson_repair
        }
        current.copy(
            items = current.items + AnsweredDisplayItem(
                id = current.nextId,
                question = text(R.string.lesson_question),
                choices = resources.getStringArray(R.array.lesson_options).take(3),
                answer = resources.getStringArray(R.array.lesson_options)[current.selection],
                thinking = current.thinking,
                confidence = current.confidence.takeUnless { current.selection == 3 },
                correctAnswer = resources.getStringArray(R.array.lesson_options)[0],
                explanation = text(R.string.lesson_correct),
                questionType = "predict",
                dontKnow = current.selection == 3,
            ) + TextDisplayItem(current.nextId + 1, ""),
            dock = Dock.COMPOSER,
            expanded = false,
            streaming = true,
            streamTarget = text(feedback),
            afterStream = if (correct || attempts >= MAX_ATTEMPTS) Dock.COMPOSER else Dock.QUESTION,
            nextId = current.nextId + 2,
            attempts = attempts,
        )
    }

    fun send() = mutableState.update { current ->
        if (current.dock != Dock.COMPOSER || current.streamTarget.isNotEmpty() ||
            current.draft.isBlank() || current.notes >= MAX_NOTES
        ) return@update current
        current.copy(
            items = current.items + TextDisplayItem(
                current.nextId,
                current.draft.trim(),
                Role.LEARNER
            ) +
                    TextDisplayItem(current.nextId + 1, ""),
            draft = "",
            streaming = true,
            streamTarget = text(R.string.lesson_mock_reply),
            afterStream = Dock.COMPOSER,
            nextId = current.nextId + 2,
            notes = current.notes + 1,
        )
    }

    fun save(): Bundle = with(state.value) {
        Bundle().apply {
            putString("fixture", FIXTURE)
            putParcelableArrayList("items", ArrayList(items.map { it.save() }))
            putString("dock", dock.name)
            putBoolean("expanded", expanded)
            putInt("selection", selection)
            putInt("confidence", confidence)
            putString("thinking", thinking)
            putString("draft", draft)
            putString("streamTarget", streamTarget)
            putBoolean("streaming", streaming)
            putString("afterStream", afterStream.name)
            putLong("nextId", nextId)
            putInt("attempts", attempts)
            putInt("notes", notes)
        }
    }

    @Suppress("DEPRECATION") // Typed Bundle overload requires API 33; minimum is 26.
    private fun restore(saved: Bundle): State = with(saved) {
        State(
            items = getParcelableArrayList<Bundle>("items").orEmpty()
                .map(LessonDisplayItem::restore),
            dock = Dock.valueOf(getString("dock")!!),
            expanded = getBoolean("expanded"),
            selection = getInt("selection", -1),
            confidence = getInt("confidence", 50),
            thinking = getString("thinking").orEmpty(),
            draft = getString("draft").orEmpty(),
            streamTarget = getString("streamTarget").orEmpty(),
            streaming = getBoolean("streaming"),
            afterStream = Dock.valueOf(getString("afterStream")!!),
            nextId = getLong("nextId"),
            attempts = getInt("attempts"),
            notes = getInt("notes"),
        )
    }

    private fun text(id: Int) = resources.getString(id)

    companion object {
        private const val FIXTURE = "gradient-direction-scroll-tables-v3"
        private val RENDERING_SAMPLES = listOf(
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
        const val MAX_INPUT = 2000
        const val MAX_NOTES = 8
        private const val MAX_ATTEMPTS = 3
    }
}
