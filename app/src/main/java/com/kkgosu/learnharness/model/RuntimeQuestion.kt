package com.kkgosu.learnharness.model

import com.kkgosu.learnharness.controllers.QuestionContract
import com.kkgosu.learnharness.utils.strings
import org.json.JSONArray
import org.json.JSONObject

internal data class RuntimeQuestion(
    val callId: String, val text: String, val choices: List<String>,
    val correctAnswer: String = "", val explanation: String = "", val phase: String = "",
    val type: String = "", // Empty only on probe questions and legacy saved questions.
    val requiresChoice: Boolean = false,
    val values: List<String> = choices,
    val correctValues: List<String> = listOf(correctAnswer).filter { it.isNotBlank() && it in values },
    val multiSelect: Boolean = false,
    val details: String = "",
    val descriptions: List<String> = emptyList(),
) {
    val isCheck get() = phase == "teacher" && type == "check"
    val hasConfidence get() = choices.isNotEmpty() && correctAnswer.isNotBlank()
    fun json() =
        JSONObject().put("call", callId).put("text", text).put("choices", JSONArray(choices))
            .put("correctAnswer", correctAnswer).put("explanation", explanation).put("phase", phase)
            .put("type", type).put("requiresChoice", requiresChoice)
            .put("values", JSONArray(values)).put("correctValues", JSONArray(correctValues))
            .put("multiSelect", multiSelect)
            .put("details", details).put("descriptions", JSONArray(descriptions))

    fun transcript() =
        listOf(text, details).filter { it.isNotBlank() }.joinToString("\n\n") +
                if (choices.isEmpty()) "" else choices.joinToString("\n", "\n\n") { "- $it" }

    fun canAnswer(draft: String, selected: List<String>, dontKnow: Boolean): Boolean =
        (dontKnow && type != "ask") || (selected.isNotEmpty() && selected.distinct().size == selected.size &&
                selected.all { it in values } && (multiSelect || selected.size == 1)) || (!requiresChoice && selected.isEmpty() && draft.isNotBlank())

    fun answer(
        draft: String, selection: Int, confidence: Int, readingThinking: String, dontKnow: Boolean,
        selectedValues: List<String>? = null
    ): JSONObject {
        val selected = if (dontKnow) emptyList() else selectedValues ?: listOfNotNull(
            values.getOrNull(selection)
        )
        require(canAnswer(draft, selected, dontKnow))
        val indices = values.indices.filter { values[it] in selected }
        return JSONObject().put("call", callId).put("dontKnow", dontKnow)
            .put(
                "answer",
                if (dontKnow) "" else if (indices.isEmpty()) draft else indices.joinToString("\n") { choices[it] })
            .put("selectedValues", JSONArray(indices.map { values[it] }))
            .put(
                "selections",
                JSONArray(indices.map {
                    JSONObject().put("value", values[it]).put("label", choices[it])
                        .put("index", it + 1)
                })
            )
            .put("reasoning", if (dontKnow || indices.isNotEmpty()) draft else "")
            .apply {
                if (!dontKnow && hasConfidence) put("confidence_self_report", confidence)
                if (!dontKnow && indices.isNotEmpty() && correctValues.isNotEmpty()) put(
                    "correct",
                    selected.toSet() == correctValues.toSet()
                )
                if (isCheck && readingThinking.isNotBlank()) put(
                    "thinkingWhileReading",
                    readingThinking
                )
            }
    }

    fun answered(id: Long, answer: JSONObject) = LessonDisplayItem.AnsweredDisplayItem(
        id,
        text,
        choices,
        answer.optString("answer"),
        answer.optString("reasoning"),
        if (answer.optBoolean("dontKnow") || answer.isNull("confidence_self_report")) null else answer.getInt(
            "confidence_self_report"
        ),
        correctAnswer,
        explanation,
        phase,
        type,
        answer.optBoolean("dontKnow"),
        answer.optString("thinkingWhileReading"), values,
        answer.optJSONArray("selectedValues")?.strings(QuestionContract.MAX_OPTIONS, 4000)
            ?: choices.indices.filter { choices[it] == answer.optString("answer") }
                .map { values[it] },
        correctValues, details, descriptions
    )

    companion object {
        fun parse(q: JSONObject) = RuntimeQuestion(
            q.getString("call"),
            q.getString("text"),
            q.getJSONArray("choices").strings(QuestionContract.MAX_OPTIONS, 4000),
            q.optString("correctAnswer"),
            q.optString("explanation"),
            q.optString("phase"),
            q.optString("type"),
            q.optBoolean("requiresChoice"),
            values = q.optJSONArray("values")?.strings(QuestionContract.MAX_OPTIONS, 4000)
                ?: q.getJSONArray("choices").strings(QuestionContract.MAX_OPTIONS, 4000),
            correctValues = q.optJSONArray("correctValues")
                ?.strings(QuestionContract.MAX_OPTIONS, 4000)
                ?: listOf(q.optString("correctAnswer")).filter {
                    it.isNotBlank() && it in q.getJSONArray(
                        "choices"
                    ).strings(QuestionContract.MAX_OPTIONS, 4000)
                },
            multiSelect = q.optBoolean("multiSelect"),
            details = q.optString("details"),
            descriptions = q.optJSONArray("descriptions")
                ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        ).also { require(it.values.size == it.choices.size && it.values.distinct().size == it.values.size) }
    }
}

internal fun answerText(answer: JSONObject): String = buildList {
    add(if (answer.optBoolean("dontKnow")) "I don’t know." else answer.optString("answer"))
    add(answer.optString("reasoning"))
    answer.optString("thinkingWhileReading").takeIf { it.isNotBlank() }
        ?.let { add("Thinking while reading:\n$it") }
    if (!answer.optBoolean("dontKnow") && !answer.isNull("confidence_self_report"))
        add("Confidence (self-report): ${answer.getInt("confidence_self_report")}%")
}.filter { it.isNotBlank() }.distinct().joinToString("\n\n")
