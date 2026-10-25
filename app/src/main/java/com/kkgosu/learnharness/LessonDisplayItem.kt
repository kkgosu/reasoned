package com.kkgosu.learnharness

import android.os.Bundle

sealed class LessonDisplayItem(open val id: Long) {
    data class TextDisplayItem(override val id: Long, val text: String, val role: Role = Role.PROSE) : LessonDisplayItem(id)
    data class FormulaDisplayItem(override val id: Long, val text: String, val spoken: String) : LessonDisplayItem(id)
    data class QuestionDisplayItem(
        override val id: Long,
        val answer: String,
        val thinking: String,
        val confidence: Int?,
    ) : LessonDisplayItem(id)

    enum class Role { TITLE, PROSE, LEARNER }

    fun save(): Bundle = Bundle().apply {
        putLong("id", id)
        when (val item = this@LessonDisplayItem) {
            is TextDisplayItem -> {
                putString("type", "text")
                putString("text", item.text)
                putString("role", item.role.name)
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
                "formula" -> FormulaDisplayItem(getLong("id"), getString("text").orEmpty(), getString("spoken").orEmpty())
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
