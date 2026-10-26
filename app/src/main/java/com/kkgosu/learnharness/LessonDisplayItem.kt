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
        val confidence: Int,
        val correctAnswer: String = "",
        val explanation: String = "",
        val phase: String = "",
    ) : LessonDisplayItem(id)

    enum class Role { TITLE, PROSE, LEARNER, QUESTION, THINKING, FIGURE }

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
                putInt("confidence", item.confidence)
                putString("correctAnswer", item.correctAnswer)
                putString("explanation", item.explanation)
                putString("phase", item.phase)
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
                    getInt("confidence"),
                    getString("correctAnswer").orEmpty(),
                    getString("explanation").orEmpty(),
                    getString("phase").orEmpty()
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
