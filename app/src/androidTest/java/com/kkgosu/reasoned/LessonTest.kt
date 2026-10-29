package com.kkgosu.reasoned

import android.graphics.Rect
import android.os.Parcel
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.controllers.MockLesson
import com.kkgosu.reasoned.fragments.RuntimeSessionFragment
import com.kkgosu.reasoned.model.LessonDisplayItem
import com.kkgosu.reasoned.storage.knowledge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LessonTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private lateinit var lesson: MockLesson
    private val sampleField = ReasonedApplication::class.java.getDeclaredField("sample").apply { isAccessible = true }
    private var previous: Any? = null

    @Before
    fun setUp() {
        previous = sampleField.get(app)
        lesson = MockLesson(app)
        sampleField.set(app, lesson)
    }

    @After
    fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { lesson.runtime.stop() }.join()
        sampleField.set(app, previous)
        lesson.store.close()
    }

    @Test
    fun realRuntimeRunsOfflineProbePlanTeachAndRestoresWithoutRequests() = runBlocking {
        val realSessions = app.sessions.list()
        command { lesson.runtime.open(lesson.sessionId) }
        var state = lesson.session()
        assertEquals("probe", state.phase)
        assertEquals("question", state.status)
        assertEquals(0, lesson.requests)
        val correct = state.question!!.correctValues.single()
        withContext(Dispatchers.Main) { lesson.runtime.edit("A retained draft", -1, 90, false, selectedValues = listOf(correct)) }
        val parcel = Parcel.obtain()
        val restored = try {
            parcel.writeBundle(lesson.save())
            assertTrue("Sample state should remain bounded", parcel.dataSize() < 128 * 1024)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            MockLesson(app, parcel.readBundle(javaClass.classLoader))
        } finally { parcel.recycle() }
        try {
            command { restored.runtime.open(restored.sessionId) }
            assertEquals(0, restored.requests)
            assertEquals("A retained draft", restored.session().draft)
            assertEquals(listOf(correct), restored.session().selectedValues)
            assertEquals(90, restored.session().confidence)
            assertFalse(restored.session().expanded)
            assertEquals(state.nativeRecord, restored.session().nativeRecord)
        } finally {
            command { restored.runtime.stop() }
            restored.store.close()
        }
        withContext(Dispatchers.Main) { lesson.runtime.edit("", -1, 90, true, selectedValues = listOf(correct)) }
        command { lesson.runtime.send() }
        state = lesson.session()
        assertEquals(3, state.assessment!!.strands.size)
        assertTrue(state.displayItems.any { it is LessonDisplayItem.AssessmentDisplayItem })
        assertEquals(4, state.plan!!.nodes.size)
        assertEquals("plan", state.nativeRecord!!.state().phase)
        assertEquals(2, lesson.requests)
        command { lesson.runtime.open(lesson.sessionId) }
        assertEquals(2, lesson.requests) // Opening a checkpoint never resumes the mock either.
        withContext(Dispatchers.Main) { lesson.runtime.edit("Proceed with this sample plan", -1, 50, true) }
        command { lesson.runtime.send() }
        assertEquals("slope", lesson.session().nativeRecord!!.state().current)
        repeat(2) {
            withContext(Dispatchers.Main) { lesson.runtime.edit("", -1, 50, true, dontKnow = true) }
            command { lesson.runtime.send() }
            assertEquals("quiz", lesson.session().nativeRecord!!.pendingQuestion()!!.tool)
        }
        state = lesson.session()
        withContext(Dispatchers.Main) {
            lesson.runtime.edit("Subtract the positive slope", -1, 90, true,
                dontKnow = false, selectedValues = state.question!!.correctValues)
        }
        command { lesson.runtime.send() }
        state = lesson.session()
        assertEquals("check", state.nativeRecord!!.pendingQuestion()!!.question.type)
        assertFalse(state.expanded) // The same native reveal law as a real teaching check.
        assertEquals("update", state.nativeRecord.state().current)
        assertTrue("slope" in state.nativeRecord.state().visited)
        withContext(Dispatchers.Main) {
            lesson.runtime.edit("A small step moves toward zero", -1, 50, true, selectedValues = state.question!!.correctValues)
        }
        command { lesson.runtime.send() }
        assertNull(lesson.session().question)
        assertTrue(lesson.session().answerCards.values.last().correctValues.isNotEmpty())
        withContext(Dispatchers.Main) { lesson.runtime.edit("An offline note", -1, 50, false) }
        val sending = withContext(Dispatchers.Main) { lesson.runtime.send() }
        withTimeout(5000) { lesson.runtime.state.first { it?.status == "running" } }
        command { lesson.runtime.stop() }
        sending.join()
        assertEquals("interrupted", lesson.session().status)
        val requests = lesson.requests
        command { lesson.runtime.open(lesson.sessionId) }
        assertEquals(requests, lesson.requests)
        command { lesson.runtime.retry() }
        assertEquals(requests, lesson.requests) // Native Stop needs a new learner message, not legacy resume.
        withContext(Dispatchers.Main) { lesson.runtime.edit("Continue with an offline reply", -1, 50, false) }
        command { lesson.runtime.send() }
        assertEquals("idle", lesson.session().status)
        assertEquals(realSessions, app.sessions.list()) // Synthetic evidence never reaches the real database.
        assertTrue(lesson.store.knowledge().nodes.isEmpty())
    }

    @Test
    fun sampleUsesRealChatControlsScrollButtonAndRestoresAnswerDraft() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLesson(scenario)
            val requests = lesson.requests
            scenario.onActivity { activity ->
                activity.findViewById<RecyclerView>(R.id.lesson_list).scrollToPosition(0)
            }
            idle()
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.scroll_to_bottom)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.question_panel).visibility)
                assertEquals(View.INVISIBLE, button.visibility)
                val position = button.layoutParams as android.widget.FrameLayout.LayoutParams
                assertEquals(android.view.Gravity.END, position.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
                assertEquals(0, (activity.findViewById<View>(R.id.dock_shell).layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin)
                val fragment = activity.fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>().single()
                val toolbar = fragment.view!!.findViewById<android.widget.Toolbar>(me.grishka.appkit.R.id.toolbar)
                assertEquals(1, toolbar.menu.size())
                assertNotNull(toolbar.menu.findItem(R.id.session_plan))
                assertFalse(activity.findViewById<Button>(R.id.session_model).isEnabled)
                assertFalse(activity.findViewById<Button>(R.id.session_thinking).isEnabled)
                assertFalse(activity.findViewById<Button>(R.id.composer_action).isEnabled)
                val options = activity.findViewById<LinearLayout>(R.id.question_options)
                (options.getChildAt(0) as CompoundButton).performClick()
                activity.findViewById<EditText>(R.id.question_thinking).setText("Retained sample reasoning")
                activity.findViewById<SeekBar>(R.id.question_confidence).progress = 4
                // Persist through the same text watcher after the discrete confidence control changes.
                activity.findViewById<EditText>(R.id.question_thinking).append(".")
                activity.findViewById<Button>(R.id.collapse_question).performClick()
                activity.findViewById<RecyclerView>(R.id.lesson_list).scrollToPosition(0)
            }
            idle()
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.lesson_list)
                assertNotNull(list.foreground)
                assertTrue(list.canScrollVertically(1))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.scroll_to_bottom).visibility)
                assertEquals(0, (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
            }
            withTimeout(5000) {
                var opaque = false
                while (!opaque) {
                    scenario.onActivity { activity ->
                        opaque = activity.findViewById<View>(R.id.scroll_to_bottom).alpha == 1f
                    }
                    if (!opaque) kotlinx.coroutines.delay(20)
                }
            }
            scenario.onActivity { it.findViewById<Button>(R.id.reveal_question).performClick() }
            withTimeout(5000) {
                var hiding = false
                while (!hiding) {
                    scenario.onActivity { hiding = !it.findViewById<View>(R.id.scroll_to_bottom).isEnabled }
                    if (!hiding) kotlinx.coroutines.delay(20)
                }
            }
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.scroll_to_bottom)
                if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    assertEquals("Hide must finish its fade before making the button invisible", View.VISIBLE, button.visibility)
                    assertTrue(button.alpha > 0f)
                }
                // Reverse an in-flight hide; its old completion must not hide the button later.
                activity.findViewById<Button>(R.id.collapse_question).performClick()
            }
            awaitScrollButton(scenario, shown = true)
            scenario.onActivity { it.findViewById<View>(R.id.scroll_to_bottom).performClick() }
            val deadline = android.os.SystemClock.uptimeMillis() + 8000
            var bottom = false
            while (!bottom && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.lesson_list)
                    bottom = !list.canScrollVertically(1) && list.scrollState == RecyclerView.SCROLL_STATE_IDLE
                }
                if (!bottom) kotlinx.coroutines.delay(20)
            }
            awaitScrollButton(scenario, shown = false)
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.lesson_list)
                val manager = list.layoutManager as LinearLayoutManager
                val last = manager.findViewByPosition(list.adapter!!.itemCount - 1)
                assertTrue("Scroll did not reach bottom: last=${last?.bottom}, viewport=${list.height - list.paddingBottom}, state=${list.scrollState}", bottom)
                assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.scroll_to_bottom).visibility)
            }
            scenario.recreate()
            awaitQuestionViews(scenario)
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.lesson_list)
                assertEquals(list.canScrollVertically(1), activity.findViewById<View>(R.id.scroll_to_bottom).isEnabled)
                assertEquals("Retained sample reasoning.", lesson.session().draft)
                assertEquals(90, lesson.session().confidence)
                assertFalse(lesson.session().expanded)
                activity.findViewById<Button>(R.id.reveal_question).performClick()
            }
            idle()
            scenario.onActivity { activity ->
                assertEquals("Retained sample reasoning.", activity.findViewById<EditText>(R.id.question_thinking).text.toString())
                assertTrue((activity.findViewById<LinearLayout>(R.id.question_options).getChildAt(0) as CompoundButton).isChecked)
                assertEquals(4, activity.findViewById<SeekBar>(R.id.question_confidence).progress)
                activity.findViewById<RecyclerView>(R.id.lesson_list).scrollToPosition(0)
            }
            awaitScrollButton(scenario, shown = false)
            scenario.onActivity { activity ->
                assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.scroll_to_bottom).visibility)
            }
            assertEquals(requests, lesson.requests)
        }
    }

    @Test
    fun sampleAssessmentAndNativePlanUseSharedScreensAndSurviveNavigation() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLesson(scenario)
            scenario.onActivity { activity ->
                activity.findViewById<CheckBox>(R.id.question_dont_know).performClick()
                activity.findViewById<Button>(R.id.composer_action).performClick()
            }
            withTimeout(8000) { lesson.runtime.state.first { it?.plan != null && it.status == "idle" } }
            idle()
            val requests = lesson.requests
            scenario.onActivity { activity ->
                val fragment = activity.fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>().single()
                val toolbar = fragment.view!!.findViewById<android.widget.Toolbar>(me.grishka.appkit.R.id.toolbar)
                assertTrue(toolbar.menu.performIdentifierAction(R.id.session_plan, 0))
            }
            idle()
            scenario.recreate()
            idle()
            scenario.onActivity { activity ->
                val nodes = activity.findViewById<LinearLayout>(R.id.plan_nodes)
                assertEquals(5, nodes.childCount)
                for (i in 1..4) assertTrue((nodes.getChildAt(i) as TextView).text.contains(activity.getString(R.string.plan_upcoming)))
                assertEquals(requests, lesson.requests)
                activity.onBackPressed()
            }
            idle()
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.lesson_composer).setText("Continue the sample")
                activity.findViewById<Button>(R.id.composer_action).performClick()
            }
            withTimeout(8000) { lesson.runtime.state.first { it?.question?.type == "predict" } }
            idle()
            scenario.onActivity { activity ->
                val fragment = activity.fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>().single()
                fragment.view!!.findViewById<android.widget.Toolbar>(me.grishka.appkit.R.id.toolbar)
                    .menu.performIdentifierAction(R.id.session_plan, 0)
            }
            idle()
            scenario.onActivity { activity ->
                val nodes = activity.findViewById<LinearLayout>(R.id.plan_nodes)
                assertTrue((nodes.getChildAt(2) as TextView).text.contains(activity.getString(R.string.plan_current)))
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun keyboardKeepsSharedTranscriptVisibleAndSubmitReachable() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLesson(scenario)
            scenario.onActivity { activity ->
                val editor = activity.findViewById<EditText>(R.id.question_thinking)
                editor.requestFocus()
                activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor, 0)
            }
            withTimeout(5000) {
                var visible = false
                while (!visible) {
                    scenario.onActivity { activity ->
                        val insets = activity.window.decorView.rootWindowInsets
                        val root = activity.fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>().single().view!!
                        visible = insets.isVisible(WindowInsets.Type.ime()) &&
                            root.paddingBottom == insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars()).bottom &&
                            !activity.findViewById<View>(R.id.composer_action).isLayoutRequested
                    }
                    if (!visible) kotlinx.coroutines.delay(20)
                }
            }
            idle()
            scenario.onActivity { activity ->
                val insets = activity.window.decorView.rootWindowInsets
                assertTrue(insets.isVisible(WindowInsets.Type.ime()))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.lesson_list).visibility)
                val editor = activity.findViewById<EditText>(R.id.question_thinking)
                val rect = Rect()
                assertTrue(editor.getGlobalVisibleRect(rect) && rect.height() >= editor.lineHeight)
                val submit = activity.findViewById<Button>(R.id.composer_action)
                val location = IntArray(2)
                submit.getLocationOnScreen(location)
                assertTrue("Submit must remain above the fully shown keyboard",
                    location[1] + submit.height <= activity.window.decorView.height - insets.getInsets(WindowInsets.Type.ime()).bottom)
                activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
            }
        }
    }

    private suspend fun command(action: () -> Job) {
        val job = withContext(Dispatchers.Main) { action() }
        withTimeout(8000) { job.join() }
        assertEquals("", lesson.runtime.failure.value)
    }

    private suspend fun openLesson(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.openHomeAction(R.id.open_sample_lesson) }
        withTimeout(5000) { lesson.runtime.state.first { it != null } }
        idle()
        scenario.recreate()
        awaitQuestionViews(scenario)
    }

    private suspend fun awaitScrollButton(scenario: ActivityScenario<MainActivity>, shown: Boolean) {
        withTimeout(5000) {
            var settled = false
            while (!settled) {
                scenario.onActivity { activity ->
                    val button = activity.findViewById<View>(R.id.scroll_to_bottom)
                    settled = button.isEnabled == shown &&
                        button.visibility == (if (shown) View.VISIBLE else View.INVISIBLE)
                    if (shown) settled = settled && button.alpha == 1f
                }
                if (!settled) kotlinx.coroutines.delay(20)
            }
        }
    }

    private suspend fun awaitQuestionViews(scenario: ActivityScenario<MainActivity>) {
        withTimeout(5000) {
            var ready = false
            while (!ready) {
                scenario.onActivity { activity ->
                    val options = activity.findViewById<LinearLayout>(R.id.question_options)
                    ready = options != null && options.childCount > 0 && options.tag == lesson.session().question?.callId
                }
                if (!ready) kotlinx.coroutines.delay(20)
            }
        }
        idle()
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()
}
