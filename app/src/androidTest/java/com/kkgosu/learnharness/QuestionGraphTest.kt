package com.kkgosu.learnharness

import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuestionGraphTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private fun options(count: Int = 6) = JSONArray((1..count).map {
        JSONObject().put("label", if (it < 3) "Same visible label" else "Option $it")
            .put("value", "id-$it")
    })

    private fun question() =
        JSONObject().put("question", "Select both.").put("details", "A longer question body.")
            .put("options", options()).put("multiSelect", true)
            .put("correctAnswer", JSONArray(listOf("id-6", "id-1")))
            .put("explanation", "COMMITTED_HIDDEN_EXPLANATION")

    private fun response(name: String, args: JSONObject, id: String) =
        JSONObject().put("status", "completed")
            .put(
                "output", JSONArray().put(
                    JSONObject().put("type", "function_call").put("status", "completed")
                        .put("name", name).put("call_id", id).put("arguments", args.toString())
                )
            )

    private fun clarification() = JSONObject().put("question", "Next topic?").put("details", "")
        .put("multiSelect", false).put("options", JSONArray())

    private fun result(request: JSONObject, id: String) =
        JSONObject(
            request.getJSONArray("input").objects()
                .single { it.optString("type") == "function_call_output" && it.optString("call_id") == id }
                .getString("output")
        )

    private fun rejected(args: JSONObject) {
        try {
            RuntimeTools.validate(
                response("probe", args, "bad"),
                RuntimeSession(goal = "Fixture", model = "fixture")
            ); fail("Invalid question accepted")
        } catch (_: ProviderFailure) {
        }
    }

    @Test
    fun stableIdentitySetGradingAndLegacyFallbackDoNotDependOnLabels() {
        val args = question()
        RuntimeTools.validate(
            response("probe", args, "probe"),
            RuntimeSession(goal = "Fixture", model = "fixture")
        )
        val q = QuestionContract.parse("probe", args, "probe", "probe")
        assertEquals(q, RuntimeQuestion.parse(q.json()))
        val answer = q.answer("My reasoning", -1, 80, "", false, listOf("id-1", "id-6"))
        assertTrue(answer.getBoolean("correct"))
        assertEquals(
            listOf("id-1", "id-6"),
            answer.getJSONArray("selectedValues").strings(32, 2000)
        )
        assertEquals(6, answer.getJSONArray("selections").getJSONObject(1).getInt("index"))
        assertFalse(q.answer("", -1, 80, "", false, listOf("id-2", "id-6")).getBoolean("correct"))
        assertFalse(q.canAnswer("", listOf("id-1", "unknown"), false))
        assertFalse(q.canAnswer("", listOf("id-1", "id-1"), false))
        assertFalse(q.canAnswer("Only prose", emptyList(), false))
        assertFalse(q.transcript().contains("COMMITTED_HIDDEN_EXPLANATION"))
        val card = q.answered(3, answer)
        assertEquals(card, LessonDisplayItem.restore(card.save()))
        rejected(question().put("correctAnswer", "Same visible label"))
        rejected(question().put("multiSelect", false))
        rejected(question().put("correctAnswer", JSONArray(listOf("id-1", "id-1"))))
        rejected(question().put("options", options(33)))
        rejected(question().apply { getJSONArray("options").getJSONObject(1).put("value", "id-1") })
        val fallback =
            question().apply { getJSONArray("options").getJSONObject(5).put("value", "") }
                .put("correctAnswer", "Option 6").put("multiSelect", false)
        assertEquals(
            listOf("Option 6"),
            QuestionContract.parse("fallback", fallback, "probe", "probe").correctValues
        )
        val legacy = RuntimeQuestion("old", "Old question", listOf("Left", "Right"), "Left")
        val oldJson = legacy.json().apply {
            listOf(
                "values",
                "correctValues",
                "multiSelect",
                "details",
                "descriptions"
            ).forEach(::remove)
        }
        assertEquals(legacy, RuntimeQuestion.parse(oldJson))
        assertTrue(
            RuntimeQuestion.parse(oldJson).answer("", 0, 60, "", false).getBoolean("correct")
        )
        val askArgs = clarification().put(
            "options",
            options().apply { objects().forEach { it.put("description", "More detail") } })
        RuntimeTools.validate(
            response("ask_user_question", askArgs, "ask"),
            RuntimeSession(goal = "Fixture", model = "fixture")
        )
        val ask = QuestionContract.parse("ask", askArgs, "probe", "ask_user_question")
        assertEquals("More detail", ask.descriptions.first())
        val other = ask.answer("Written Other", -1, 80, "", false, emptyList())
        assertEquals("Written Other", other.getString("answer"))
        assertFalse(other.has("correct")); assertFalse(other.has("confidence_self_report"))
    }

    @Test
    fun selectionsAndExactSourceCommitmentSurviveSqliteAndReopen() = runBlocking {
        val name = "identity-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val args = question()
                val q = QuestionContract.parse("multi", args, "probe", "probe")
                val source = args.toString()
                val session = RuntimeSession(
                    goal = "Fixture", model = "fixture", status = "question", question = q,
                    selectedValues = listOf("id-6", "id-1"), draft = "My reasoning",
                    protocol = response("probe", args, "multi").getJSONArray("output").toString(),
                    items = listOf(
                        LessonDisplayItem.TextDisplayItem(
                            1,
                            "Fixture",
                            LessonDisplayItem.Role.TITLE
                        )
                    )
                )
                store.save(session)
                assertEquals(session, store.load(session.id))
                var calls = 0
                val runtime = SessionRuntime(app, store, ModelProvider { request, _ ->
                    calls++
                    assertTrue(result(request, "multi").getBoolean("correct"))
                    response("ask_user_question", clarification(), "next")
                })
                try {
                    runtime.open(session.id).join(); assertEquals(0, calls)
                    runtime.send().join(); assertEquals(1, calls)
                    val loaded = store.load(session.id)!!
                    assertEquals("question", loaded.status)
                    assertEquals(
                        listOf("id-1", "id-6"),
                        loaded.answerCards.values.single().selectedValues
                    )
                    assertEquals(q.correctValues, loaded.answerCards.values.single().correctValues)
                    assertEquals(
                        source,
                        JSONArray(loaded.protocol).getJSONObject(0).getString("arguments")
                    )
                    assertTrue(loaded.selectedValues.isEmpty())
                } finally {
                    runtime.stop().join()
                }
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test
    fun graphToolsReadBirthOrganizationWithoutStatementsOrOldTranscript() = runBlocking {
        val name = "graph-read-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val previous = RuntimeSession(
                    goal = "Prior session", model = "fixture", items = listOf(
                        LessonDisplayItem.TextDisplayItem(1, "PRIVATE_OLD_TRANSCRIPT")
                    )
                )
                store.save(previous)
                store.writableDatabase.execSQL(
                    "INSERT INTO knowledge_nodes(id,name,statement,aliases,birth) VALUES(?,?,?,?,?)",
                    arrayOf(
                        "node-a",
                        "Proposition name",
                        "PRIVATE_PROPOSITION_STATEMENT",
                        "[]",
                        previous.id
                    )
                )
                assertEquals(
                    0,
                    store.graphPropositions("missing' OR 1=1 --").getJSONArray("names").length()
                )
                for (phase in listOf("probe", "practice")) {
                    val names = RuntimeTools.schemas(phase).objects().map { it.getString("name") }
                    assertTrue("list_sessions" in names); assertTrue("list_propositions" in names)
                }
                assertFalse(
                    RuntimeTools.schemas("teacher").objects()
                        .any { it.getString("name") == "list_propositions" })
                var calls = 0
                val runtime = SessionRuntime(app, store, ModelProvider { request, _ ->
                    assertFalse(request.toString().contains("PRIVATE_"))
                    when (++calls) {
                        1 -> response("learning_context", JSONObject(), "context")
                        2 -> {
                            assertFalse(result(request, "context").has("propositions"))
                            response("list_sessions", JSONObject(), "sessions")
                        }

                        3 -> {
                            val row = result(request, "sessions").getJSONArray("sessions").objects()
                                .single()
                            assertEquals(previous.id, row.getString("id"))
                            assertEquals(1, row.getInt("propositions"))
                            response(
                                "list_propositions",
                                JSONObject().put("session", row.getString("id")),
                                "names"
                            )
                        }

                        4 -> {
                            assertEquals(
                                "Proposition name",
                                result(request, "names").getJSONArray("names").getString(0)
                            )
                            response("ask_user_question", clarification(), "ask")
                        }

                        else -> error("Unexpected request")
                    }
                })
                try {
                    runtime.create("New goal", "fixture").join()
                    runtime.retry().join()
                    assertEquals(4, calls); assertEquals("question", runtime.state.value!!.status)
                    assertEquals(1, store.knowledge().nodes.size)
                } finally {
                    runtime.stop().join()
                }
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test
    fun nativeMultiSelectRendersAllOptionsAndRestoresTheirValuesWithoutRequests() = runBlocking {
        val q = QuestionContract.parse("ui-${UUID.randomUUID()}", question(), "probe", "probe")
        val session = RuntimeSession(
            goal = "Local multiselect fixture",
            model = "fixture",
            status = "question",
            question = q,
            items = listOf(
                LessonDisplayItem.TextDisplayItem(
                    1,
                    "Fixture",
                    LessonDisplayItem.Role.TITLE
                )
            )
        )
        app.sessions.save(session)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    me.grishka.appkit.Nav.go(
                        it, RuntimeSessionFragment::class.java,
                        Bundle().apply { putString("session", session.id) })
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    val options = activity.findViewById<LinearLayout>(R.id.question_options)
                    assertEquals(6, options.childCount)
                    assertTrue((0 until options.childCount).all { options.getChildAt(it) is CheckBox })
                    val nextAppKitId = activity.generateViewId()
                    (0 until options.childCount).forEach {
                        val option = options.getChildAt(it)
                        assertTrue(
                            "Choices must use AppKit's shared ID sequence",
                            option.id in 1 until nextAppKitId
                        )
                        assertSame(option, activity.findViewById<View>(option.id))
                    }
                    activity.fragmentManager.fragments.forEach {
                        assertTrue(
                            "Transition containers must resolve to ViewGroups",
                            activity.findViewById<View>(it.id) is android.view.ViewGroup
                        )
                    }
                    assertFalse(
                        activity.findViewById<android.widget.TextView>(R.id.question_text).text.contains(
                            "COMMITTED_HIDDEN"
                        )
                    )
                    options.getChildAt(0).performClick(); options.getChildAt(5).performClick()
                    assertTrue((options.getChildAt(0) as CompoundButton).isChecked)
                    assertTrue((options.getChildAt(5) as CompoundButton).isChecked)
                }
                scenario.recreate()
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    val options = activity.findViewById<LinearLayout>(R.id.question_options)
                    assertEquals(6, options.childCount)
                    assertTrue((options.getChildAt(0) as CompoundButton).isChecked)
                    assertTrue((options.getChildAt(5) as CompoundButton).isChecked)
                    activity.findViewById<CheckBox>(R.id.question_dont_know).performClick()
                    assertTrue((0 until options.childCount).none { (options.getChildAt(it) as CompoundButton).isChecked })
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    assertEquals(
                        View.GONE,
                        activity.findViewById<View>(R.id.question_confidence).visibility
                    )
                }
            }
        } finally {
            app.runtime.stop().join()
            app.sessions.writableDatabase.delete("sessions", "id=?", arrayOf(session.id))
        }
    }
}
