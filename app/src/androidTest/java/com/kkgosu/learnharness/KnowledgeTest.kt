package com.kkgosu.learnharness

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
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
            assertEquals(3, graph.data().nodes.size) // Two propositions plus one derived argument junction.
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
                db.version = 1
            }
            SessionStore(app, name).use { migrated ->
                assertEquals(saved, migrated.load(saved.id))
                assertEquals(2, migrated.readableDatabase.version)
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
