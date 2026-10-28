package com.kkgosu.reasoned

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.controllers.RuntimeTools
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.model.LessonDisplayItem
import com.kkgosu.reasoned.model.ProbeAssessment
import com.kkgosu.reasoned.model.RuntimeQuestion
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.model.StudyPlan
import com.kkgosu.reasoned.model.answerText
import com.kkgosu.reasoned.storage.SessionStore
import com.kkgosu.reasoned.utils.objects
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LearningFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private fun response(
        name: String,
        args: JSONObject,
        id: String = UUID.randomUUID().toString(),
        text: String = ""
    ) =
        JSONObject().put("status", "completed").put("output", JSONArray().apply {
            if (text.isNotBlank()) put(
                JSONObject().put("type", "message").put("role", "assistant")
                    .put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "output_text").put("text", text))
                    )
            )
            put(
                JSONObject().put("type", "function_call").put("status", "completed")
                    .put("name", name).put("call_id", id).put("arguments", args.toString())
            )
        })

    private fun question(type: String, node: String = "a") = JSONObject()
        .put("question", "Which direction?").put("details", "").put("multiSelect", false)
        .put(
            "options",
            JSONArray(listOf("Left", "Right").map {
                JSONObject().put("label", it).put("value", it)
            })
        )
        .put("explanation", "Subtracting moves left.").put("correctAnswer", "Left")
        .put("type", type).put("nodeId", node)

    private fun result(request: JSONObject, id: String) =
        JSONObject(
            request.getJSONArray("input").objects()
                .single { it.optString("type") == "function_call_output" && it.optString("call_id") == id }
                .getString("output")
        )

    private fun rejected(block: () -> Unit) {
        try {
            block(); fail("Invalid contract accepted")
        } catch (_: ProviderFailure) {
        }
    }

    @Test
    fun predictionGapThenReadingCheckSurviveReopenAndPlanRewriteDoesNotPause() = runBlocking {
        val name = "learning-cycle-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val plan = StudyPlan(listOf(StudyPlan.Node("a", "Movement", "First")), emptyList())
                val saved = RuntimeSession(
                    goal = "Number line", model = "fixture", phase = "teacher", plan = plan,
                    items = listOf(
                        LessonDisplayItem.TextDisplayItem(
                            1,
                            "Number line",
                            LessonDisplayItem.Role.TITLE
                        )
                    )
                )
                store.save(saved)
                var calls = 0
                val provider = ModelProvider { request, _ ->
                    when (++calls) {
                        1 -> response("quiz", question("predict"), "prediction")
                        2 -> {
                            val gap = result(request, "prediction")
                            assertTrue(gap.getBoolean("dontKnow"))
                            assertEquals("", gap.getString("answer"))
                            assertEquals("I cannot work this out", gap.getString("reasoning"))
                            assertFalse(gap.has("confidence_self_report"))
                            assertFalse(gap.has("correct"))
                            assertFalse(gap.has("thinkingWhileReading"))
                            response(
                                "quiz",
                                question("check"),
                                "check",
                                "Subtracting takes one step left."
                            )
                        }

                        3 -> {
                            val answer = result(request, "check")
                            assertEquals("Question thought", answer.getString("reasoning"))
                            assertEquals(
                                "Reading thought",
                                answer.getString("thinkingWhileReading")
                            )
                            assertEquals(80, answer.getInt("confidence_self_report"))
                            assertTrue(answer.getBoolean("correct"))
                            response(
                                "plan_init", plan.copy(
                                    nodes = plan.nodes + StudyPlan.Node("b", "Negatives", "Next"),
                                    edges = listOf("a" to "b")
                                ).json().apply { remove("version") }, "rewrite"
                            )
                        }

                        4 -> {
                            assertFalse(
                                result(
                                    request,
                                    "rewrite"
                                ).getBoolean("awaitingLearnerApproval")
                            )
                            response("quiz", question("predict", "b"), "next-prediction")
                        }

                        else -> error("Unexpected request")
                    }
                }
                val runtime = SessionRuntime(app, store, provider)
                runtime.open(saved.id).join(); runtime.retry().join()
                assertEquals("predict", runtime.state.value!!.question!!.type)
                assertTrue(runtime.state.value!!.expanded)
                runtime.edit(
                    "I cannot work this out",
                    0,
                    80,
                    true,
                    "Not a reading channel on predict",
                    true
                )
                runtime.send().join()
                assertEquals(2, calls)
                assertTrue(runtime.state.value!!.question!!.isCheck)
                assertFalse(runtime.state.value!!.expanded)
                assertFalse(runtime.state.value!!.dontKnow)
                val gapCard = runtime.state.value!!.answerCards.values.single()
                assertTrue(gapCard.dontKnow); assertNull(gapCard.confidence)
                runtime.edit("Question thought", 0, 80, false, "Reading thought", false)
                runtime.stop().join()
                val checkpoint = store.load(saved.id)!!
                assertEquals("Question thought", checkpoint.draft)
                assertEquals("Reading thought", checkpoint.readingThinking)
                assertEquals("check", checkpoint.question!!.type)
                assertTrue(checkpoint.planApproved)
                val reopened = SessionRuntime(app, store, provider)
                try {
                    reopened.open(saved.id).join()
                    assertEquals(2, calls) // Reopening never reissues a request.
                    reopened.send().join()
                    val state = store.load(saved.id)!!
                    assertEquals(4, calls)
                    assertEquals("question", state.status)
                    assertFalse(state.planPending); assertTrue(state.planApproved)
                    assertEquals(2, state.plan!!.version)
                    assertEquals("b", state.plan.nodes.last().id)
                    assertTrue(state.expanded)
                    val card = state.answerCards.values.single { it.questionType == "check" }
                    assertEquals("Question thought", card.thinking)
                    assertEquals("Reading thought", card.readingThinking)
                    assertEquals(80, card.confidence)
                    assertEquals("", state.readingThinking)
                } finally {
                    reopened.stop().join()
                }
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test
    fun contractsRejectMissingRolesAndPreserveFreeRecallAndLegacyQuestions() {
        val plan = StudyPlan(listOf(StudyPlan.Node("a", "Movement", "First")), emptyList())
        val session =
            RuntimeSession(goal = "Fixture", model = "fixture", phase = "teacher", plan = plan)
        for (type in listOf("predict", "check"))
            RuntimeTools.validate(response("quiz", question(type)), session)
        rejected { RuntimeTools.validate(response("quiz", question("other")), session) }
        rejected {
            RuntimeTools.validate(
                response(
                    "quiz",
                    question("check").apply { remove("type") }), session
            )
        }
        rejected {
            RuntimeTools.validate(
                response(
                    "quiz",
                    question("predict").put("correctAnswer", "")
                ), session
            )
        }
        rejected { RuntimeTools.validate(response("quiz", question("ask")), session) }
        RuntimeTools.validate(
            response(
                "ask_user_question",
                JSONObject().put("question", "Which topic?").put("details", "")
                    .put("multiSelect", false).put("options", JSONArray())
            ), session
        )
        RuntimeTools.validate(
            response(
                "explain",
                question("check").apply { remove("options"); remove("multiSelect"); remove("explanation") }
                    .put("correctAnswer", "Left, because subtraction reverses addition.")),
            session.copy(mechanic = "recall"))
        val recall = RuntimeQuestion(
            "recall",
            "Explain",
            emptyList(),
            "Reference with reasoning",
            phase = "teacher",
            type = "check"
        )
        val answer = recall.answer("My written reasoning", -1, 60, "Reading notes", false)
        assertFalse(answer.has("confidence_self_report")); assertFalse(answer.has("correct"))
        assertEquals("", answer.getString("reasoning"))
        assertEquals("My written reasoning", answer.getString("answer"))
        assertEquals("Reading notes", answer.getString("thinkingWhileReading"))
        val gap = recall.answer("", -1, 100, "", true)
        assertFalse(answerText(gap).contains("Confidence"))
        assertTrue(answerText(gap).contains("don’t know"))
        val legacy = RuntimeQuestion.parse(recall.json().apply { remove("type") })
        assertEquals("", legacy.type); assertFalse(legacy.isCheck)
        assertFalse(legacy.answer("Legacy reply", -1, 60, "", false).has("thinkingWhileReading"))
        val assessment = ProbeAssessment(
            listOf(
                ProbeAssessment.Strand(
                    "Arithmetic",
                    "Floor reliable, ceiling uncertain; build from movement."
                )
            )
        )
        assertEquals(assessment, ProbeAssessment.parse(assessment.json()))
        rejected { ProbeAssessment.parse(assessment.json().put("extra", true)) }
        rejected { ProbeAssessment.parse(JSONObject().put("strands", JSONArray())) }
        val name = "legacy-learning-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                store.save(session)
                val checkpoint = JSONObject(session.checkpoint()).apply {
                    remove("planApproved"); remove("readingThinking"); remove("dontKnow"); remove("assessment")
                }
                store.writableDatabase.execSQL(
                    "UPDATE sessions SET checkpoint=? WHERE id=?",
                    arrayOf(checkpoint.toString(), session.id)
                )
                assertTrue(store.load(session.id)!!.planApproved)
                assertFalse(store.load(session.id)!!.dontKnow)
                assertEquals("", store.load(session.id)!!.readingThinking)
                checkpoint.put("status", "plan").put("planPending", true)
                store.writableDatabase.execSQL(
                    "UPDATE sessions SET checkpoint=? WHERE id=?",
                    arrayOf(checkpoint.toString(), session.id)
                )
                assertFalse(store.load(session.id)!!.planApproved)
                assertTrue(store.load(session.id)!!.planPending)
            }
        } finally {
            app.deleteDatabase(name)
        }
    }
}
