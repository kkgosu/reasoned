package com.kkgosu.learnharness

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.learnharness.api.ModelProvider
import com.kkgosu.learnharness.api.ProviderFailure
import com.kkgosu.learnharness.api.userMessage
import com.kkgosu.learnharness.controllers.AgentInstructions
import com.kkgosu.learnharness.controllers.KnowledgeExtraction
import com.kkgosu.learnharness.controllers.RuntimeTools
import com.kkgosu.learnharness.controllers.SessionRuntime
import com.kkgosu.learnharness.model.Findings
import com.kkgosu.learnharness.model.LessonDisplayItem
import com.kkgosu.learnharness.model.Passage
import com.kkgosu.learnharness.model.RuntimeSession
import com.kkgosu.learnharness.model.StudyPlan
import com.kkgosu.learnharness.model.passages
import com.kkgosu.learnharness.storage.SessionStore
import com.kkgosu.learnharness.storage.extractionUsage
import com.kkgosu.learnharness.storage.knowledge
import com.kkgosu.learnharness.storage.mergeKnowledge
import com.kkgosu.learnharness.storage.uncovered
import com.kkgosu.learnharness.utils.objects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KnowledgeTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private fun session(text: String = "A holds. A implies B, therefore B.") = RuntimeSession(goal = "Fixture goal", model = "gpt-6.1-sol", connection = "oaiapp_fixture",
        items = listOf(LessonDisplayItem.TextDisplayItem(1, "Fixture goal", LessonDisplayItem.Role.TITLE),
            LessonDisplayItem.TextDisplayItem(2, text), LessonDisplayItem.TextDisplayItem(3, "LEARNER_SECRET", LessonDisplayItem.Role.LEARNER)),
        protocol = JSONArray().put(userMessage("LEARNER_SECRET")).put(JSONObject().put("type", "message").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))).toString())
    private fun proposition(id: String, statement: String) = JSONObject().put("id", id).put("name", statement).put("statement", statement)
        .put("aliases", JSONArray()).put("messageId", "2")
    private fun findings() = JSONObject().put("propositions", JSONArray().put(proposition("p1", "A holds.")).put(proposition("p2", "B holds.")))
        .put("chains", JSONArray().put(JSONObject().put("premises", JSONArray().put("p1")).put("conclusion", "p2").put("messageId", "2")))
    private fun rejected(block: () -> Unit) { try { block(); fail("Invalid data accepted") } catch (_: ProviderFailure) { } }
    private fun response(name: String, args: JSONObject) = JSONObject().put("status", "completed")
        .put("output", JSONArray().put(JSONObject().put("type", "function_call").put("status", "completed")
            .put("name", name).put("call_id", UUID.randomUUID().toString()).put("arguments", args.toString())))
        .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 3))

    @Test fun dependencyPlansRejectInvalidReferencesAndCyclesAndNeverBecomeKnowledge() {
        val nodes = listOf(StudyPlan.Node("a", "Foundation", "First"), StudyPlan.Node("b", "Consequence", "Next"))
        val plan = StudyPlan(nodes, listOf("a" to "b"))
        assertEquals(plan, StudyPlan.parse(plan.json()))
        val split = session("A holds.B holds.").copy(proseIds = setOf(2, 3).map(Int::toLong).toSet(), protocol = JSONArray().apply {
            for (text in listOf("A holds.", "B holds.")) put(JSONObject().put("type", "message").put("role", "assistant")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text))))
        }.toString())
        assertEquals(listOf(Passage(2, "A holds.B holds.")), split.passages()) // One rendered row, several final SSE items; never learner row 3.
        rejected { StudyPlan.parse(plan.copy(nodes = nodes + nodes[0]).json()) }
        rejected { StudyPlan.parse(plan.copy(edges = listOf("missing" to "b")).json()) }
        rejected { StudyPlan.parse(plan.copy(edges = listOf("a" to "a")).json()) }
        rejected { StudyPlan.parse(plan.copy(edges = listOf("a" to "b", "b" to "a")).json()) }
        rejected { StudyPlan.parse(plan.copy(edges = listOf("a" to "b", "a" to "b")).json()) }
        val history = JSONArray()
        for (id in listOf("a", "b", "a")) history.put(JSONObject().put("type", "function_call").put("name", "ask_question")
            .put("arguments", JSONObject().put("nodeId", id).toString()))
        assertEquals("a" to setOf("a", "b"), plan.positions(history.toString()))
        val name = "plan-${UUID.randomUUID()}.db"
        SessionStore(app, name).use { store ->
            val saved = session().copy(plan = plan)
            store.save(saved)
            assertEquals(plan, store.load(saved.id)!!.plan)
            assertTrue(store.knowledge().nodes.isEmpty())
        }
        app.deleteDatabase(name)
    }

    @Test
    fun legacyRouteMigratesLocallyAndTeacherCanRetryWithoutApprovingTheOldRoute() = runBlocking {
        val name = "route-edit-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val oldCall = "old-route"
                val saved = session().copy(
                    status = "route", answers = 1, route = "Old route", routeCall = oldCall,
                    protocol = JSONArray().put(
                        JSONObject().put("type", "function_call").put("name", "finish_probe")
                            .put("call_id", oldCall).put(
                                "arguments", JSONObject().put("summary", "Old route")
                                    .put("steps", JSONArray().put("Old step")).toString()
                            )
                    ).toString()
                )
                store.save(saved)
                var calls = 0
                val provider = ModelProvider { request, _ ->
                    calls++
                    val input = request.getJSONArray("input").objects()
                    assertEquals(
                        1,
                        input.count {
                            it.optString("type") == "function_call_output" && it.optString("call_id") == oldCall
                        })
                    assertTrue(input.any {
                        it.optString("role") == "user" && it.toString()
                            .contains("Start with the basics")
                    })
                    assertEquals(
                        AgentInstructions.compose(app.assets, "teacher"),
                        request.getString("instructions")
                    )
                    when (calls) {
                        1 -> throw ProviderFailure("fixture_offline")
                        else -> response(
                            "plan_init",
                            StudyPlan(
                                listOf(StudyPlan.Node("basics", "Basics", "Start here")),
                                emptyList()
                            )
                                .json().apply { remove("version") })
                    }
                }
                val runtime = SessionRuntime(app, store, provider)
                runtime.open(saved.id).join()
                assertEquals(0, calls)
                assertEquals("teacher", runtime.state.value!!.phase)
                assertEquals("idle", runtime.state.value!!.status)
                runtime.edit("Start with the basics", -1, 60, false)
                runtime.approveRoute().join()
                assertEquals(0, calls)
                runtime.send().join()
                val interrupted = store.load(saved.id)!!
                assertEquals("error", interrupted.status)
                assertEquals("teacher", interrupted.phase)
                assertEquals("", interrupted.routeCall)
                assertEquals("Old route", interrupted.route)
                assertEquals(
                    1,
                    interrupted.items.count { it.role == LessonDisplayItem.Role.LEARNER && it.text == "Start with the basics" })
                runtime.stop().join()
                val reopened = SessionRuntime(app, store, provider)
                reopened.open(saved.id).join()
                assertEquals(1, calls)
                reopened.retry().join()
                assertEquals(2, calls)
                assertEquals("teacher", reopened.state.value!!.phase)
                assertEquals("plan", reopened.state.value!!.status)
                reopened.stop().join()
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test
    fun planChangesStayUnapprovedAcrossRepliesInterruptionAndReopening() = runBlocking {
        val name = "plan-edit-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val original =
                    StudyPlan(listOf(StudyPlan.Node("a", "Foundation", "First")), emptyList())
                val revised = original.copy(
                    nodes = original.nodes + StudyPlan.Node(
                        "b",
                        "Practice",
                        "Apply it"
                    ), edges = listOf("a" to "b")
                )
                val saved = session().copy(
                    phase = "teacher",
                    status = "plan",
                    plan = original,
                    planPending = true,
                    proseIds = emptySet()
                )
                store.save(saved)
                var calls = 0
                val provider = ModelProvider { request, _ ->
                    calls++
                    if (calls < 3) assertTrue(
                        request.getJSONArray("input").toString().contains("Add practice")
                    )
                    if (calls == 1) response(
                        "plan_init",
                        revised.json().apply { remove("version") })
                    else JSONObject().put("status", "completed").put(
                        "output", JSONArray().put(
                            JSONObject()
                                .put("type", "message").put("role", "assistant").put(
                                    "content", JSONArray().put(
                                        JSONObject()
                                            .put("type", "output_text").put(
                                                "text",
                                                if (calls == 2) "Plan clarification, not teaching." else "Teaching starts now."
                                            )
                                    )
                                )
                        )
                    )
                }
                val runtime = SessionRuntime(app, store, provider)
                runtime.open(saved.id).join()
                runtime.edit("Add practice", -1, 60, false)
                runtime.approveRoute().join()
                assertEquals(0, calls) // Unsent changes must not be silently approved/discarded.
                runtime.send().join()
                assertEquals(1, calls); assertEquals("plan", runtime.state.value!!.status)
                assertEquals(2, runtime.state.value!!.plan!!.version)
                assertTrue(store.load(saved.id)!!.planPending)
                assertFalse(store.load(saved.id)!!.planApproved)
                val planRow =
                    store.load(saved.id)!!.items.single { it.role == LessonDisplayItem.Role.PLAN }
                assertEquals(runtime.state.value!!.plan!!.diagramMarkdown(), planRow.text)
                assertTrue(planRow.text.contains("n0 --> n1"))
                runtime.edit("Explain that prerequisite", -1, 60, false)
                runtime.send().join()
                val pending = runtime.state.value!!
                assertEquals("plan", pending.status); assertTrue(pending.planPending)
                assertTrue(
                    pending.passages().isEmpty()
                ) // Plan discussion is not teaching evidence.
                rejected {
                    RuntimeTools.validate(
                        response(
                            "visual",
                            JSONObject().put("brief", "Figure")
                        ), pending
                    )
                }
                runtime.stop().join()
                store.save(pending.copy(status = "interrupted"))
                val reopened = SessionRuntime(app, store, provider)
                reopened.open(saved.id).join(); reopened.retry().join()
                assertEquals(2, calls); assertEquals(
                "plan",
                reopened.state.value!!.status
            ) // No generation replay.
                reopened.approveRoute().join()
                assertEquals(3, calls); assertEquals("idle", reopened.state.value!!.status)
                assertFalse(store.load(saved.id)!!.planPending)
                assertTrue(store.load(saved.id)!!.planApproved)
                reopened.stop().join()
            }
        } finally {
            app.deleteDatabase(name)
        }
    }

    @Test fun mergeIsAtomicReplaySafeAndKeepsMessageEvidenceAndFirstIdentity() {
        val name = "knowledge-${UUID.randomUUID()}.db"
        try { SessionStore(app, name).use { store ->
            val first = session(); store.save(first)
            val batch = store.uncovered(first)
            assertEquals(listOf(2L), batch.map { it.id })
            val parsed = Findings.parse(findings(), batch, emptySet())
            rejected { Findings.parse(findings().apply { getJSONArray("propositions").getJSONObject(0).put("messageId", "3") }, batch, emptySet()) }
            val redundant = parsed.copy(propositions = parsed.propositions + parsed.propositions.first().copy(id = "p3"),
                chains = parsed.chains + parsed.chains.first().copy(premises = listOf("p3")))
            assertEquals(2 to 1, store.mergeKnowledge(first.id, batch, redundant, emptyMap(), emptySet()))
            assertEquals(0 to 0, store.mergeKnowledge(first.id, batch, parsed, emptyMap(), emptySet()))
            assertTrue(store.uncovered(first).isEmpty())
            val graph = store.knowledge()
            assertEquals(
                2,
                graph.data().nodes.size
            ) // A single-premise chain is a direct edge, not a selectable junction.
            val chain = graph.chains.single()
            assertEquals(listOf(chain.premises.single() to chain.conclusion), graph.data().edges)
            assertEquals(2L, graph.sources.getValue(graph.nodes.first().id).single().message)
            assertEquals(2L, graph.sources.getValue(graph.chains.single().id).single().message)
            store.save(first.copy(draft = "A later edit must not delete source rows"))
            assertEquals(graph, store.knowledge())
            val second = session("Another account of A and B."); store.save(second)
            val nextBatch = store.uncovered(second)
            val a = graph.nodes.first { it.statement == "A holds." }
            val b = graph.nodes.first { it.statement == "B holds." }
            rejected { store.mergeKnowledge(second.id, nextBatch, parsed, mapOf("p1" to a.id, "p2" to a.id), setOf(a.id)) }
            assertEquals(graph, store.knowledge()) // Self-loop after identity collapse rolled aliases/provenance back too.
            assertEquals(1, store.uncovered(second).size)
            rejected { store.mergeKnowledge(second.id, nextBatch, parsed, mapOf("p1" to a.id), emptySet()) }
            assertEquals(0 to 0, store.mergeKnowledge(second.id, nextBatch, parsed, mapOf("p1" to a.id, "p2" to b.id), setOf(a.id, b.id)))
            assertEquals(a.name, store.knowledge().nodes.first { it.id == a.id }.name)
            assertEquals(2, store.knowledge().sources.getValue(a.id).size)
            store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            SessionStore(app, name).use { reopened -> assertEquals(store.knowledge(), reopened.knowledge()) }
        } } finally { app.deleteDatabase(name) }
    }

    @Test fun migrationPreservesVersionOneSessions() {
        val name = "migration-${UUID.randomUUID()}.db"
        try {
            val saved = session()
            SessionStore(app, name).use { it.save(saved) }
            SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                for (table in listOf("knowledge_sources", "knowledge_coverage", "knowledge_runs", "knowledge_chains", "knowledge_nodes")) db.execSQL("DROP TABLE $table")
                db.execSQL("CREATE TABLE sessions_v1(id TEXT PRIMARY KEY, goal TEXT NOT NULL, model TEXT NOT NULL, checkpoint TEXT NOT NULL, updated INTEGER NOT NULL)")
                db.execSQL("INSERT INTO sessions_v1 SELECT id,goal,model,checkpoint,updated FROM sessions")
                db.execSQL("DROP TABLE sessions")
                db.execSQL("ALTER TABLE sessions_v1 RENAME TO sessions")
                db.version = 1
            }
            SessionStore(app, name).use { migrated ->
                assertEquals(saved, migrated.load(saved.id))
                assertEquals(3, migrated.readableDatabase.version)
                assertTrue(migrated.knowledge().nodes.isEmpty())
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun hybridPipelineUsesSavedBillingAndOnlyNewAssistantProse() = runBlocking {
        val name = "pipeline-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        var calls = 0
        val connections = mutableListOf<String>()
        val pipeline = KnowledgeExtraction(app, store) { connection ->
            connections += connection
            ModelProvider { request, _ ->
                calls++
                val extractor = request.getString("instructions").startsWith("You are the extraction stage")
                val prompt = request.getJSONArray("input").getJSONObject(0).getString("content")
                assertFalse(prompt.contains("LEARNER_SECRET"))
                assertFalse(request.has("max_output_tokens")) // ChatGPT billing does not silently use API payload/billing.
                if (extractor) {
                    assertTrue(prompt.contains("SOURCE_SENTINEL"))
                    response("submit_findings", findings())
                } else {
                    assertFalse(prompt.contains("SOURCE_SENTINEL"))
                    if (request.getJSONArray("input").length() == 1) response("list_sessions", JSONObject())
                    else response("submit_review", JSONObject().put("links", JSONArray()))
                }
            }
        }
        try {
            val source = session("SOURCE_SENTINEL A holds. A implies B, therefore B.\n".repeat(400)); store.save(source)
            withContext(Dispatchers.Main) { pipeline.foreground = true }
            val job = withContext(Dispatchers.Main) { pipeline.start(source, automatic = true) }
            withTimeout(8000) { job.join() }
            assertEquals("complete", pipeline.state.value.phase)
            assertEquals(3, calls)
            assertTrue(connections.all { it == source.connection })
            assertTrue(store.uncovered(source).isEmpty())
            assertEquals(30L to 9L, store.extractionUsage(source.id))
            val replay = withContext(Dispatchers.Main) { pipeline.start(source) }
            withTimeout(8000) { replay.join() }
            assertEquals(3, calls)
            val paused = session("SOURCE_SENTINEL A holds."); store.save(paused)
            withContext(Dispatchers.Main) { pipeline.foreground = false }
            withContext(Dispatchers.Main) { pipeline.start(paused) }.join()
            assertEquals(3, calls)
            assertEquals(1, store.uncovered(paused).size)
            val entered = CompletableDeferred<Unit>()
            val interrupted = KnowledgeExtraction(app, store) { ModelProvider { _, _ -> entered.complete(Unit); awaitCancellation() } }
            val running = withContext(Dispatchers.Main) { interrupted.foreground = true; interrupted.start(paused) }
            withTimeout(8000) { entered.await() }
            withContext(Dispatchers.Main) { interrupted.foreground = false }
            withTimeout(8000) { running.join() }
            assertEquals("interrupted", interrupted.state.value.phase)
            assertEquals(1, store.uncovered(paused).size) // A cancelled batch cannot acquire coverage.
        } finally {
            withContext(Dispatchers.Main) { pipeline.stop() }?.join()
            store.close(); app.deleteDatabase(name)
        }
    }
}
