package com.kkgosu.learnharness.controllers

import android.content.res.Resources
import android.os.Bundle
import com.kkgosu.learnharness.R
import com.kkgosu.learnharness.model.LessonDisplayItem
import com.kkgosu.learnharness.model.LessonDisplayItem.FormulaDisplayItem
import com.kkgosu.learnharness.model.LessonDisplayItem.QuestionDisplayItem
import com.kkgosu.learnharness.model.LessonDisplayItem.Role
import com.kkgosu.learnharness.model.LessonDisplayItem.TextDisplayItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
            TextDisplayItem(6, ""),
        ),
        streamTarget = text(R.string.lesson_stream),
        streaming = true,
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

    // Also allows deterministic checks without sleeping or a coroutine test framework.
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
            items = current.items + QuestionDisplayItem(
                current.nextId, resources.getStringArray(R.array.lesson_options)[current.selection],
                current.thinking, current.confidence.takeUnless { current.selection == 3 },
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

    // ponytail: a bounded mock fits in saved state; real transcripts move to SQLite in phase 4.
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
        private const val FIXTURE = "gradient-direction-v1"
        const val MAX_INPUT = 2000
        const val MAX_NOTES = 8
        private const val MAX_ATTEMPTS = 3
    }
}
