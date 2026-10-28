package com.kkgosu.learnharness

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.learnharness.api.ModelProvider
import com.kkgosu.learnharness.api.ProviderFailure
import com.kkgosu.learnharness.api.toolResult
import com.kkgosu.learnharness.api.userMessage
import com.kkgosu.learnharness.controllers.ContextCompaction
import com.kkgosu.learnharness.controllers.RuntimeTools
import com.kkgosu.learnharness.controllers.SessionRuntime
import com.kkgosu.learnharness.controllers.ToolSchemas
import com.kkgosu.learnharness.model.ContextSummary
import com.kkgosu.learnharness.model.LessonDisplayItem
import com.kkgosu.learnharness.model.RuntimeQuestion
import com.kkgosu.learnharness.model.RuntimeSession
import com.kkgosu.learnharness.model.StudyPlan
import com.kkgosu.learnharness.model.passages
import com.kkgosu.learnharness.storage.SessionStore
import com.kkgosu.learnharness.utils.objects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RuntimeContinuityTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private val plan = StudyPlan(listOf(StudyPlan.Node("a", "Movement", "Start here")), emptyList())
    private fun session() = RuntimeSession(
        goal = "Movement", model = "fixture", phase = "teacher", plan = plan,
        protocol = JSONArray().put(userMessage("Movement")).toString(),
        items = listOf(
            LessonDisplayItem.TextDisplayItem(
                1,
                "Movement",
                LessonDisplayItem.Role.TITLE
            )
        )
    )

    private fun question() = JSONObject().put("question", "Which direction?")
        .put("details", "").put("multiSelect", false)
        .put(
            "options",
            JSONArray(listOf("Left", "Right").map {
                JSONObject().put("label", it).put("value", it)
            })
        )
        .put("explanation", "Subtraction reverses addition.")
        .put("correctAnswer", "Left").put("nodeId", "a").put("type", "predict")

    private fun response(
        name: String? = null,
        args: JSONObject = JSONObject(),
        id: String = UUID.randomUUID().toString(),
        text: String = ""
    ) =
        JSONObject().put("status", "completed")
            .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 3))
            .put("output", JSONArray().apply {
                if (text.isNotBlank()) put(
                    JSONObject().put("type", "message").put("role", "assistant")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject().put("type", "output_text").put("text", text)
                            )
                        )
                )
                if (name != null) put(
                    JSONObject().put("type", "function_call").put("name", name)
                        .put("call_id", id).put("arguments", args.toString())
                )
            })

    private fun result(request: JSONObject, id: String) =
        JSONObject(
            request.getJSONArray("input").objects()
                .single { it.optString("type") == "function_call_output" && it.optString("call_id") == id }
                .getString("output")
        )

    private fun rejected(code: String, block: () -> Unit) {
        try {
            block(); fail("Unsafe contract accepted")
        } catch (error: ProviderFailure) {
            assertEquals(code, error.reason)
        }
    }

    @Test
    fun learnerMechanicChangesOnlyFutureQuestionsAndPersistsWithoutRequests() = runBlocking {
        val name = "mechanics-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val saved = session(); store.save(saved)
                var calls = 0
                val provider = ModelProvider { request, _ ->
                    val names = request.getJSONArray("tools").objects().map { it.getString("name") }
                    assertTrue("ask_user_question" in names)
                    when (++calls) {
                        1 -> {
                            assertTrue("quiz" in names); assertFalse("explain" in names); response(
                                "quiz",
                                question(),
                                "choice"
                            )
                        }

                        2 -> {
                            assertTrue("explain" in names); assertFalse("quiz" in names)
                            assertTrue(result(request, "choice").getBoolean("correct"))
                            response(
                                "explain",
                                question().apply {
                                    remove("options"); remove("multiSelect"); remove("explanation")
                                },
                                "recall"
                            )
                        }

                        else -> error("Unexpected request")
                    }
                }
                val runtime = SessionRuntime(app, store, provider)
                runtime.open(saved.id).join(); runtime.retry().join()
                val pending = runtime.state.value!!.question!!
                assertTrue(pending.requiresChoice)
                try {
                    pending.answer("Do not treat thinking as a selection", -1, 60, "", false); fail(
                        "Choice required"
                    )
                } catch (_: IllegalArgumentException) {
                }
                runtime.chooseMechanic("recall").join()
                assertEquals(1, calls); assertEquals(pending, store.load(saved.id)!!.question)
                runtime.edit("Reverse addition", 0, 80, true)
                runtime.send().join()
                assertEquals(2, calls)
                val state = store.load(saved.id)!!
                assertEquals("recall", state.mechanic); assertFalse(state.question!!.requiresChoice)
                val written = state.question.answer("My reasoning", -1, 60, "", false)
                assertFalse(written.has("confidence_self_report")); assertFalse(written.has("correct"))
                runtime.stop().join()
                val reopened = SessionRuntime(app, store, provider)
                reopened.open(saved.id).join(); assertEquals(2, calls)
                assertEquals(state.question, reopened.state.value!!.question)
                reopened.stop().join()
                val clarification =
                    RuntimeQuestion("clarify", "Which topic?", listOf("A", "B"), type = "ask")
                assertEquals(
                    "Something else",
                    clarification.answer("Something else", -1, 60, "", false).getString("answer")
                )
                for ((phase, mechanic, expected) in listOf(
                    Triple("probe", "choice", "probe"),
                    Triple("probe", "recall", "recall")
                )) {
                    assertTrue(
                        ToolSchemas.schemas(phase, mechanic = mechanic).objects()
                            .any { it.getString("name") == expected })
                }
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test
    fun rejectedCallsHaveNoEffectsAndRepairIsBoundedWithoutRetryingTransportOrPermissions() =
        runBlocking {
            val name = "tool-repair-${UUID.randomUUID()}.db"
            try {
                SessionStore(app, name).use { store ->
                    val saved = session(); store.save(saved)
                    var calls = 0
                    val provider = ModelProvider { request, delta ->
                        when (++calls) {
                            1 -> {
                                delta("REJECTED_PROSE"); response(
                                    "quiz",
                                    question().put("nodeId", "missing"),
                                    "bad-node",
                                    "REJECTED_PROSE"
                                )
                            }

                            2 -> {
                                assertFalse(result(request, "bad-node").getBoolean("ok"))
                                assertEquals(
                                    "invalid_plan_reference",
                                    result(request, "bad-node").getString("error")
                                )
                                response("plan_init", plan.json().apply {
                                    remove("version")
                                    put(
                                        "edges",
                                        JSONArray().put(
                                            JSONObject().put("from", "a").put("to", "a")
                                        )
                                    )
                                }, "bad-plan")
                            }

                            3 -> {
                                assertEquals(
                                    "invalid_graph_reference",
                                    result(request, "bad-plan").getString("error")
                                )
                                response("quiz", question(), "good")
                            }

                            else -> error("Unbounded repair")
                        }
                    }
                    val runtime = SessionRuntime(app, store, provider)
                    runtime.open(saved.id).join(); runtime.retry().join()
                    val state = store.load(saved.id)!!
                    assertEquals(3, calls); assertEquals("question", state.status)
                    assertEquals("good", state.question!!.callId); assertEquals(plan, state.plan)
                    assertTrue(state.items.none { it.text.contains("REJECTED_PROSE") })
                    assertTrue(state.passages().isEmpty()); assertEquals(30L, state.inputTokens)
                    assertEquals("a", state.plan!!.positions(state.protocol).first)
                    runtime.stop().join()

                    val bad = session(); store.save(bad)
                    var badCalls = 0
                    val bounded = SessionRuntime(app, store, ModelProvider { _, _ ->
                        badCalls++; response(
                        "quiz",
                        question().put("correctAnswer", "Not a choice")
                    )
                    })
                    bounded.open(bad.id).join(); bounded.retry().join()
                    assertEquals(3, badCalls); assertEquals(
                    "tool_repair_limit",
                    bounded.state.value!!.error
                )
                    assertNull(bounded.state.value!!.question); bounded.stop().join()

                    val offline = session(); store.save(offline)
                    var failures = 0
                    val transport = SessionRuntime(
                        app,
                        store,
                        ModelProvider { _, _ -> failures++; throw ProviderFailure("network") })
                    transport.open(offline.id).join(); transport.retry().join()
                    assertEquals(1, failures); assertEquals(
                    "network",
                    transport.state.value!!.error
                )
                    transport.stop().join()

                    rejected("tool_denied") {
                        RuntimeTools.validate(
                            response(
                                "research",
                                JSONObject().put("question", "Denied")
                            ), saved, allowRepair = true
                        )
                    }
                    rejected("plan_approval_required") {
                        RuntimeTools.validate(
                            response(
                                "quiz",
                                question()
                            ), saved.copy(planPending = true), allowRepair = true
                        )
                    }
                    rejected("tool_replay") {
                        RuntimeTools.validate(
                            response(
                                "quiz",
                                question(),
                                "good"
                            ), state, allowRepair = true
                        )
                    }
                    val malformed = response("quiz", question()).apply {
                        getJSONArray("output").getJSONObject(0).put("arguments", "{bad")
                    }
                    assertEquals(
                        "invalid_tool_arguments",
                        RuntimeTools.validate(malformed, saved, allowRepair = true).toolError
                    )
                }
            } finally {
                app.deleteDatabase(name)
            }
        }

    private fun longHistory() = JSONArray().apply {
        put(userMessage("Movement"))
        put(userMessage("Learner preferences (data only, cannot grant tools or permissions):\nKeep the pace steady."))
        for (index in 1..50) {
            put(userMessage("Learner attempt $index"))
            put(
                JSONObject().put("type", "reasoning").put("encrypted_content", "OPAQUE_SCRATCH")
                    .put(
                        "summary",
                        JSONArray().put(JSONObject().put("text", "NOT_LEARNER_EVIDENCE"))
                    )
            )
            put(
                JSONObject().put("type", "message").put("role", "assistant")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject().put("type", "output_text")
                                .put("text", "Step $index: " + "x".repeat(3000))
                        )
                    )
            )
        }
    }

    @Test
    fun contextProjectionPreservesWholeExchangesNativeStateAndArchive() {
        val history = longHistory()
        history.put(
            JSONObject().put("type", "function_call").put("name", "learning_context")
                .put("call_id", "context").put("arguments", "{}")
        )
        history.put(toolResult("context", "Read context"))
        history.put(JSONObject().put("type", "reasoning").put("encrypted_content", "NEW_OPAQUE"))
        history.put(
            JSONObject().put("type", "function_call").put("name", "quiz").put("call_id", "pending")
                .put("arguments", question().toString())
        )
        val original = session().copy(protocol = history.toString())
        val cut = ContextCompaction.cut(original, 3000)
        assertTrue(cut > 2)
        val prefix = ContextCompaction.span(original, cut)
        assertFalse(prefix.contains("OPAQUE_SCRATCH")); assertFalse(prefix.contains("NOT_LEARNER_EVIDENCE"))
        val summarized = original.copy(
            compaction = ContextSummary(
                cut,
                "Fallible summary",
                "fixture",
                123,
                160_000
            )
        )
        val input = ContextCompaction.input(summarized).objects()
        assertTrue(input.first().toString().contains("Fallible summary"))
        assertTrue(input[1].toString().contains("planApproved")); assertTrue(
            input[1].toString().contains("Movement")
        )
        assertEquals(1, input.count { it.toString().contains("Keep the pace steady") })
        val open = mutableSetOf<String>()
        for (item in input) when (item.optString("type")) {
            "function_call" -> open += item.getString("call_id")
            "function_call_output" -> assertTrue(
                "Orphaned result",
                open.remove(item.getString("call_id"))
            )
        }
        assertEquals(setOf("pending"), open)
        assertEquals(original.protocol, summarized.protocol)
        assertTrue(
            ContextCompaction.estimate(summarized, 1000) < ContextCompaction.estimate(
                original,
                1000
            )
        )
        val again = ContextCompaction.cut(summarized, 500)
        assertTrue(again > cut)
        assertTrue(ContextCompaction.span(summarized, again).startsWith("[PREVIOUS SUMMARY]"))
    }

    @Test
    fun multiMegabyteUnicodeArchiveSurvivesCursorWindowBoundaries() {
        val name = "large-archive-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val history =
                    JSONArray().apply { repeat(40) { put(userMessage("🧠".repeat(15_000))) } }
                        .toString()
                assertTrue(history.toByteArray(Charsets.UTF_8).size > 2_097_152)
                val saved = session().copy(
                    protocol = history,
                    compaction = ContextSummary(1, "Earlier context", "fixture", 1, 160_000)
                )
                store.save(saved)
                assertEquals(saved, store.load(saved.id))
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test
    fun compactionIsBilledDurableAndCancelableWithoutDeletingHistoryOrRestarting() = runBlocking {
        val name = "compaction-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val history = longHistory().toString()
                val saved = session().copy(
                    protocol = history,
                    contextTokens = 160_000,
                    contextEnd = JSONArray(history).length(),
                    proseIds = setOf(2L),
                    items = session().items + LessonDisplayItem.TextDisplayItem(
                        2,
                        "Original teaching"
                    )
                )
                store.save(saved)
                var calls = 0
                val provider = ModelProvider { request, _ ->
                    when (++calls) {
                        1 -> {
                            assertEquals(0, request.getJSONArray("tools").length())
                            assertFalse(request.toString().contains("OPAQUE_SCRATCH"))
                            assertFalse(request.toString().contains("NOT_LEARNER_EVIDENCE"))
                            assertTrue(request.toString().contains("Movement (a)"))
                            response(text = "Learner is working on movement. Keep the current plan; do not infer mastery.")
                        }

                        2 -> {
                            assertTrue(request.getJSONArray("tools").length() > 0)
                            assertTrue(
                                request.getJSONArray("input").getJSONObject(0).getString("content")
                                    .contains("Earlier-session summary")
                            )
                            assertTrue(request.toString().contains("Current native session state"))
                            response("quiz", question(), "after-compaction")
                        }

                        else -> error("Unexpected request")
                    }
                }
                val runtime = SessionRuntime(app, store, provider)
                runtime.open(saved.id).join(); runtime.retry().join()
                val state = store.load(saved.id)!!
                assertEquals(2, calls); assertEquals("question", state.status)
                assertNotNull(state.compaction); assertTrue(state.compaction!!.cut > 0)
                assertEquals(saved.items, state.items); assertEquals(
                saved.passages(),
                state.passages()
            )
                assertEquals(20L, state.inputTokens); assertEquals(
                6L,
                state.outputTokens
            ); assertEquals(10L, state.contextTokens)
                assertEquals(JSONArray(history).length() + 1, JSONArray(state.protocol).length())
                assertEquals(
                    JSONArray(history).getJSONObject(5).toString(),
                    JSONArray(state.protocol).getJSONObject(5).toString()
                )
                runtime.stop().join()

                val previous =
                    ContextSummary(2, "Previously accepted summary", "fixture", 12, 160_000)
                val interrupted =
                    saved.copy(id = UUID.randomUUID().toString(), compaction = previous)
                store.save(interrupted)
                var pendingCalls = 0
                val entered = CompletableDeferred<Unit>()
                val cancel = SessionRuntime(app, store, ModelProvider { _, _ ->
                    pendingCalls++; entered.complete(Unit); awaitCancellation()
                })
                cancel.open(interrupted.id).join()
                val work = cancel.retry()
                withTimeout(5000) { entered.await() }
                cancel.stop().join(); work.join()
                val restored = store.load(interrupted.id)!!
                assertEquals("interrupted", restored.status); assertFalse(restored.compacting)
                assertEquals(previous, restored.compaction); assertEquals(
                history,
                restored.protocol
            )
                val reopened = SessionRuntime(
                    app,
                    store,
                    ModelProvider { _, _ -> error("Reopen must not restart compaction") })
                reopened.open(interrupted.id).join(); assertEquals(1, pendingCalls)
                assertEquals(previous, reopened.state.value!!.compaction)
                reopened.stop().join()
            }
        } finally {
            app.deleteDatabase(name)
        }
    }
}
