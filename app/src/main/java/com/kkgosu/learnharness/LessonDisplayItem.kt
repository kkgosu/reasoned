package com.kkgosu.learnharness

import android.os.Bundle

sealed class LessonDisplayItem(open val id: Long) {
    data class TextDisplayItem(
        override val id: Long,
        val text: String,
        val role: Role = Role.PROSE
    ) : LessonDisplayItem(id)

    data class FormulaDisplayItem(override val id: Long, val text: String, val spoken: String) :
        LessonDisplayItem(id)

    data class QuestionDisplayItem(
        override val id: Long,
        val answer: String,
        val thinking: String,
        val confidence: Int?,
    ) : LessonDisplayItem(id)

    data class AnsweredDisplayItem(
        override val id: Long,
        val question: String,
        val choices: List<String>,
        val answer: String,
        val thinking: String,
        val confidence: Int?,
        val correctAnswer: String = "",
        val explanation: String = "",
        val phase: String = "",
        val questionType: String = "",
        val dontKnow: Boolean = false,
        val readingThinking: String = "",
        val values: List<String> = choices,
        val selectedValues: List<String> = listOf(answer).filter { it in values },
        val correctValues: List<String> = listOf(correctAnswer).filter { it.isNotBlank() && it in values },
        val details: String = "",
        val descriptions: List<String> = emptyList(),
    ) : LessonDisplayItem(id)

    enum class Role { TITLE, PROSE, LEARNER, QUESTION, THINKING, FIGURE, RESEARCH, PLAN, ASSESSMENT, PRACTICE }

    fun save(): Bundle = Bundle().apply {
        putLong("id", id)
        when (val item = this@LessonDisplayItem) {
            is TextDisplayItem -> {
                putString("type", "text")
                putString("text", item.text)
                putString("role", item.role.name)
            }

            is AnsweredDisplayItem -> {
                putString("type", "answered")
                putString("question", item.question)
                putStringArrayList("choices", ArrayList(item.choices))
                putString("answer", item.answer)
                putString("thinking", item.thinking)
                putInt("confidence", item.confidence ?: -1)
                putString("questionType", item.questionType)
                putBoolean("dontKnow", item.dontKnow)
                putString("readingThinking", item.readingThinking)
                putString("correctAnswer", item.correctAnswer)
                putString("explanation", item.explanation)
                putString("phase", item.phase)
                putStringArrayList("values", ArrayList(item.values))
                putStringArrayList("selectedValues", ArrayList(item.selectedValues))
                putStringArrayList("correctValues", ArrayList(item.correctValues))
                putString("details", item.details)
                putStringArrayList("descriptions", ArrayList(item.descriptions))
            }

            is FormulaDisplayItem -> {
                putString("type", "formula")
                putString("text", item.text)
                putString("spoken", item.spoken)
            }

            is QuestionDisplayItem -> {
                putString("type", "question")
                putString("answer", item.answer)
                putString("thinking", item.thinking)
                putInt("confidence", item.confidence ?: -1)
            }
        }
    }

    companion object {
        fun restore(bundle: Bundle): LessonDisplayItem = with(bundle) {
            when (getString("type")) {
                "answered" -> AnsweredDisplayItem(
                    getLong("id"),
                    getString("question").orEmpty(),
                    getStringArrayList("choices").orEmpty(),
                    getString("answer").orEmpty(),
                    getString("thinking").orEmpty(),
                    getInt("confidence", -1).takeIf { it in 0..100 },
                    getString("correctAnswer").orEmpty(),
                    getString("explanation").orEmpty(),
                    getString("phase").orEmpty(),
                    getString("questionType").orEmpty(),
                    getBoolean("dontKnow"),
                    getString("readingThinking").orEmpty(),
                    getStringArrayList("values") ?: getStringArrayList("choices").orEmpty(),
                    getStringArrayList("selectedValues")
                        ?: listOf(getString("answer").orEmpty()).filter { it in getStringArrayList("choices").orEmpty() },
                    getStringArrayList("correctValues")
                        ?: listOf(getString("correctAnswer").orEmpty()).filter {
                            it.isNotBlank() && it in getStringArrayList(
                                "choices"
                            ).orEmpty()
                        },
                    getString("details").orEmpty(), getStringArrayList("descriptions").orEmpty()
                )

                "formula" -> FormulaDisplayItem(
                    getLong("id"),
                    getString("text").orEmpty(),
                    getString("spoken").orEmpty()
                )

                "question" -> QuestionDisplayItem(
                    getLong("id"), getString("answer").orEmpty(), getString("thinking").orEmpty(),
                    getInt("confidence", -1).takeIf { it in 0..100 },
                )

                else -> TextDisplayItem(
                    getLong("id"), getString("text").orEmpty(),
                    Role.entries.firstOrNull { it.name == getString("role") } ?: Role.PROSE,
                )
            }
        }
    }
}
