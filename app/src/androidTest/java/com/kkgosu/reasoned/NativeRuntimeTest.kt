package com.kkgosu.reasoned

import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import android.app.AlertDialog
import android.widget.EditText
import android.widget.TextView
import android.widget.Toolbar
import com.kkgosu.reasoned.fragments.RuntimeSessionFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.readResponseStream
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.controllers.RuntimeTools
import com.kkgosu.reasoned.model.LessonDisplayItem.Role
import com.kkgosu.reasoned.model.LessonDisplayItem.TextDisplayItem
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeTeaching
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.storage.SessionStore
import com.kkgosu.reasoned.utils.objects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Authored offline native lessons/fake providers. Private desktop-copy checks require explicit opt-in. */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private fun text(value: String) = JSONObject().put("type", "text").put("text", value)
    private fun pose(id: String, tool: String, args: JSONObject) = JSONObject().put("id", "message_$id")
        .put("role", "assistant").put("api", "openai-responses").put("provider", "openai").put("model", "fixture")
        .put("timestamp", 1).put("stopReason", "toolUse").put("content", JSONArray().put(
            JSONObject().put("type", "toolCall").put("id", id).put("name", tool).put("arguments", args)))
    private fun result(id: String, tool: String, details: JSONObject) = JSONObject().put("id", "result_$id")
        .put("role", "toolResult").put("timestamp", 1).put("toolCallId", id).put("toolName", tool)
        .put("isError", false).put("details", details).put("content", JSONArray().put(text("{\"ok\":true}")))
    private fun fixture(): RuntimeSession {
        val session = RuntimeSession(goal = "[Authored test] Even integers", model = "fixture",
            items = listOf(TextDisplayItem(1, "[Authored test] Even integers", Role.TITLE)))
        val tree = JSONObject("""{"version":1,"nodes":[{"id":"basics","title":"Even integers"}],"edges":[]}""")
        val question = JSONObject("""{"question":"[Authored test] Which is even?","type":"check","nodeId":"basics",
          "options":[{"label":"Two","value":"two"},{"label":"Three","value":"three"}],
          "correctAnswer":"two","explanation":"Original teacher-only explanation."}""")
        val messages = JSONArray().put(JSONObject().put("id", "goal").put("role", "user").put("timestamp", 1).put("content", session.goal))
            .put(pose("done", "probe_done", JSONObject("""{"assessment":{"strands":[{"strand":"Evenness","assessment":"Authored fixture only"}]}}""")))
            .put(result("done", "probe_done", JSONObject().put("ok", true)))
            .put(pose("plan", "plan_init", JSONObject().put("mermaid", "graph TD\nbasics[Even integers]")))
            .put(result("plan", "plan_init", JSONObject().put("ok", true).put("tree", tree)))
            .put(pose("call_pending|fc_pending", "quiz", question))
        return session.copy(nativeRecord = NativeChat.parse(JSONObject().put("id", session.id).put("version", 1)
            .put("title", "[Authored offline runtime acceptance] Even integers")
            .put("createdAt", "2026-01-01T00:00:00.000Z").put("updatedAt", "2026-01-01T00:00:00.000Z")
            .put("tree", tree).put("messages", messages).put("future", "PRESERVE_AUTHORED_OPAQUE_FIELD").toString()))
    }
    private fun response(question: Boolean = false): JSONObject {
        val output = JSONArray().put(JSONObject().put("type", "reasoning").put("id", "rs_fixture")
            .put("encrypted_content", "OPAQUE_FIXTURE_REASONING").put("summary", JSONArray().put(
                JSONObject().put("type", "summary_text").put("text", if (question) "New hidden commitment" else "Provider public fixture summary"))))
            .put(JSONObject().put("type", "message").put("id", "msg_fixture").put("role", "assistant")
                .put("status", "completed").put("content", JSONArray().put(
                    JSONObject().put("type", "output_text").put("text", "Authoritative fixture continuation"))))
        if (question) output.put(JSONObject().put("type", "function_call").put("id", "fc_next").put("call_id", "call_next")
            .put("name", "explain").put("namespace", RuntimeTools.NAMESPACE).put("arguments", JSONObject().put("question", "[Authored test] Explain evenness")
                .put("correctAnswer", "New hidden commitment").put("type", "check").put("nodeId", "basics").toString()))
        return JSONObject().put("status", "completed").put("output", output)
            .put("usage", JSONObject().put("input_tokens", 40).put("output_tokens", 10))
    }
    private fun answer(runtime: SessionRuntime) {
        val s = runtime.state.value!!
        val q = s.question!!
        runtime.edit("Learner-only reasoning", q.values.indexOf("two"), 90, true,
            readingThinking = "Learner reading thought", selectedValues = listOf("two"))
    }

    // Synthetic temporary DB snapshots only; the offline desktop checker consumes these unchanged.
    private fun exportFixture(session: RuntimeSession, name: String) {
        val directory = File(app.cacheDir, "native-acceptance").apply { mkdirs() }
        File(directory, "$name.json").writeText(requireNotNull(session.nativeRecord).raw)
    }

    private fun compactionFixture(): RuntimeSession {
        val base = fixture()
        val root = base.nativeRecord!!.snapshot()
        val original = root.getJSONArray("messages").objects()
        val messages = JSONArray().put(original.first())
        repeat(12) { index -> messages.put(JSONObject().put("id", "authored_history_$index")
            .put("role", "user").put("timestamp", 1)
            .put("content", "[Authored compaction history $index] " + "x".repeat(8000))) }
        original.drop(1).forEach { messages.put(it) }
        return base.copy(nativeRecord = NativeChat.parse(root.put("messages", messages).toString()))
    }

    /** Explicit private-copy check: local fake provider only, no source content in assertions/logs. */
    @Test fun explicitlySelectedPrivateCopyRestoresAndContinuesWithoutRewritingHistory() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeLocalRecord") == "read")
        val file = File(app.cacheDir, "native-private-check.json")
        val name = "native-private-${UUID.randomUUID()}.db"
        try {
            require(file.isFile && file.length() in 1..NativeChat.MAX_BYTES.toLong())
            val bytes = file.readText()
            val chat = NativeChat.parse(bytes)
            val original = chat.snapshot()
            val prefix = original.getJSONArray("messages").objects().map { it.toString() }
            SessionStore(app, name).use { store ->
                // Deliberately isolated test routing; never reuse the learner's billing connection.
                store.saveNative(RuntimeSession(id = chat.id, goal = "[Private offline compatibility check]",
                    model = "fixture", nativeRecord = chat))
                var requests = 0
                val fake = ModelProvider { request, _ ->
                    requests++
                    assertTrue(request.getJSONArray("input").length() > 0)
                    assertTrue(store.load(chat.id)!!.nativeRecord!!.snapshot().getJSONArray("messages")
                        .objects().take(prefix.size).map { it.toString() } == prefix)
                    if (requests == 1) response().apply {
                        getJSONArray("output").getJSONObject(0).put("summary", JSONArray().put(JSONObject()
                            .put("type", "summary_text").put("text", "AUTHORED_LOCAL_HIDDEN_ANSWER")))
                        getJSONArray("output").put(JSONObject().put("type", "function_call")
                            .put("call_id", "authored_private_${UUID.randomUUID()}").put("name", "quiz")
                            .put("arguments", JSONObject().put("question", "[Authored offline check] Which is even?")
                                .put("options", JSONArray().put(JSONObject().put("label", "Two").put("value", "two"))
                                    .put(JSONObject().put("label", "Three").put("value", "three")))
                                .put("correctAnswer", "two").put("explanation", "[Authored offline check] Two is even.").put("type", "check")
                                .put("nodeId", store.load(chat.id)!!.plan!!.nodes.first().id).toString()))
                    } else response()
                }
                val runtime = SessionRuntime(app, store, fake)
                try {
                    runtime.open(chat.id).join()
                    assertTrue(runtime.failure.value.isEmpty())
                    assertEquals(0, requests)
                    assertTrue(runtime.state.value!!.question == null) // This opt-in case requires a settled lesson.
                    assertTrue(runtime.state.value!!.nativeRecord!!.raw == bytes)
                    runtime.retry().join()
                    assertEquals(0, requests)
                    runtime.edit("[Authored offline compatibility action — not learner evidence]", -1, 80, true)
                    runtime.send().join()
                    assertEquals("", runtime.failure.value)
                    assertEquals(1, requests)
                    assertTrue(runtime.state.value!!.question != null)
                    assertTrue(runtime.state.value!!.items.none { it.text.contains("AUTHORED_LOCAL_HIDDEN_ANSWER") })
                    runtime.open(chat.id).join()
                    assertEquals(1, requests)
                    assertTrue(runtime.state.value!!.items.none { it.text.contains("AUTHORED_LOCAL_HIDDEN_ANSWER") })
                    runtime.edit("[Authored offline answer — not learner evidence]", -1, 90, true,
                        selectedValues = listOf("two"))
                    runtime.send().join()
                    assertTrue(runtime.failure.value.isEmpty())
                    assertEquals(2, requests)
                    val continued = store.load(chat.id)!!.nativeRecord!!
                    assertTrue(continued.snapshot().getJSONArray("messages").objects().take(prefix.size)
                        .map { it.toString() } == prefix)
                    original.keys().asSequence().filter { it !in setOf("messages", "turns", "updatedAt", "version") }
                        .forEach { key -> assertTrue(original.get(key).toString() == continued.snapshot().get(key).toString()) }
                    runtime.open(chat.id).join()
                    assertEquals(2, requests)
                    assertTrue(runtime.state.value!!.nativeRecord!!.raw == continued.raw)
                    assertTrue(file.readText() == bytes)
                } finally { runtime.stop().join() }
            }
        } catch (error: Exception) {
            // Parser exception messages can quote input; do not attach the private cause to JUnit output.
            throw AssertionError("Private local check failed (${error.javaClass.simpleName}); details withheld.")
        } finally {
            try { app.deleteDatabase(name) }
            finally { file.delete() } // Also clean up if parsing or database cleanup fails.
        }
    }

    /** Opt-in fixtures from the pinned real desktop runtime; never an app import action. */
    @Test fun desktopContinuationsRestoreAndContinueThroughAndroidWithoutRewritingHistory() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeDesktopRecords") == "read")
        val directory = File(app.cacheDir, "native-desktop-acceptance")
        for (case in listOf("teaching", "practice", "compaction", "failures", "recovery-conversation", "recovery-compaction")) {
            val file = File(directory, "$case.json")
            require(file.length() in 1..2_000_000) { "Missing or oversized authored desktop fixture: $case" }
            val bytes = file.readText()
            val chat = NativeChat.parse(bytes)
            val original = chat.snapshot()
            require(original.getString("title").startsWith("[Authored offline runtime acceptance]"))
            require(original.getJSONArray("messages").objects().any { it.optString("provider") == "faux" })
            val name = "native-desktop-${UUID.randomUUID()}.db"
            try {
                SessionStore(app, name).use { store ->
                    // Explicit test-local connection/model, not an importer silently routing an account.
                    // Local counters start at zero; historical native turns remain untouched, not re-billed.
                    val initial = RuntimeSession(id = chat.id, goal = original.getString("title"), model = "fixture",
                        nativeRecord = chat)
                    store.saveNative(initial)
                    assertEquals(bytes, store.load(chat.id)!!.nativeRecord!!.raw)
                    val ids = store.load(chat.id)!!.nativeDisplayIds
                    val questionCase = case in setOf("teaching", "recovery-conversation")
                    var calls = 0
                    val fake = ModelProvider { request, _ ->
                        calls++
                        val durable = store.load(chat.id)!!
                        val messages = durable.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                        if (questionCase) {
                            val pending = chat.pendingQuestion()!!
                            assertNull(durable.nativeRecord.pendingQuestion())
                            val result = messages.single { it.optString("role") == "toolResult" && it.optString("toolCallId") == pending.question.callId }
                            assertTrue(result.getJSONObject("details").getBoolean("correct"))
                            assertEquals("Authored Android return thinking", result.getJSONObject("details").getString("thinking"))
                            assertEquals("Authored Android return reading", result.getJSONObject("details").getString("readingThinking"))
                            assertTrue(request.getJSONArray("input").objects().any { it.optString("type") == "function_call_output" &&
                                it.optString("call_id") == pending.question.callId.substringBefore('|') })
                        } else if (case == "practice") {
                            assertTrue(messages.any { it.optString("customType") == "practice-thinking" &&
                                it.getJSONObject("details").optString("text") == "Authored Android return thinking" })
                        } else assertEquals("Authored Android return thinking", messages.last { it.optString("role") == "user" }.getString("content"))
                        if (case == "compaction") {
                            val marker = original.getJSONArray("messages").objects().last { it.optString("customType") == "compaction" }
                            assertEquals(marker.getString("content"), request.getJSONArray("input").getJSONObject(0)
                                .getJSONArray("content").getJSONObject(0).getString("text"))
                            assertFalse(request.getJSONArray("input").toString().contains("[Authored compaction history 0]"))
                        }
                        if (case == "practice" && calls == 1) response().put("output", JSONArray().put(JSONObject()
                            .put("type", "function_call").put("call_id", "android_return_conclude").put("name", "conclude")
                            .put("arguments", JSONObject().put("outcome", "solved").put("verdict", "Authored return judgment, not mastery")
                                .put("rung", JSONObject.NULL).put("missClass", JSONObject.NULL).put("retest", false).toString())))
                        else { assertEquals(if (case == "practice") 2 else 1, calls); response() }
                    }
                    val runtime = SessionRuntime(app, store, fake)
                    try {
                        runtime.open(chat.id).join()
                        assertEquals("$case: ${runtime.failure.value}", "", runtime.failure.value)
                        var restored = runtime.state.value!!
                        val previousAnswers = restored.answers
                        assertEquals(if (questionCase) "question" else "idle", restored.status)
                        assertEquals(0, calls)
                        assertEquals(bytes, restored.nativeRecord!!.raw)
                        assertFalse(restored.items.any { it.text.contains("AUTHORED_DESKTOP_HIDDEN_COMMITMENT") })
                        if (questionCase) {
                            assertEquals(chat.pendingQuestion()!!.question.callId, restored.question!!.callId)
                            assertFalse(restored.expanded)
                        } else if (case == "practice") {
                            assertEquals("desktop_problem", restored.activeProblem!!.id)
                            assertEquals("4k = 2(2k).", restored.activeProblem!!.solution)
                            assertFalse(restored.items.any { it.text.contains("4k = 2(2k).") })
                            assertTrue(restored.items.any { it.text.contains("AUTHORED_HIDDEN_PRACTICE_SOLUTION") })
                        }
                        runtime.retry().join() // Opening or retrying a settled record cannot pay again.
                        assertEquals(0, calls)
                        runtime.edit("Authored Android return thinking", if (questionCase) restored.question!!.values.indexOf("two") else -1,
                            90, true, readingThinking = if (questionCase) "Authored Android return reading" else "",
                            selectedValues = if (questionCase) listOf("two") else emptyList())
                        runtime.send().join()
                        restored = runtime.state.value!!
                        val count = if (case == "practice") 2 else 1
                        assertEquals("$case: ${restored.error}", count, calls)
                        assertEquals("$case: ${restored.error}", "idle", restored.status)
                        assertNull(restored.question)
                        assertNull(restored.activeProblem)
                        assertEquals(40L * count, restored.inputTokens)
                        assertEquals(10L * count, restored.outputTokens)
                        assertEquals(previousAnswers + if (questionCase) 1 else 0, restored.answers)
                        if (case == "practice") {
                            assertEquals("solved", restored.problems.last().outcome)
                            assertTrue(restored.items.any { it.text.contains("Committed solution\n\n4k = 2(2k).") })
                        }
                        val after = restored.nativeRecord!!.snapshot()
                        assertEquals(original.getJSONArray("messages").toString(), JSONArray(after.getJSONArray("messages").objects()
                            .take(original.getJSONArray("messages").length())).toString())
                        val turns = after.getJSONArray("turns").objects()
                        assertEquals(original.getJSONArray("turns").toString(), JSONArray(turns.dropLast(1)).toString())
                        assertEquals(count, turns.last().getJSONArray("steps").length())
                        assertEquals(original.get("future"), after.get("future"))
                        assertEquals(ids, restored.nativeDisplayIds.filterKeys { it in ids })
                        val saved = restored.nativeRecord.raw
                        val savedIds = restored.nativeDisplayIds
                        runtime.stop().join(); runtime.open(chat.id).join(); runtime.retry().join()
                        assertEquals(count, calls)
                        assertEquals(saved, runtime.state.value!!.nativeRecord!!.raw)
                        assertEquals(savedIds, runtime.state.value!!.nativeDisplayIds)
                        assertEquals(bytes, file.readText())
                    } finally { runtime.stop().join() }
                }
            } finally { app.deleteDatabase(name) }
        }
    }

    // Explicit two-process harness only: ordinary suites skip both methods, never kill their runner.
    @Test fun nativeAccountingProcessDeathWriter() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativeProcessDeath") == "prepare")
        val boundary = args.getString("boundary")!!
        require(boundary in setOf("conversation", "compaction"))
        val name = "native-process-death-$boundary.db"
        app.deleteDatabase(name) // This authored fixture only; never the application's sessions.db.
        SessionStore(app, name).use { store ->
            val initial = (if (boundary == "compaction") compactionFixture() else fixture()).copy(connection = "fixture-account")
            store.save(initial)
            var calls = 0
            val fake = ModelProvider { _, _ ->
                calls++
                if (calls == 1) {
                    if (boundary == "compaction") response(question = true).apply { getJSONObject("usage").put("input_tokens", 200000) }
                    else response().put("output", JSONArray().put(JSONObject().put("type", "function_call")
                        .put("call_id", "rewrite_before_death").put("name", "plan_init").put("namespace", RuntimeTools.NAMESPACE)
                        .put("arguments", JSONObject().put("mermaid", "graph TD\nbasics[Even integers]").toString())))
                } else {
                    val durable = store.load(initial.id)!!
                    assertTrue(durable.nativeTurn.isNotEmpty())
                    assertTrue(JSONObject(durable.nativeTurn).getBoolean("inFlight"))
                    if (boundary == "conversation") store.queueNativeSteer(initial.id,
                        JSONObject(durable.nativeTurn).getJSONObject("entry").getString("id"), "[Authored steer] Survives process death")
                    File(app.cacheDir, "native-process-death-$boundary.json").writeText(JSONObject()
                        .put("session", initial.id).put("run", JSONObject(durable.nativeTurn).getJSONObject("entry").getString("id")).toString())
                    android.os.Process.killProcess(android.os.Process.myPid()) // No coroutine finally/close.
                    error("Process kill returned")
                }
            }
            val runtime = SessionRuntime(app, store, fake)
            runtime.open(initial.id).join()
            runtime.chooseMechanic("recall").join()
            answer(runtime); runtime.send().join()
            fail("The authored second request never reached the process-death boundary: ${runtime.state.value?.error}")
        }
    }

    @Test fun nativeAccountingProcessDeathReader() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativeProcessDeath") == "recover")
        val boundary = args.getString("boundary")!!
        require(boundary in setOf("conversation", "compaction"))
        val name = "native-process-death-$boundary.db"
        val marker = File(app.cacheDir, "native-process-death-$boundary.json")
        val ids = JSONObject(marker.readText())
        try {
            SessionStore(app, name).use { store ->
                val before = store.load(ids.getString("session"))!!
                val pending = JSONObject(before.nativeTurn)
                assertEquals(ids.getString("run"), pending.getJSONObject("entry").getString("id"))
                assertTrue(pending.getBoolean("inFlight"))
                assertEquals(if (boundary == "compaction") 0 else 1, pending.getJSONObject("entry").getJSONArray("steps").length())
                store.recover()
                val restored = store.load(before.id)!!
                assertEquals("", restored.nativeTurn)
                assertEquals(if (boundary == "compaction") 0 else 1, JSONArray(restored.nativeSteers).length())
                assertEquals(if (boundary == "compaction") 200000L else 40L, restored.inputTokens)
                assertEquals(10L, restored.outputTokens)
                val root = restored.nativeRecord!!.snapshot()
                val entry = root.getJSONArray("turns").objects().single { it.getString("id") == ids.getString("run") }
                assertEquals("interrupted", entry.getString("reasonedStatus"))
                val steps = entry.getJSONArray("steps")
                assertEquals(if (boundary == "compaction") 1 else 2, steps.length())
                assertEquals(0, steps.getJSONObject(steps.length() - 1).length()) // Not a fabricated zero-token report.
                if (boundary == "compaction") {
                    assertEquals(before.nativeRecord!!.snapshot().getJSONArray("messages").toString(), root.getJSONArray("messages").toString())
                    assertEquals(before.nativeDisplayIds, restored.nativeDisplayIds)
                    assertEquals("call_next|fc_next", restored.nativeRecord.pendingQuestion()!!.question.callId)
                } else assertEquals("error", root.getJSONArray("messages").objects().last().getString("stopReason"))
                store.readableDatabase.rawQuery("SELECT status,input_tokens,output_tokens FROM runs WHERE id=?", arrayOf(ids.getString("run"))).use { row ->
                    assertTrue(row.moveToFirst()); assertEquals("interrupted", row.getString(0))
                    assertEquals(if (boundary == "compaction") 0L else 40L, row.getLong(1))
                    assertEquals(if (boundary == "compaction") 0L else 10L, row.getLong(2))
                }
                store.recover()
                assertEquals(restored.nativeRecord.raw, store.load(before.id)!!.nativeRecord!!.raw)
                var calls = 0
                val runtime = SessionRuntime(app, store, ModelProvider { request, _ ->
                    calls++
                    if (boundary == "conversation") assertTrue(request.getJSONArray("input").toString().contains("[Authored steer] Survives process death"))
                    response(question = true)
                })
                try {
                    runtime.open(before.id).join()
                    assertEquals(0, calls)
                    assertEquals(1, runtime.state.value!!.answers)
                    assertFalse(runtime.state.value!!.items.any { it.text.contains("New hidden commitment") })
                    runtime.retry().join()
                    assertEquals(if (boundary == "compaction") 0 else 1, calls)
                    assertEquals("[]", store.load(before.id)!!.nativeSteers)
                    if (boundary == "conversation") assertEquals(1, store.load(before.id)!!.nativeRecord!!.snapshot()
                        .getJSONArray("messages").objects().count { it.optString("content") == "[Authored steer] Survives process death" })
                    assertEquals(restored.inputTokens + if (boundary == "compaction") 0 else 40, runtime.state.value!!.inputTokens)
                    assertEquals(1, runtime.state.value!!.answers)
                    val directory = File(app.cacheDir, "native-acceptance").apply { mkdirs() }
                    File(directory, "recovery-$boundary.json").writeText(restored.nativeRecord.raw)
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name); marker.delete() }
    }

    @Test fun nativeCompactionKeepsPendingCommitmentsAndRetriesAnIncompleteSummaryAtTheNextInput() = runBlocking {
        val name = "native-compaction-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val initial = compactionFixture().copy(connection = "fixture-account")
                store.save(initial)
                val originalIds = store.load(initial.id)!!.nativeDisplayIds
                val requests = mutableListOf<JSONObject>()
                val connections = mutableListOf<String>()
                val fake = ModelProvider { request, _ ->
                    requests += JSONObject(request.toString())
                    when (requests.size) {
                        1 -> response(question = true).apply { getJSONObject("usage").put("input_tokens", 200000) }
                        2 -> {
                            val compacting = store.load(initial.id)!!
                            assertThrows(IllegalStateException::class.java) {
                                store.queueNativeSteer(initial.id, JSONObject(compacting.nativeTurn).getJSONObject("entry").getString("id"),
                                    "A summary request is not a live teacher")
                            }
                            assertFalse(request.has("tools")) // Account payload: genuinely tool-free.
                            assertFalse(request.optJSONObject("reasoning")?.has("summary") == true)
                            response().put("status", "incomplete")
                        }
                        3 -> {
                            assertFalse(request.has("tools"))
                            val committed = store.load(initial.id)!!.nativeRecord!!
                            assertNull(committed.pendingQuestion()) // Answer is durable before any summary request.
                            assertTrue(request.getJSONArray("input").toString().contains("Even integers (basics)"))
                            assertFalse(request.getJSONArray("input").toString().contains("OPAQUE_FIXTURE_REASONING"))
                            response()
                        }
                        4 -> {
                            val saved = store.load(initial.id)!!.nativeRecord!!.snapshot()
                            val marker = saved.getJSONArray("messages").objects().last()
                            assertEquals("compaction", marker.getString("customType"))
                            assertEquals("openai-codex:fixture", marker.getJSONObject("details").getString("model"))
                            assertEquals(marker.getString("content"), request.getJSONArray("input").getJSONObject(0)
                                .getJSONArray("content").getJSONObject(0).getString("text"))
                            response()
                        }
                        else -> error("Unexpected additional request")
                    }
                }
                val runtime = SessionRuntime(app, store, fake,
                    providerFor = { connections += it; fake })
                try {
                    runtime.open(initial.id).join()
                    runtime.chooseMechanic("recall").join()
                    answer(runtime); runtime.send().join()
                    assertEquals(runtime.state.value!!.error, "question", runtime.state.value!!.status)
                    assertEquals(2, requests.size)
                    val pending = runtime.state.value!!
                    assertEquals("question", pending.status); assertFalse(pending.compacting)
                    assertEquals("call_next|fc_next", pending.question!!.callId)
                    assertFalse(pending.items.any { it.text.contains("New hidden commitment") })
                    assertFalse(pending.nativeRecord!!.snapshot().getJSONArray("messages").objects().any { it.optString("customType") == "compaction" })
                    runtime.retry().join() // A failed settle summary cannot replace/duplicate the pending question.
                    assertEquals(2, requests.size)
                    runtime.edit("Authored recall answer", -1, 70, true)
                    runtime.send().join()
                    assertEquals(4, requests.size)
                    val saved = runtime.state.value!!
                    val root = saved.nativeRecord!!.snapshot()
                    assertEquals("PRESERVE_AUTHORED_OPAQUE_FIELD", root.getString("future"))
                    val turns = root.getJSONArray("turns").objects()
                    assertEquals(4, turns.size)
                    assertEquals(4, turns.map { it.getString("id") }.distinct().size)
                    assertEquals("compaction", turns[1].getString("reasonedPurpose"))
                    assertEquals("failed", turns[1].getString("reasonedStatus"))
                    assertEquals("incomplete", turns[1].getJSONObject("reasonedResponses").getString("status"))
                    assertEquals(200120L, turns.sumOf { it.getJSONArray("steps").objects().sumOf { step -> step.getLong("inputTokens") } })
                    exportFixture(saved, "compaction")
                    assertEquals(originalIds, saved.nativeDisplayIds.filterKeys { it in originalIds })
                    assertTrue(saved.items.any { it.text.startsWith("[Authored compaction history 0]") })
                    assertFalse(saved.items.any { it.text.startsWith("[SESSION HISTORY COMPACTED]") })
                    assertEquals(List(4) { "fixture-account" }, connections)
                    assertNull(saved.compaction) // No legacy index-based checkpoint.
                    assertEquals(200120L, saved.inputTokens) // Teaching + rejected summary + summary + teaching.
                    runtime.stop().join()
                    runtime.open(initial.id).join()
                    assertEquals(4, requests.size)
                    assertEquals(root.toString(), runtime.state.value!!.nativeRecord!!.snapshot().toString())
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun successfulNativeSettleSummaryRestoresTheSameHiddenQuestionWithoutAnotherRequest() = runBlocking {
        val name = "native-compaction-settle-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val initial = compactionFixture().copy(connection = "fixture-account"); store.save(initial)
                var calls = 0
                val fake = ModelProvider { request, _ ->
                    if (++calls == 1) response(question = true).apply { getJSONObject("usage").put("input_tokens", 200000) }
                    else {
                        assertFalse(request.has("tools"))
                        assertEquals("call_next|fc_next", store.load(initial.id)!!.nativeRecord!!.pendingQuestion()!!.question.callId)
                        response()
                    }
                }
                val runtime = SessionRuntime(app, store, fake)
                try {
                    runtime.open(initial.id).join(); runtime.chooseMechanic("recall").join(); answer(runtime)
                    runtime.send().join()
                    val saved = runtime.state.value!!
                    assertEquals(2, calls); assertEquals("question", saved.status)
                    assertFalse(saved.compacting); assertFalse(saved.expanded)
                    assertEquals("call_next|fc_next", saved.question!!.callId)
                    assertEquals("compaction", saved.nativeRecord!!.snapshot().getJSONArray("messages").objects().last().optString("customType"))
                    assertFalse(saved.items.any { it.text.contains("New hidden commitment") })
                    runtime.retry().join(); runtime.open(initial.id).join()
                    assertEquals(2, calls)
                    assertEquals(saved.nativeRecord!!.raw, runtime.state.value!!.nativeRecord!!.raw)
                    assertEquals(saved.question.callId, runtime.state.value!!.question!!.callId)
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun cancellingASettleSummaryKeepsTheNativeQuestionAndDoesNotAppendAFailedTeacher() = runBlocking {
        val name = "native-compaction-stop-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val initial = compactionFixture().copy(connection = "fixture-account"); store.save(initial)
                var calls = 0
                val entered = CompletableDeferred<Unit>()
                val fake = ModelProvider { _, _ ->
                    if (++calls == 1) response(question = true).apply { getJSONObject("usage").put("input_tokens", 200000) }
                    else { entered.complete(Unit); awaitCancellation() }
                }
                val runtime = SessionRuntime(app, store, fake)
                try {
                    runtime.open(initial.id).join(); runtime.chooseMechanic("recall").join(); answer(runtime)
                    runtime.send()
                    withTimeout(10000) { entered.await() }
                    assertTrue(runtime.state.value!!.compacting)
                    val raw = store.load(initial.id)!!.nativeRecord!!.raw
                    runtime.send() // The existing foreground command is the gate: no parallel paid turn.
                    runtime.stop().join()
                    val saved = store.load(initial.id)!!
                    assertEquals(JSONObject(raw).getJSONArray("messages").toString(), saved.nativeRecord!!.snapshot().getJSONArray("messages").toString())
                    val turns = saved.nativeRecord.snapshot().getJSONArray("turns").objects()
                    assertEquals(2, turns.size)
                    assertEquals("interrupted", turns.last().getString("reasonedStatus"))
                    assertEquals("{}", turns.last().getJSONArray("steps").getJSONObject(0).toString())
                    assertEquals("question", saved.status); assertFalse(runtime.state.value!!.compacting)
                    runtime.open(initial.id).join()
                    assertEquals("call_next|fc_next", runtime.state.value!!.question!!.callId)
                    assertFalse(runtime.state.value!!.expanded)
                    assertEquals(2, calls)
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun teachingAnswerCommitsBeforeRequestAndRestoresTheNextHiddenNativeQuestion() = runBlocking {
        val name = "native-runtime-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val initial = fixture().copy(connection = "fixture-account"); store.save(initial)
                val connections = mutableListOf<String>()
                val ids = store.load(initial.id)!!.nativeDisplayIds
                val requests = mutableListOf<JSONObject>()
                val commitments = mutableListOf<NativeChat>()
                var completed = 0
                val fake = ModelProvider { request, delta ->
                    requests += JSONObject(request.toString())
                    commitments += store.load(initial.id)!!.nativeRecord!!
                    delta("Provisional text must not replace the completed response")
                    response(question = requests.size == 1)
                }
                val runtime = SessionRuntime(app, store, fake, afterTurn = { completed++ },
                    providerFor = { connection -> connections += connection; fake })
                try {
                    runtime.open(initial.id).join()
                    assertTrue(runtime.failure.value.isEmpty()); assertTrue(requests.isEmpty())
                    assertFalse(runtime.state.value!!.expanded)
                    assertEquals(initial.nativeRecord!!.raw, store.load(initial.id)!!.nativeRecord!!.raw)
                    runtime.send().join() // No answer, no reveal: no commit or request.
                    assertTrue(requests.isEmpty())
                    runtime.chooseMechanic("recall").join() // Does not change the existing quiz commitment.
                    answer(runtime); runtime.send().join()
                    assertEquals(1, requests.size)
                    val committed = commitments.single().snapshot().getJSONArray("messages").objects().last()
                    assertEquals("toolResult", committed.getString("role"))
                    assertEquals("call_pending|fc_pending", committed.getString("toolCallId"))
                    assertEquals(90, committed.getJSONObject("details").getInt("confidence"))
                    assertTrue(committed.getJSONObject("details").getBoolean("correct"))
                    val wire = requests.single().getJSONArray("input").objects().last()
                    assertEquals("call_pending", wire.getString("call_id"))
                    assertFalse(wire.getString("output").contains("Original teacher-only explanation"))
                    assertTrue(wire.getString("output").contains("Learner-only reasoning"))
                    val state = runtime.state.value!!
                    assertEquals(state.error, "question", state.status)
                    assertEquals("call_next|fc_next", state.question!!.callId)
                    assertFalse(state.expanded)
                    assertTrue(state.items.any { it.text == "Authoritative fixture continuation" })
                    assertFalse(state.items.any { it.text.contains("New hidden commitment") || it.text.contains("Provisional text") })
                    assertEquals(ids, state.nativeDisplayIds.filterKeys { it in ids })
                    assertEquals("fixture", state.model); assertEquals("fixture-account", state.connection)
                    assertEquals(listOf("fixture-account"), connections)
                    assertEquals("openai-codex", state.nativeRecord!!.snapshot().getJSONArray("messages").objects().last().getString("provider"))
                    runtime.edit("Unsubmitted answer", -1, 70, false)
                    runtime.stop().join()
                    val reopened = SessionRuntime(app, store, fake,
                        providerFor = { connection -> connections += connection; fake })
                    try {
                        reopened.open(initial.id).join()
                        val restored = reopened.state.value!!
                        assertEquals(1, requests.size); assertEquals(70, restored.confidence)
                        assertEquals("Unsubmitted answer", restored.draft); assertFalse(restored.expanded)
                        assertEquals(state.nativeRecord!!.raw, restored.nativeRecord!!.raw)
                        reopened.edit("Learner recall answer", -1, 70, true)
                        reopened.send().join()
                        assertEquals(2, requests.size)
                        assertEquals(listOf("fixture-account", "fixture-account"), connections)
                        val compact = requests.last().getJSONArray("input").objects().last().getString("output")
                        assertTrue(compact.contains("Learner recall answer")); assertFalse(compact.contains("New hidden commitment"))
                        val saved = store.load(initial.id)!!.nativeRecord!!.snapshot()
                        assertEquals("PRESERVE_AUTHORED_OPAQUE_FIELD", saved.getString("future"))
                        assertTrue(saved.getJSONArray("messages").objects().any { it.optJSONObject("details")?.optString("committed") == "New hidden commitment" })
                        reopened.retry().join() // Successful prose ending is not permission to generate again.
                        assertEquals(2, requests.size)
                    } finally { reopened.stop().join() }
                    assertEquals(1, completed)
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun probeHandoffRepairsAPlanThenWaitsForANaturalReplyBeforeTeaching() = runBlocking {
        val name = "native-handoff-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val base = fixture()
                val root = base.nativeRecord!!.snapshot().apply { remove("tree") }
                root.put("messages", JSONArray().put(root.getJSONArray("messages").getJSONObject(0)))
                val initial = base.copy(nativeRecord = NativeChat.parse(root.toString()))
                store.save(initial)
                var calls = 0
                var finished = 0
                val fake = ModelProvider { request, _ ->
                    calls++
                    val committed = store.load(initial.id)!!.nativeRecord!!
                    val advertised = request.getJSONArray("tools").objects().flatMap {
                        if (it.optString("type") == "namespace") it.getJSONArray("tools").objects() else listOf(it)
                    }.map { it.getString("name") }.toSet()
                    val (tool, args) = when (calls) {
                        1 -> {
                            assertEquals("probe", committed.state().phase)
                            assertTrue("probe_done" in advertised); assertFalse("plan_init" in advertised)
                            "probe" to JSONObject("""{"question":"[Authored test] Which is even?",
                              "options":[{"label":"Two","value":"two"},{"label":"Three","value":"three"}],
                              "correctAnswer":"two","explanation":"Probe commitment"}""")
                        }
                        2 -> {
                            assertEquals("toolResult", committed.snapshot().getJSONArray("messages").objects().last().getString("role"))
                            "probe_done" to JSONObject("""{"assessment":{"strands":[{"strand":"Evenness","assessment":"Authored learner evidence, not mastery"}]}}""")
                        }
                        3 -> {
                            assertEquals("plan", committed.state().phase)
                            assertTrue(NativeTeaching.handoffPending(committed))
                            assertTrue("plan_init" in advertised); assertFalse("probe_done" in advertised); assertFalse("quiz" in advertised)
                            assertTrue(request.getJSONArray("input").toString().contains(NativeTeaching.PROBE_ACK))
                            "plan_init" to JSONObject().put("mermaid", "graph TD\nbasics --> basics")
                        }
                        4 -> {
                            assertFalse(committed.snapshot().has("tree"))
                            assertFalse(committed.snapshot().getJSONArray("messages").objects().last().getJSONObject("details").getBoolean("ok"))
                            "plan_init" to JSONObject().put("mermaid", "graph TD\nbasics[Even integers]")
                        }
                        5 -> {
                            assertEquals("plan", committed.state().phase)
                            assertEquals("user", committed.snapshot().getJSONArray("messages").objects().last().getString("role"))
                            assertTrue("quiz" in advertised)
                            "quiz" to JSONObject("""{"question":"[Authored test] What is even?","type":"check","nodeId":"basics",
                              "options":[{"label":"Two","value":"two"},{"label":"Three","value":"three"}],
                              "correctAnswer":"two","explanation":"Hidden new teaching commitment"}""")
                        }
                        else -> error("Unexpected automatic continuation: $calls")
                    }
                    JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
                        .put("type", "function_call").put("call_id", "flow_$calls").put("id", "fc_flow_$calls")
                        .put("name", tool).put("arguments", args.toString())))
                        .put("usage", JSONObject().put("input_tokens", 100 + calls).put("output_tokens", 10)
                            .put("input_tokens_details", JSONObject().put("cached_tokens", 25))
                            .put("output_tokens_details", JSONObject().put("reasoning_tokens", 3)))
                }
                val runtime = SessionRuntime(app, store, fake, afterTurn = { finished++ })
                try {
                    runtime.open(initial.id).join()
                    assertEquals("probe", runtime.state.value!!.phase); assertEquals(0, calls)
                    runtime.retry().join() // Explicit start; opening above is local-only.
                    assertEquals(1, calls); assertEquals("probe", runtime.state.value!!.phase)
                    assertEquals(runtime.state.value!!.error, "question", runtime.state.value!!.status)
                    answer(runtime); runtime.send().join()
                    val planned = runtime.state.value!!
                    assertEquals(planned.error, "idle", planned.status)
                    assertEquals(4, calls); assertEquals(2, finished)
                    val turns = planned.nativeRecord!!.snapshot().getJSONArray("turns").objects()
                    assertEquals(listOf(1, 3), turns.map { it.getJSONArray("steps").length() })
                    assertEquals(listOf(77, 78, 79), turns[1].getJSONArray("steps").objects().map { it.getInt("inputTokens") })
                    assertEquals(410L, planned.inputTokens); assertEquals(40L, planned.outputTokens)
                    assertEquals("plan", planned.nativeRecord!!.state().phase)
                    assertFalse(planned.planApproved); assertFalse(planned.planPending)
                    assertEquals("basics", planned.plan!!.nodes.single().id)
                    assertNotNull(planned.assessment)
                    assertTrue(planned.items.any { it.role == Role.ASSESSMENT })
                    assertTrue(planned.items.any { it.role == Role.PLAN })
                    assertEquals("[]", planned.protocol)
                    runtime.retry().join(); runtime.approveRoute().join()
                    assertEquals(4, calls) // No verdict/approval event or hidden extra request.
                    runtime.stop().join()
                    val reopened = SessionRuntime(app, store, fake)
                    try {
                        reopened.open(initial.id).join()
                        assertEquals(4, calls)
                        assertEquals(planned.nativeRecord.raw, reopened.state.value!!.nativeRecord!!.raw)
                        assertEquals(planned.nativeDisplayIds, reopened.state.value!!.nativeDisplayIds)
                        reopened.edit("Let's start with that plan", -1, 70, true)
                        reopened.send().join()
                        assertEquals(5, calls)
                        val teaching = reopened.state.value!!
                        assertEquals(teaching.error, "question", teaching.status)
                        assertEquals("teach", teaching.nativeRecord!!.state().phase)
                        assertEquals("basics", teaching.nativeRecord.state().current)
                        assertFalse(teaching.expanded)
                        assertFalse(teaching.items.any { it.text.contains("Hidden new teaching commitment") })
                        val history = teaching.nativeRecord.snapshot().getJSONArray("messages").objects()
                        assertEquals(1, history.count { it.optString("customType") == "probe-handoff" })
                        assertEquals(1, history.count { it.optString("toolName") == "probe_done" })
                        assertEquals("PRESERVE_AUTHORED_OPAQUE_FIELD", teaching.nativeRecord.snapshot().getString("future"))
                        exportFixture(teaching, "teaching")
                    } finally { reopened.stop().join() }
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun nativePracticeCommitsThinkingRetriesRevealsAndDefersSilentDismissalUntilExplicitInput() = runBlocking {
        val name = "native-practice-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val base = fixture()
                val root = base.nativeRecord!!.snapshot().apply { remove("tree") }
                root.put("kind", "practice").put("messages", JSONArray().put(root.getJSONArray("messages").getJSONObject(0)))
                val initial = base.copy(nativeRecord = NativeChat.parse(root.toString()))
                store.save(initial)
                val requests = mutableListOf<JSONObject>()
                val solution = "AUTHORED_HIDDEN_PRACTICE_SOLUTION"
                val fake = ModelProvider { request, _ ->
                    requests += JSONObject(request.toString())
                    val committed = store.load(initial.id)!!
                    val history = committed.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                    val count = requests.size
                    if (count in setOf(2, 3, 4)) {
                        if (count != 3) assertEquals("practice-thinking", history.last().optString("customType"))
                        else assertEquals("error", history.last().getString("stopReason"))
                        assertEquals(if (count == 4) 2 else 1, history.count { it.optString("customType") == "practice-thinking" })
                    }
                    if (count == 2) throw IOException("Authored offline retry")
                    val output = JSONArray()
                    when (count) {
                        1, 6, 8 -> {
                            assertEquals("practice-next", history.last().optString("customType"))
                            assertNull(committed.nativeRecord.state().activeProblem)
                            output.put(JSONObject().put("type", "reasoning").put("summary", JSONArray().put(
                                JSONObject().put("type", "summary_text").put("text", solution))))
                            output.put(JSONObject().put("type", "function_call").put("call_id", "practice_$count").put("id", "fc_practice_$count")
                                .put("name", "problem").put("arguments", JSONObject().put("statement", "[Authored test] Prove parity $count")
                                    .put("mode", "composition").put("modelSolution", solution).toString())) // target is absent, not fabricated.
                        }
                        4 -> output.put(JSONObject().put("type", "function_call").put("call_id", "practice_conclusion")
                            .put("name", "conclude").put("arguments", JSONObject().put("outcome", "solvedWithHints")
                                .put("verdict", "Authored model judgment").put("rung", "R1").put("missClass", JSONObject.NULL).put("retest", true).toString()))
                        3, 5, 7 -> {
                            if (count == 5) {
                                assertEquals("conclude", history.last().optString("toolName"))
                                assertEquals(0, request.getJSONArray("tools").length())
                            }
                            if (count == 7) {
                                val tail = history.takeLast(3)
                                assertEquals("dismissed", tail[0].getJSONArray("content").getJSONObject(0).getJSONObject("arguments").getString("outcome"))
                                assertEquals("conclude", tail[1].getString("toolName"))
                                assertEquals("user", tail[2].getString("role"))
                                assertFalse(request.getJSONArray("input").toString().contains("problem-dismissed"))
                            }
                            output.put(JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray().put(
                                JSONObject().put("type", "output_text").put("text", "Authored prose $count"))))
                        }
                        else -> error("Unexpected practice request $count")
                    }
                    JSONObject().put("status", "completed").put("output", output)
                }
                val runtime = SessionRuntime(app, store, fake)
                try {
                    runtime.open(initial.id).join()
                    assertEquals("practice", runtime.state.value!!.phase); assertTrue(requests.isEmpty())
                    runtime.nextProblem().join()
                    val posed = runtime.state.value!!
                    assertEquals(posed.error, "idle", posed.status)
                    assertEquals("practice_1|fc_practice_1", posed.activeProblem!!.id)
                    assertFalse(posed.items.any { it.text.contains(solution) })
                    runtime.edit("First working trace", -1, 60, true); runtime.send().join()
                    assertEquals("error", runtime.state.value!!.status)
                    runtime.retry().join()
                    assertEquals(3, requests.size)
                    assertEquals(listOf("First working trace"), runtime.state.value!!.activeProblem!!.attempts)
                    runtime.edit("Final answer", -1, 60, true); runtime.send().join()
                    assertEquals(5, requests.size)
                    val ended = runtime.state.value!!
                    assertEquals(ended.error, "idle", ended.status)
                    assertNull(ended.activeProblem); assertFalse(ended.practiceReady)
                    assertEquals(listOf("First working trace", "Final answer"), ended.problems.single().attempts)
                    assertEquals("solvedWithHints", ended.problems.single().outcome)
                    assertTrue(ended.items.any { it.role == Role.PRACTICE && it.text.endsWith(solution) })
                    runtime.stop().join()
                    val reopened = SessionRuntime(app, store, fake)
                    try {
                        reopened.open(initial.id).join(); reopened.retry().join()
                        assertEquals(5, requests.size)
                        assertEquals(ended.nativeRecord!!.raw, reopened.state.value!!.nativeRecord!!.raw)
                        assertEquals(ended.nativeDisplayIds, reopened.state.value!!.nativeDisplayIds)
                        reopened.nextProblem().join()
                        assertEquals(6, requests.size)
                        reopened.edit("Unsent draft must survive dismissal", -1, 60, true)
                        reopened.dismissProblem().join()
                        val dismissed = reopened.state.value!!
                        assertEquals(6, requests.size); assertNull(dismissed.activeProblem)
                        assertEquals("Unsent draft must survive dismissal", dismissed.draft)
                        assertEquals("practice_6|fc_practice_6", dismissed.nativeRecord!!.state().pendingDismissal)
                        assertEquals(1, dismissed.items.count { it.role == Role.PRACTICE })
                        reopened.retry().join(); assertEquals(6, requests.size)
                        reopened.send().join()
                        assertEquals(7, requests.size)
                        assertNull(reopened.state.value!!.nativeRecord!!.state().pendingDismissal)
                        reopened.nextProblem().join()
                        assertEquals(8, requests.size)
                        val last = reopened.state.value!!
                        assertNotNull(last.activeProblem)
                        assertEquals("[]", last.protocol)
                        assertEquals("PRESERVE_AUTHORED_OPAQUE_FIELD", last.nativeRecord!!.snapshot().getString("future"))
                        assertEquals(1, last.nativeRecord.snapshot().getJSONArray("messages").objects().count {
                            it.optString("role") == "assistant" && it.optString("model") == "synthetic-conclude"
                        })
                        val turns = last.nativeRecord.snapshot().getJSONArray("turns").objects()
                        assertEquals(8, turns.sumOf { it.getJSONArray("steps").length() })
                        assertEquals(7, turns.size) // conclude and its closing prose share one invocation.
                        assertTrue(turns.all { turn -> turn.getJSONArray("steps").objects().all { it.length() == 0 } })
                        exportFixture(last, "practice")
                    } finally { reopened.stop().join() }
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun rejectedAndIncompleteResponsesKeepReportedMoneyWhileRetriesDoNotInventMissingUsage() = runBlocking {
        val name = "native-money-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val initial = fixture(); store.save(initial)
                var calls = 0
                val fake = ModelProvider { _, _ ->
                    when (++calls) {
                        1 -> response().put("output", JSONArray().put(JSONObject().put("type", "function_call")
                            .put("call_id", "denied").put("name", "research").put("arguments", "{}")))
                        2 -> throw ProviderFailure("incomplete", response().put("status", "incomplete").apply {
                            getJSONObject("usage").put("input_tokens", 50)
                        })
                        3 -> throw IOException("Authored offline unknown spend")
                        4 -> response().apply { getJSONObject("usage").put("input_tokens", -1) }
                        5 -> response().apply { remove("usage") }
                        else -> error("Unexpected request")
                    }
                }
                val runtime = SessionRuntime(app, store, fake)
                try {
                    runtime.open(initial.id).join(); answer(runtime); runtime.send().join()
                    assertEquals("tool_denied", runtime.state.value!!.error)
                    runtime.retry().join(); assertEquals("incomplete", runtime.state.value!!.error)
                    runtime.retry().join(); assertEquals("network", runtime.state.value!!.error)
                    runtime.retry().join(); assertEquals("invalid_output", runtime.state.value!!.error)
                    runtime.retry().join(); assertEquals("idle", runtime.state.value!!.status)
                    val saved = store.load(initial.id)!!
                    assertEquals(5, calls); assertEquals(90L, saved.inputTokens); assertEquals(20L, saved.outputTokens)
                    val root = saved.nativeRecord!!.snapshot()
                    val turns = root.getJSONArray("turns").objects()
                    assertEquals(5, turns.size); assertEquals(5, turns.map { it.getString("id") }.distinct().size)
                    assertEquals(listOf(40, 50), turns.take(2).map { it.getJSONArray("steps").getJSONObject(0).getInt("inputTokens") })
                    assertTrue(turns.drop(2).all { it.getJSONArray("steps").getJSONObject(0).length() == 0 })
                    val failures = root.getJSONArray("messages").objects().filter { it.optString("stopReason") == "error" }
                    assertEquals(4, failures.size)
                    assertEquals("incomplete", failures[1].getString("rawStopReason"))
                    assertEquals(50, failures[1].getJSONObject("usage").getInt("input"))
                    assertFalse(failures[2].has("usage")); assertFalse(failures[3].has("usage"))
                    assertEquals(-1, failures[3].getJSONObject("reasonedResponses").getJSONObject("usage").getInt("input_tokens"))
                    assertTrue(turns.all { entry -> root.getJSONArray("messages").objects().any { it.getString("id") == entry.getString("messageId") } })
                    runtime.open(initial.id).join(); runtime.retry().join()
                    assertEquals(5, calls); assertEquals(root.toString(), runtime.state.value!!.nativeRecord!!.snapshot().toString())
                    exportFixture(saved, "failures")
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun nativePracticeCannotAutoPoseAfterConcludeEvenOnRetry() = runBlocking {
        val name = "native-practice-gate-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val base = fixture()
                val problem = JSONObject().put("statement", "[Authored test] Solve parity").put("modelSolution", "Hidden original solution").put("mode", "recall")
                val root = base.nativeRecord!!.snapshot().apply { remove("tree") }
                root.put("kind", "practice").put("messages", JSONArray().put(root.getJSONArray("messages").getJSONObject(0))
                    .put(pose("active", "problem", problem)).put(result("active", "problem", JSONObject())))
                val initial = base.copy(nativeRecord = NativeChat.parse(root.toString()))
                store.save(initial)
                var calls = 0
                val fake = ModelProvider { request, _ ->
                    calls++
                    if (calls > 1) assertEquals(0, request.getJSONArray("tools").length())
                    val output = if (calls == 3) JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
                        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Authored closing prose"))))
                    else JSONArray().put(JSONObject().put("type", "function_call").put("call_id", "gate_$calls")
                        .put("name", if (calls == 1) "conclude" else "problem").put("arguments", if (calls == 1)
                            JSONObject().put("outcome", "surrendered").put("verdict", "Authored verdict").put("rung", JSONObject.NULL)
                                .put("missClass", JSONObject.NULL).put("retest", true).toString() else problem.toString()))
                    JSONObject().put("status", "completed").put("output", output)
                }
                val runtime = SessionRuntime(app, store, fake)
                try {
                    runtime.open(initial.id).join()
                    runtime.edit("I surrender", -1, 60, true); runtime.send().join()
                    assertEquals(2, calls)
                    val denied = runtime.state.value!!
                    assertEquals("tool_denied", denied.error); assertNull(denied.activeProblem)
                    assertEquals("surrendered", denied.problems.single().outcome)
                    assertTrue(denied.items.any { it.role == Role.PRACTICE && it.text.endsWith("Hidden original solution") })
                    runtime.retry().join()
                    assertEquals(3, calls); assertEquals("idle", runtime.state.value!!.status)
                    assertEquals(1, runtime.state.value!!.nativeRecord!!.snapshot().getJSONArray("messages").objects().count {
                        it.optString("role") == "toolResult" && it.optString("toolName") == "conclude"
                    })
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun failedAttemptIsDurableRetryIsWireOnlyAndTheLearnerAnswerIsNotDuplicated() = runBlocking {
        val name = "native-retry-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val initial = fixture(); store.save(initial)
                var calls = 0
                var retryInput = ""
                val fake = ModelProvider { request, delta ->
                    calls++
                    if (calls == 1) { delta("Authored interrupted fragment"); throw IOException("Authored network failure") }
                    retryInput = request.getJSONArray("input").toString(); response()
                }
                val runtime = SessionRuntime(app, store, fake)
                try {
                    runtime.open(initial.id).join(); answer(runtime); runtime.send().join()
                    assertEquals("error", runtime.state.value!!.status)
                    val failed = store.load(initial.id)!!.nativeRecord!!
                    val message = failed.snapshot().getJSONArray("messages").objects().last()
                    assertEquals("error", message.getString("stopReason"))
                    runtime.stop().join()
                    val reopened = SessionRuntime(app, store, fake)
                    try {
                        reopened.open(initial.id).join(); assertEquals(1, calls)
                        reopened.retry().join(); assertEquals(2, calls)
                        assertFalse(retryInput.contains("Authored interrupted fragment"))
                        val history = reopened.state.value!!.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                        assertEquals(1, history.count { it.optString("toolCallId") == "call_pending|fc_pending" })
                        assertEquals(message.toString(), history.first { it.optString("id") == message.getString("id") }.toString())
                        assertEquals("idle", reopened.state.value!!.status)
                    } finally { reopened.stop().join() }
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun questionBatchesAdvanceLocallyAndOnlyTheLastAnswerWakesTheProvider() = runBlocking {
        val name = "native-batch-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                for (mode in listOf("answer", "mixed", "dismiss")) {
                    val initial = fixture(); store.save(initial)
                    var calls = 0
                    val fake = ModelProvider { request, _ ->
                        calls++
                        if (calls == 1) {
                            val output = JSONArray().put(JSONObject().put("type", "reasoning").put("summary", JSONArray()
                                .put(JSONObject().put("type", "summary_text").put("text", "AUTHORED_BATCH_SECRET"))))
                            repeat(3) { index ->
                                val args = if (index == 0) JSONObject().put("question", "Authored preference?") else
                                    JSONObject("""{"question":"Authored batch question $index","type":"check","nodeId":"basics",
                                        "options":[{"label":"Two","value":"two"},{"label":"Three","value":"three"}],
                                        "correctAnswer":"two","explanation":"Authored batch answer"}""")
                                output.put(JSONObject().put("type", "function_call").put("call_id", "batch_$index")
                                    .put("id", "fc_batch_$index").put("name", if (index == 0) "ask_user_question" else "quiz")
                                    .put("arguments", args.toString()))
                            }
                            JSONObject().put("status", "completed").put("output", output)
                                .put("usage", JSONObject().put("input_tokens", 40).put("output_tokens", 10))
                        } else {
                            assertEquals(2, calls)
                            val messages = store.load(initial.id)!!.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                            val ownerIndex = messages.indexOfFirst { message -> message.optString("role") == "assistant" &&
                                message.getJSONArray("content").objects().any { it.optString("id") == "batch_0|fc_batch_0" } }
                            assertTrue(ownerIndex >= 0)
                            assertEquals((0..2).map { "batch_$it|fc_batch_$it" }, messages.drop(ownerIndex + 1).take(3).map { it.getString("toolCallId") })
                            val results = request.getJSONArray("input").objects().filter {
                                it.optString("type") == "function_call_output" && it.optString("call_id").startsWith("batch_") }
                            assertEquals(3, results.size)
                            assertFalse(results.any { it.getString("output").contains("interrupted", ignoreCase = true) })
                            response()
                        }
                    }
                    val runtime = SessionRuntime(app, store, fake)
                    try {
                        runtime.open(initial.id).join(); answer(runtime); runtime.send().join()
                        assertEquals(1, calls)
                        val posed = store.load(initial.id)!!
                        assertEquals(3, posed.nativeRecord!!.snapshot().getJSONArray("messages").objects().last()
                            .getJSONArray("content").objects().count { it.optString("type") == "toolCall" })
                        for (index in 0..2) {
                            val callId = "batch_$index|fc_batch_$index"
                            val current = runtime.state.value!!
                            assertEquals(callId, current.question!!.callId)
                            assertFalse(current.items.any { it.text.contains("AUTHORED_BATCH_SECRET") })
                            runtime.retry().join(); assertEquals(1, calls)
                            if (mode == "dismiss" || (mode == "mixed" && index == 1)) runtime.dismissQuestion(callId).join()
                            else {
                                runtime.edit("Learner batch thinking $index", -1, 90, true,
                                    readingThinking = "Learner batch reading $index", selectedValues = if (index == 0) emptyList() else listOf("two"))
                                runtime.send().join()
                            }
                            if (index < 2 || mode == "dismiss") {
                                assertEquals(1, calls)
                                assertEquals(40L, store.load(initial.id)!!.inputTokens)
                                val raw = store.load(initial.id)!!.nativeRecord!!.raw
                                runtime.dismissQuestion(callId).join() // Stale/duplicate target must not consume the sibling.
                                runtime.open(initial.id).join()
                                assertEquals(raw, store.load(initial.id)!!.nativeRecord!!.raw)
                            }
                        }
                        if (mode == "dismiss") {
                            assertNull(runtime.state.value!!.question)
                            runtime.retry().join(); assertEquals(1, calls)
                            assertFalse(runtime.state.value!!.items.any { it.text.contains("AUTHORED_BATCH_SECRET") })
                            runtime.edit("Authored move on after discarding batch", -1, 50, true)
                            runtime.send().join()
                        }
                        assertEquals(2, calls); assertEquals("idle", runtime.state.value!!.status)
                        val final = store.load(initial.id)!!
                        assertEquals(80L, final.inputTokens); assertEquals(20L, final.outputTokens)
                        assertEquals(2, final.nativeRecord!!.snapshot().getJSONArray("turns").length())
                        val before = posed.nativeRecord.snapshot().getJSONArray("messages").objects().map { it.toString() }
                        val after = final.nativeRecord.snapshot().getJSONArray("messages").objects()
                        assertEquals(before, after.take(before.size).map { it.toString() })
                        assertEquals("PRESERVE_AUTHORED_OPAQUE_FIELD", final.nativeRecord.snapshot().getString("future"))
                        assertEquals(1 + if (mode == "answer") 3 else if (mode == "mixed") 2 else 0, final.answers)
                        runtime.open(initial.id).join(); runtime.retry().join(); assertEquals(2, calls)
                    } finally { runtime.stop().join() }
                }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun questionDismissalIsSilentDurableAndOnlyExplicitTextContinuesIt() = runBlocking {
        val name = "native-dismiss-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val initial = fixture(); store.save(initial)
                val original = initial.nativeRecord!!.snapshot().getJSONArray("messages").objects().map { it.toString() }
                var calls = 0
                val fake = ModelProvider { request, _ ->
                    calls++
                    val durable = store.load(initial.id)!!
                    assertNull(durable.question)
                    assertTrue(durable.nativeRecord!!.snapshot().getJSONArray("messages").objects().any {
                        it.optString("toolCallId") == "call_pending|fc_pending" && it.getJSONObject("details").optBoolean("dismissed")
                    })
                    assertTrue(request.getJSONArray("input").toString().contains("Authored unsent draft"))
                    assertFalse(request.getJSONArray("input").toString().contains("Learner reading thought"))
                    response()
                }
                val runtime = SessionRuntime(app, store, fake)
                try {
                    runtime.open(initial.id).join(); answer(runtime)
                    runtime.edit("Authored unsent draft", -1, 90, false, readingThinking = "Learner reading thought", dontKnow = true)
                    runtime.dismissQuestion("stale-call").join()
                    assertNotNull(runtime.state.value!!.question)
                    runtime.dismissQuestion("call_pending|fc_pending").join()
                    val paused = store.load(initial.id)!!
                    assertEquals(0, calls); assertEquals("idle", paused.status); assertNull(paused.question)
                    assertEquals("Authored unsent draft", paused.draft)
                    assertEquals("Learner reading thought", paused.readingThinking)
                    assertFalse(paused.dontKnow); assertEquals(0, paused.answers); assertTrue(paused.answerCards.isEmpty())
                    assertEquals(0L, paused.inputTokens); assertEquals(0L, paused.outputTokens)
                    val history = paused.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                    assertEquals(original, history.take(original.size).map { it.toString() })
                    val details = history.last().getJSONObject("details")
                    assertTrue(details.getBoolean("dismissed"))
                    assertFalse(details.getBoolean("dontKnow")); assertEquals(0, details.getJSONArray("answers").length())
                    for (key in listOf("correct", "confidence", "thinking", "readingThinking")) assertFalse(details.has(key))
                    assertFalse(paused.items.any { it.text.contains("Original teacher-only explanation") })
                    runtime.dismissQuestion("call_pending|fc_pending").join(); runtime.retry().join()
                    runtime.open(initial.id).join()
                    assertEquals(0, calls); assertEquals(paused.nativeRecord.raw, store.load(initial.id)!!.nativeRecord!!.raw)
                    runtime.send().join()
                    assertEquals(1, calls); assertEquals("idle", runtime.state.value!!.status)
                    val continued = store.load(initial.id)!!
                    assertEquals("PRESERVE_AUTHORED_OPAQUE_FIELD", continued.nativeRecord!!.snapshot().getString("future"))
                    assertEquals(1, continued.nativeRecord.snapshot().getJSONArray("messages").objects().count {
                        it.optString("toolCallId") == "call_pending|fc_pending"
                    })
                    runtime.open(initial.id).join(); runtime.retry().join(); assertEquals(1, calls)
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun nativeSteeringMenuQueuesLearnerTextAndRetainsAnUnsentSettleRaceDraft() = runBlocking {
        val name = "native-steering-ui-${UUID.randomUUID()}.db"
        val delegate = ReasonedApplication::class.java.getDeclaredField("runtimeDelegate").apply { isAccessible = true }
        val previous = delegate.get(app)
        val realSessions = app.sessions.list()
        try {
            SessionStore(app, name).use { store ->
                val initial = fixture(); store.save(initial)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var calls = 0
                val runtime = SessionRuntime(app, store, ModelProvider { _, _ ->
                    if (++calls == 1) { entered.complete(Unit); release.await() }
                    response()
                })
                try {
                    delegate.set(app, lazyOf(runtime)) // Existing UI-test seam; no real provider/account changes.
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        scenario.onActivity { it.openSession(initial.id) }
                        val opened = withTimeoutOrNull(5000) { runtime.state.first { it?.id == initial.id && it.status == "question" } }
                        assertNotNull("UI open failed: ${runtime.failure.value}", opened)
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        val job = withContext(Dispatchers.Main) { answer(runtime); runtime.send() }
                        val started = withTimeoutOrNull(5000) { entered.await() }
                        assertNotNull("UI request did not start: ${runtime.state.value?.status}, ${runtime.failure.value}", started)
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        val dialogField = RuntimeSessionFragment::class.java.getDeclaredField("dialog").apply { isAccessible = true }
                        var lastDialog: AlertDialog? = null
                        scenario.onActivity { activity ->
                            val fragment = activity.fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>().single()
                            val menu = fragment.view!!.findViewById<Toolbar>(me.grishka.appkit.R.id.toolbar).menu
                            assertTrue(menu.findItem(R.id.session_steer).isVisible)
                            assertTrue(menu.findItem(R.id.session_steer).isEnabled)
                            assertTrue(menu.performIdentifierAction(R.id.session_steer, 0))
                            lastDialog = dialogField.get(fragment) as AlertDialog
                            val input = lastDialog!!.findViewById<EditText>(R.id.session_name)
                            assertTrue(input.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                            input.setText("[Authored UI steer] Please slow down")
                        }
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync() // Let the dialog finish showing before clicking.
                        scenario.onActivity { lastDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
                        val queued = withTimeoutOrNull(5000) { runtime.state.first { it != null && JSONArray(it.nativeSteers).length() == 1 } }
                        assertNotNull("UI steer not queued: ${runtime.state.value?.status}, ${runtime.failure.value}, calls=$calls", queued)
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        assertFalse(lastDialog!!.isShowing)
                        scenario.onActivity { activity ->
                            assertTrue(activity.findViewById<TextView>(R.id.lesson_status).text.contains(activity.getString(R.string.native_steer_pending, 1)))
                            val fragment = activity.fragmentManager.fragments.filterIsInstance<RuntimeSessionFragment>().single()
                            val menu = fragment.view!!.findViewById<Toolbar>(me.grishka.appkit.R.id.toolbar).menu
                            assertTrue(menu.performIdentifierAction(R.id.session_steer, 0))
                            lastDialog = dialogField.get(fragment) as AlertDialog
                            lastDialog!!.findViewById<EditText>(R.id.session_name).setText("[Authored UI draft] Do not send after settlement")
                        }
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        release.complete(Unit); job.join()
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        scenario.onActivity {
                            assertEquals(2, calls)
                            assertTrue(runtime.state.value!!.items.any { row -> row.role == Role.LEARNER && row.text == "[Authored UI steer] Please slow down" })
                            lastDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        }
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        scenario.onActivity {
                            assertTrue(lastDialog!!.isShowing)
                            val input = lastDialog!!.findViewById<EditText>(R.id.session_name)
                            assertEquals("[Authored UI draft] Do not send after settlement", input.text.toString())
                            assertNotNull(input.error)
                            lastDialog!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                        }
                        assertEquals(2, calls)
                        assertEquals("[]", store.load(initial.id)!!.nativeSteers)
                        assertFalse(store.load(initial.id)!!.nativeRecord!!.raw.contains("[Authored UI draft]"))
                    }
                } finally { runtime.stop().join() }
            }
            assertEquals(realSessions, app.sessions.list())
        } finally { delegate.set(app, previous); app.deleteDatabase(name) }
    }

    @Test fun learnerSteersDrainTogetherAtResponseBoundariesButNeverWakeAStoppedRun() = runBlocking {
        val name = "native-steering-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                for (mode in listOf("prose", "rewrite", "question", "error", "stop", "late-stop", "plan", "practice")) {
                    val base = fixture().copy(connection = "fixture-account")
                    val initial = if (mode !in setOf("plan", "practice")) base else base.copy(nativeRecord = NativeChat.parse(
                        base.nativeRecord!!.snapshot().apply {
                            remove("tree")
                            if (mode == "practice") put("kind", "practice")
                            put("messages", JSONArray(getJSONArray("messages").objects().take(if (mode == "practice") 1 else 3)))
                        }.toString()))
                    store.save(initial)
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val requests = mutableListOf<JSONObject>()
                    val fake = ModelProvider { request, _ ->
                        requests += JSONObject(request.toString())
                        if (requests.size == 1) {
                            entered.complete(Unit)
                            if (mode == "late-stop") withContext(NonCancellable) { release.await() } else release.await()
                            when (mode) {
                                "error" -> throw IOException("Authored offline failure")
                                "question" -> response(question = true)
                                "practice" -> response().put("output", JSONArray().put(JSONObject()
                                    .put("type", "function_call").put("call_id", "authored_steered_problem")
                                    .put("name", "problem").put("namespace", RuntimeTools.NAMESPACE)
                                    .put("arguments", JSONObject().put("statement", "[Authored test] Show evenness")
                                        .put("modelSolution", "AUTHORED_HIDDEN_STEER_SOLUTION").put("mode", "recall").toString())))
                                "plan", "rewrite" -> response().put("output", JSONArray().put(JSONObject()
                                    .put("type", "function_call").put("call_id", "authored_first_plan")
                                    .put("name", "plan_init").put("namespace", RuntimeTools.NAMESPACE)
                                    .put("arguments", "{\"mermaid\":\"graph TD\\nbasics[Even integers]\"}")))
                                else -> response()
                            }
                        } else response()
                    }
                    val runtime = SessionRuntime(app, store, fake)
                    try {
                        runtime.open(initial.id).join(); runtime.chooseMechanic("recall").join()
                        if (mode in setOf("plan", "practice")) runtime.edit("[Authored test] Please start", -1, 60, true)
                        else answer(runtime)
                        assertFalse(runtime.steer(initial.id, "Not a running turn"))
                        val job = runtime.send()
                        withTimeout(10_000) { entered.await() }
                        val stale = store.load(initial.id)!!
                        assertFalse(runtime.steer("different-session", "Not this run"))
                        assertFalse(runtime.steer(initial.id, " "))
                        assertFalse(runtime.steer(initial.id, "x".repeat(2001)))
                        val words = if (mode == "prose") List(8) { "[Authored steer] Slow down, note $it" }
                            else listOf("[Authored steer] Slow down", "[Authored steer] Explain without granting research tools")
                        words.forEach { assertTrue(runtime.steer(initial.id, it)) }
                        if (mode == "prose") assertFalse(runtime.steer(initial.id, "Ninth message must stay unsent"))
                        assertThrows(IllegalStateException::class.java) { store.queueNativeSteer(initial.id, "different-run", "Not this run") }
                        val queued = JSONArray(store.load(initial.id)!!.nativeSteers).objects()
                        assertEquals(words, queued.map { it.getString("content") })
                        assertTrue(queued.all { it.getString("id").startsWith("steer_") })
                        assertEquals(stale.nativeRecord!!.raw, store.load(initial.id)!!.nativeRecord!!.raw)
                        store.save(stale) // An older command checkpoint must not erase the concurrent queue.
                        assertEquals(queued.map { it.toString() }, JSONArray(store.load(initial.id)!!.nativeSteers).objects().map { it.toString() })
                        assertEquals(1, requests.size)
                        if (mode == "stop") runtime.stop().join()
                        else if (mode == "late-stop") { val stopping = runtime.stop(); release.complete(Unit); stopping.join() }
                        else release.complete(Unit)
                        job.join()
                        assertEquals("$mode: ${runtime.state.value!!.error}", when (mode) {
                            "question" -> "question"; "error" -> "error"; "stop", "late-stop" -> "interrupted"; else -> "idle"
                        }, runtime.state.value!!.status)
                        assertFalse(runtime.steer(initial.id, "The run already ended"))
                        if (mode !in setOf("prose", "rewrite")) {
                            assertEquals(1, requests.size)
                            assertEquals(2, JSONArray(store.load(initial.id)!!.nativeSteers).length())
                            store.recover(); store.recover()
                            runtime.open(initial.id).join()
                            assertEquals(1, requests.size) // Restore is local even with pending learner text.
                            runtime.retry().join()
                            if (mode == "error") assertEquals(2, requests.size)
                            else {
                                assertEquals("$mode: ${runtime.state.value!!.error}", 1, requests.size)
                                runtime.edit("[Authored test] Explicit next input", -1, 60, true)
                                runtime.send().join()
                            }
                        }
                        assertEquals("$mode: ${runtime.state.value!!.error}", 2, requests.size)
                        val final = store.load(initial.id)!!
                        assertEquals("idle", final.status); assertEquals("[]", final.nativeSteers)
                        assertEquals("[]", runtime.state.value!!.nativeSteers)
                        assertEquals(if (mode in setOf("error", "stop")) 40L else 80L, final.inputTokens)
                        assertFalse(final.researchAllowed)
                        if (mode == "practice") {
                            assertEquals("AUTHORED_HIDDEN_STEER_SOLUTION", final.activeProblem!!.solution)
                            assertFalse(final.items.any { it.text.contains("AUTHORED_HIDDEN_STEER_SOLUTION") })
                            assertEquals(listOf("[Authored test] Explicit next input"), final.activeProblem!!.attempts)
                        }
                        val messages = final.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                        queued.forEach { steer ->
                            assertEquals(1, messages.count { it.getString("id") == steer.getString("id") })
                            assertEquals(steer.toString(), messages.single { it.getString("id") == steer.getString("id") }.toString())
                            assertFalse(requests.first().getJSONArray("input").toString().contains(steer.getString("content")))
                            assertTrue(requests.last().getJSONArray("input").toString().contains(steer.getString("content")))
                        }
                        assertEquals(words, messages.filter { it.getString("id") in queued.map { it.getString("id") } }.map { it.getString("content") })
                        val turns = final.nativeRecord.snapshot().getJSONArray("turns").objects()
                        assertEquals(if (mode in setOf("prose", "rewrite")) 1 else 2, turns.size)
                        if (mode in setOf("prose", "rewrite")) assertEquals(2, turns.single().getJSONArray("steps").length())
                        val saved = final.nativeRecord.raw
                        runtime.open(initial.id).join(); runtime.retry().join()
                        assertEquals(2, requests.size); assertEquals(saved, store.load(initial.id)!!.nativeRecord!!.raw)
                    } finally { runtime.stop().join() }
                }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun nativeStreamFailuresAndCancellationNeverCommitPartialToolsOrRepeatLearnerAnswers() = runBlocking {
        val name = "native-stream-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                for (mode in listOf("eof", "malformed", "failed", "incomplete", "pending", "cancel")) {
                    val initial = fixture(); store.save(initial)
                    var calls = 0
                    val entered = CompletableDeferred<Unit>()
                    val reported = mode in setOf("failed", "incomplete", "pending")
                    fun event(type: String, terminal: JSONObject? = null) = "data: " + JSONObject().put("type", type)
                        .apply { if (terminal != null) put("response", terminal) }.toString() + "\n\n"
                    val terminal = response().put("status", if (mode == "pending") "completed" else mode)
                    val ending = when (mode) {
                        "failed", "incomplete" -> event("response.$mode", terminal)
                        "pending" -> "data: {\"type\":\"response.output_item.added\",\"output_index\":0," +
                            "\"item\":{\"type\":\"function_call\",\"call_id\":\"partial_sse_call\",\"name\":\"research\",\"arguments\":\"{}\"}}\n\n" +
                            event("response.completed", terminal)
                        "malformed" -> "data: {invalid-json}\n\n"
                        else -> ""
                    }
                    val stream = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"AUTHORED_UNCOMMITTED_DELTA\"}\n\n" + ending
                    val runtime = SessionRuntime(app, store, ModelProvider { _, delta ->
                        calls++
                        if (calls > 1) response()
                        else readResponseStream(Buffer().writeUtf8(stream)) { part ->
                            delta(part)
                            if (mode == "cancel") { entered.complete(Unit); awaitCancellation() }
                        }
                    })
                    try {
                        runtime.open(initial.id).join(); answer(runtime)
                        val job = runtime.send()
                        if (mode == "cancel") { withTimeout(10_000) { entered.await() }; runtime.stop().join() }
                        job.join()
                        val failed = store.load(initial.id)!!
                        val messages = failed.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                        assertEquals(if (mode == "cancel") "aborted" else "error", messages.last().getString("stopReason"))
                        assertFalse(messages.last().getJSONArray("content").objects().any { it.optString("type") == "toolCall" })
                        assertEquals(if (reported) 40L else 0L, failed.inputTokens)
                        assertEquals(if (reported) 10L else 0L, failed.outputTokens)
                        val step = failed.nativeRecord.snapshot().getJSONArray("turns").getJSONObject(0)
                            .getJSONArray("steps").getJSONObject(0)
                        assertEquals(reported, step.has("inputTokens"))
                        if (!reported) assertEquals(0, step.length())
                        val bytes = failed.nativeRecord.raw
                        runtime.open(initial.id).join()
                        assertEquals(1, calls); assertEquals(bytes, store.load(initial.id)!!.nativeRecord!!.raw)
                        runtime.retry().join()
                        assertEquals(if (mode == "cancel") 1 else 2, calls)
                        val saved = store.load(initial.id)!!
                        assertEquals((if (reported) 40L else 0L) + if (mode == "cancel") 0 else 40, saved.inputTokens)
                        assertEquals(1, saved.nativeRecord!!.snapshot().getJSONArray("messages").objects().count {
                            it.optString("toolCallId") == "call_pending|fc_pending"
                        })
                        assertEquals(1, saved.answers)
                    } finally { runtime.stop().join() }
                }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun nativeToolEnvelopesCannotEscalatePermissionsOrCommitRejectedCalls() = runBlocking {
        fun call(tool: String, id: String = "forbidden", namespace: String = "") = JSONObject()
            .put("type", "function_call").put("call_id", id).put("name", tool)
            .put("arguments", "{\"question\":\"Authored permission escalation attempt\"}")
            .apply { if (namespace.isNotEmpty()) put("namespace", namespace) }
        val rejected = listOf(
            JSONArray().put(call("research")) to "tool_denied",
            JSONArray().put(call("list_sessions")) to "tool_denied",
            JSONArray().put(call("list_propositions")) to "tool_denied",
            JSONArray().put(call("find")) to "tool_denied",
            JSONArray().put(call("visual")) to "tool_denied",
            JSONArray().put(call("spawn_agent")) to "tool_denied",
            JSONArray().put(call("read_file")) to "tool_denied",
            JSONArray().put(call("grant_permission")) to "tool_denied",
            JSONArray().put(call("probe_done")) to "tool_denied",
            JSONArray().put(call("ask_user_question", namespace = "foreign")) to "tool_denied",
            JSONArray().put(call("ask_user_question", namespace = RuntimeTools.NAMESPACE)) to "tool_denied",
            JSONArray().put(call("quiz", id = "call_pending")) to "tool_replay",
            JSONArray().put(call("ask_user_question")).put(call("ask_user_question")) to "tool_replay",
            JSONArray().put(call("ask_user_question")).put(call("quiz", id = "second").put("arguments", "{}")) to "invalid_tool_arguments",
            JSONArray().put(call("ask_user_question")).put(call("plan_init", id = "second")) to "multiple_tool_calls"
        )
        val name = "native-permissions-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                for ((output, reason) in rejected) {
                    val initial = fixture().copy(researchAllowed = true); store.save(initial)
                    var calls = 0
                    val runtime = SessionRuntime(app, store, ModelProvider { _, _ ->
                        calls++
                        JSONObject().put("status", "completed").put("output", output)
                            .put("usage", JSONObject().put("input_tokens", 40).put("output_tokens", 10))
                    }, researchFor = { _, _, _ -> error("Native research must not execute") })
                    try {
                        runtime.open(initial.id).join(); answer(runtime); runtime.send().join()
                        val failed = store.load(initial.id)!!
                        assertEquals(reason, runtime.state.value!!.error); assertEquals(1, calls)
                        assertEquals(40L, failed.inputTokens); assertEquals(10L, failed.outputTokens)
                        val messages = failed.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                        val failure = messages.last()
                        assertEquals("error", failure.getString("stopReason"))
                        assertTrue(failure.has("reasonedResponses"))
                        assertFalse(failure.getJSONArray("content").objects().any { it.optString("type") == "toolCall" })
                        assertEquals(1, messages.count { it.optString("toolCallId") == "call_pending|fc_pending" })
                        assertEquals(1, failed.nativeRecord.snapshot().getJSONArray("turns").length())
                        val bytes = failed.nativeRecord.raw
                        runtime.open(initial.id).join(); assertEquals(1, calls)
                        assertEquals(bytes, store.load(initial.id)!!.nativeRecord!!.raw)
                    } finally { runtime.stop().join() }
                }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun dontKnowKeepsLearnerThinkingAndAProviderCannotGrantItselfResearchPermission() = runBlocking {
        val name = "native-denied-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val initial = fixture().copy(researchAllowed = true); store.save(initial)
                var calls = 0
                var research = 0
                val runtime = SessionRuntime(app, store, ModelProvider { _, _ ->
                    calls++
                    JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
                        .put("type", "function_call").put("call_id", "forbidden_research").put("name", "research")
                        .put("arguments", "{\"question\":\"Authored forbidden tool fixture\"}")))
                }, researchFor = { _, _, _ -> research++; error("Native research must not execute") })
                try {
                    runtime.open(initial.id).join()
                    runtime.edit("Learner thought before choosing don't know", -1, 90, true, dontKnow = true)
                    runtime.send().join()
                    assertEquals(1, calls); assertEquals(0, research)
                    assertEquals("tool_denied", runtime.state.value!!.error)
                    val messages = store.load(initial.id)!!.nativeRecord!!.snapshot().getJSONArray("messages").objects()
                    val answer = messages.single { it.optString("toolCallId") == "call_pending|fc_pending" }
                    val details = answer.getJSONObject("details")
                    assertTrue(details.getBoolean("dontKnow")); assertFalse(details.has("confidence"))
                    assertEquals("Learner thought before choosing don't know", details.getString("thinking"))
                    assertTrue(answer.getJSONArray("content").getJSONObject(0).getString("text").contains("Learner thought"))
                    assertEquals("error", messages.last().getString("stopReason"))
                    assertTrue(messages.last().has("reasonedResponses"))
                    assertFalse(messages.any { it.optString("toolName") == "research" })
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test fun stopDoesNotResumeAnAbortedTurnAndUnsupportedProjectionRollsBackTheWholeCommit() = runBlocking {
        val name = "native-stop-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val initial = fixture(); store.save(initial)
                val started = CompletableDeferred<Unit>()
                var calls = 0
                val runtime = SessionRuntime(app, store, ModelProvider { _, delta ->
                    calls++; delta("Authored cancelled fragment"); started.complete(Unit); awaitCancellation()
                })
                try {
                    runtime.open(initial.id).join(); answer(runtime); runtime.send()
                    withTimeout(5000) { started.await() }
                    runtime.dismissQuestion("call_pending|fc_pending")
                    runtime.send(); runtime.retry()
                    assertEquals(1, calls) // Active foreground commands neither queue a second request nor dismiss a new question.
                    runtime.stop().join()
                    assertEquals("interrupted", runtime.state.value!!.status)
                    val saved = store.load(initial.id)!!
                    assertEquals("aborted", saved.nativeRecord!!.snapshot().getJSONArray("messages").objects().last().getString("stopReason"))
                    runtime.retry().join(); assertEquals(1, calls)
                    val unsupported = NativeChat.parse(saved.nativeRecord.snapshot().put("kind", "practice").toString())
                    try { store.saveNative(saved.copy(nativeRecord = unsupported, draft = "Must roll back")); fail("Unsupported projection committed") }
                    catch (expected: ProviderFailure) { assertEquals("native_runtime_unavailable", expected.reason) }
                    assertEquals(saved.nativeRecord.raw, store.load(initial.id)!!.nativeRecord!!.raw)
                    assertEquals(saved.nativeDisplayIds, store.load(initial.id)!!.nativeDisplayIds)
                    assertEquals(saved.draft, store.load(initial.id)!!.draft)
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }
}
