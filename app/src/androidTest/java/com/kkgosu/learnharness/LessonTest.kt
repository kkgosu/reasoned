package com.kkgosu.learnharness

import android.graphics.Rect
import android.os.Bundle
import android.os.Parcel
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.learnharness.LessonDisplayItem.QuestionDisplayItem
import com.kkgosu.learnharness.LessonDisplayItem.TextDisplayItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LessonTest {
    @Test
    fun incrementalStreamingRepairAndBoundedSavedState() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var lesson = MockLesson(resources)
        val adapter = LessonAdapter()
        val changed = mutableListOf<Int>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { fail("Streaming must not refresh the whole transcript") }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                assertEquals(1, itemCount)
                assertNotNull(payload)
                changed += positionStart
            }
        })
        adapter.submit(lesson.state.value.items)
        val ids = lesson.state.value.items.map { it.id }
        repeat(5) {
            lesson.advanceStream()
            adapter.submit(lesson.state.value.items)
            assertEquals(ids, lesson.state.value.items.map { it.id })
        }
        assertEquals(List(5) { 5 }, changed)
        lesson.draft("A composer draft")
        lesson.stop()
        val stopped = lesson.state.value
        lesson.advanceStream()
        assertEquals(stopped, lesson.state.value)
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(lesson.save())
            parcel.setDataPosition(0)
            lesson = MockLesson(resources, parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
        assertEquals(stopped, lesson.state.value)
        lesson.resume()
        finishStream(lesson)
        assertEquals(MockLesson.Dock.QUESTION, lesson.state.value.dock)
        assertFalse(lesson.state.value.expanded)
        lesson.submitAnswer() // No selection: no fabricated answer or duplicate turn.
        assertEquals(0, lesson.state.value.attempts)
        lesson.select(1)
        lesson.thinking("The slope rises to the right.")
        lesson.confidence(75)
        lesson.submitAnswer()
        lesson.submitAnswer()
        assertEquals(1, lesson.state.value.attempts)
        finishStream(lesson)
        assertEquals(MockLesson.Dock.QUESTION, lesson.state.value.dock)
        lesson.select(3)
        lesson.submitAnswer()
        assertNull(lesson.state.value.items.filterIsInstance<QuestionDisplayItem>().last().confidence)
        finishStream(lesson)
        lesson.select(0)
        lesson.submitAnswer()
        finishStream(lesson)
        assertEquals(MockLesson.Dock.COMPOSER, lesson.state.value.dock)
        assertEquals("A composer draft", lesson.state.value.draft)
        val uncertain = MockLesson(resources)
        repeat(3) {
            finishStream(uncertain)
            uncertain.select(3)
            uncertain.submitAnswer()
        }
        finishStream(uncertain)
        assertEquals(MockLesson.Dock.COMPOSER, uncertain.state.value.dock)
        assertEquals(resources.getString(R.string.lesson_reveal),
            (uncertain.state.value.items.last() as TextDisplayItem).text)
        uncertain.submitAnswer()
        assertEquals(3, uncertain.state.value.attempts)
        repeat(MockLesson.MAX_NOTES) {
            lesson.draft("x".repeat(MockLesson.MAX_INPUT + 1))
            assertEquals(MockLesson.MAX_INPUT, lesson.state.value.draft.length)
            lesson.send()
            finishStream(lesson)
        }
        val full = lesson.state.value
        lesson.draft("one more")
        lesson.send()
        assertEquals(full.items, lesson.state.value.items)
        assertEquals(full.items.size, full.items.map { it.id }.distinct().size)
        val bounded = Parcel.obtain()
        try {
            bounded.writeBundle(lesson.save())
            assertTrue("Mock state should remain small", bounded.dataSize() < 128 * 1024)
        } finally { bounded.recycle() }
    }

    @Test
    fun questionAndComposerSurviveMorphRecreationAndNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLesson(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.lesson_composer).setText("What if the step is too large?")
                finishStream(activity.mockLesson)
            }
            idle()
            scenario.onActivity { it.findViewById<Button>(R.id.reveal_question).performClick() }
            idle()
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<Button>(R.id.submit_answer).isEnabled)
                activity.findViewById<RadioButton>(R.id.option_left).performClick()
                activity.findViewById<EditText>(R.id.question_thinking).setText("Subtracting a positive slope moves left.")
                val progress = Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 75f)
                }
                assertTrue(activity.findViewById<SeekBar>(R.id.question_confidence).performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, progress,
                ))
                activity.findViewById<Button>(R.id.collapse_question).performClick()
            }
            idle()
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("What if the step is too large?", activity.mockLesson.state.value.draft)
                assertEquals(75, activity.mockLesson.state.value.confidence)
                activity.findViewById<Button>(R.id.reveal_question).performClick()
            }
            idle()
            scenario.onActivity { activity ->
                assertEquals(R.id.option_left, activity.findViewById<RadioGroup>(R.id.question_options).checkedRadioButtonId)
                assertEquals("Subtracting a positive slope moves left.", activity.findViewById<EditText>(R.id.question_thinking).text.toString())
                assertEquals(75, activity.findViewById<SeekBar>(R.id.question_confidence).progress)
                activity.findViewById<Button>(R.id.submit_answer).performClick()
            }
            idle()
            scenario.onActivity { it.findViewById<Button>(R.id.composer_action).performClick() } // Stop.
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.mockLesson.state.value.streaming)
                assertEquals(activity.getString(R.string.resume_stream), activity.findViewById<Button>(R.id.composer_action).text)
                activity.findViewById<Button>(R.id.composer_action).performClick()
                finishStream(activity.mockLesson)
            }
            idle()
            scenario.onActivity { activity ->
                assertEquals("What if the step is too large?", activity.findViewById<EditText>(R.id.lesson_composer).text.toString())
                activity.onBackPressed()
            }
            scenario.recreate() // Finish AppKit's pending return transition.
            scenario.onActivity { it.openHomeAction(R.id.open_sample_lesson) }
            idle()
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("What if the step is too large?", activity.findViewById<EditText>(R.id.lesson_composer).text.toString())
                activity.findViewById<Button>(R.id.composer_action).performClick()
                assertEquals(1, activity.mockLesson.state.value.notes)
                assertEquals("", activity.mockLesson.state.value.draft)
            }
        }
    }

    @Test
    fun streamingDoesNotPullReaderAwayFromEarlierRows() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLesson(scenario)
            scenario.onActivity { activity ->
                val lesson = activity.mockLesson
                finishStream(lesson)
                lesson.select(0)
                lesson.submitAnswer()
                finishStream(lesson)
                repeat(3) {
                    lesson.draft("A note about gradients")
                    lesson.send()
                    finishStream(lesson)
                }
                lesson.draft("One more note")
                lesson.send()
                lesson.stop()
            }
            idle()
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.lesson_list).scrollToPosition(0) }
            idle()
            var offset = 0
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.lesson_list)
                assertTrue(list.canScrollVertically(1))
                offset = list.getChildAt(0).top
                activity.mockLesson.resume()
                activity.mockLesson.advanceStream()
                activity.mockLesson.stop()
            }
            idle()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.lesson_list)
                assertEquals(0, (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
                assertEquals(offset, list.getChildAt(0).top)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.lesson_list)
                assertEquals(0, (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun keyboardLeavesAnEditableAnswerAndReachableSubmit() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLesson(scenario)
            scenario.onActivity { finishStream(it.mockLesson) }
            idle()
            scenario.onActivity { it.findViewById<Button>(R.id.reveal_question).performClick() }
            idle()
            scenario.onActivity { it.findViewById<ScrollView>(R.id.question_scroll).fullScroll(View.FOCUS_DOWN) }
            idle()
            scenario.onActivity { activity ->
                val editor = activity.findViewById<EditText>(R.id.question_thinking)
                editor.requestFocus()
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, 0)
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5000)
            scenario.onActivity { activity ->
                val insets = activity.window.decorView.rootWindowInsets
                assertTrue(insets.isVisible(WindowInsets.Type.ime()))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.lesson_list).visibility)
                val scroll = activity.findViewById<ScrollView>(R.id.question_scroll)
                val editor = activity.findViewById<EditText>(R.id.question_thinking)
                assertTrue("At least one full text line must remain visible", scroll.height >= editor.lineHeight)
                val visibleEditor = Rect()
                assertTrue("The focused editor must be scrolled into view",
                    editor.getGlobalVisibleRect(visibleEditor) && visibleEditor.height() >= editor.lineHeight)
                val submit = activity.findViewById<Button>(R.id.submit_answer)
                val location = IntArray(2)
                submit.getLocationOnScreen(location)
                val keyboardTop = activity.window.decorView.height - insets.getInsets(WindowInsets.Type.ime()).bottom
                assertTrue("Submit must be above the keyboard", location[1] + submit.height <= keyboardTop)
                activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
                activity.findViewById<View>(R.id.session_root).requestFocus()
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5000)
            scenario.onActivity { assertEquals(View.VISIBLE, it.findViewById<View>(R.id.lesson_list).visibility) }
        }
    }

    @Test
    fun captureLessonScreens() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            capture("phase2-home")
            openLesson(scenario)
            scenario.onActivity { finishStream(it.mockLesson) }
            idle()
            scenario.recreate()
            capture("phase2-reading")
            scenario.onActivity { it.findViewById<Button>(R.id.reveal_question).performClick() }
            idle()
            capture("phase2-question")
            scenario.onActivity { activity ->
                activity.findViewById<RadioButton>(R.id.option_left).performClick()
                activity.findViewById<EditText>(R.id.question_thinking).setText("A positive slope rises to the right, so I would step left.")
                activity.findViewById<ScrollView>(R.id.question_scroll).fullScroll(View.FOCUS_DOWN)
            }
            idle()
            capture("phase2-thinking")
            scenario.onActivity { activity ->
                activity.findViewById<Button>(R.id.submit_answer).performClick()
                finishStream(activity.mockLesson)
            }
            idle()
            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.lesson_list)
                    .scrollToPosition(activity.mockLesson.state.value.items.lastIndex)
            }
            capture("phase2-feedback")
        }
    }

    private fun openLesson(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.openHomeAction(R.id.open_sample_lesson) }
        idle()
        scenario.recreate() // Also finishes the entry transition before inspection.
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    private fun finishStream(lesson: MockLesson) {
        var steps = 0
        while (lesson.state.value.streaming) {
            check(steps++ < 1000) { "Mock stream failed to complete" }
            lesson.advanceStream()
        }
    }
}
