package com.kkgosu.reasoned

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.OpenAiProvider
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.readResponseStream
import com.kkgosu.reasoned.api.toolResult
import com.kkgosu.reasoned.controllers.AgentInstructions
import com.kkgosu.reasoned.controllers.RuntimeTools
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.fragments.HomeFragment
import com.kkgosu.reasoned.fragments.NewSessionFragment
import com.kkgosu.reasoned.fragments.ProviderSettingsFragment
import com.kkgosu.reasoned.fragments.RuntimeSessionFragment
import com.kkgosu.reasoned.model.LessonDisplayItem
import com.kkgosu.reasoned.model.ProbeAssessment
import com.kkgosu.reasoned.model.RuntimeQuestion
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.model.StudyPlan
import com.kkgosu.reasoned.model.passages
import com.kkgosu.reasoned.storage.CredentialStore
import com.kkgosu.reasoned.storage.ModelCatalog
import com.kkgosu.reasoned.storage.SessionStore
import com.kkgosu.reasoned.storage.knowledge
import com.kkgosu.reasoned.ui.adapters.LessonAdapter
import com.kkgosu.reasoned.ui.text.lessonMarkdown
import com.kkgosu.reasoned.ui.text.markdownBody
import com.kkgosu.reasoned.utils.objects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RuntimeTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()

    private fun response(
        text: String = "",
        tool: String? = null,
        args: JSONObject = JSONObject(),
        call: String = UUID.randomUUID().toString()
    ): JSONObject {
        val output = JSONArray()
        if (text.isNotEmpty()) output.put(
            JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
                .put(
                    "content",
                    JSONArray().put(JSONObject().put("type", "output_text").put("text", text))
                )
        )
        if (tool != null) output.put(
            JSONObject().put("type", "function_call").put("status", "completed")
                .put("call_id", call).put("name", tool).put("arguments", args.toString())
        )
        return JSONObject().put("status", "completed").put("output", output)
            .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5))
    }

    private suspend fun join(job: kotlinx.coroutines.Job) {
        withTimeout(8000) { job.join() }
    }

    private suspend fun edit(runtime: SessionRuntime, text: String, selection: Int = -1) {
        withContext(Dispatchers.Main) { runtime.edit(text, selection, 72, true) }
    }

    @Test
    fun fakeProviderRunsProbeRouteTeacherAndDurableAnswers() = runBlocking {
        val name = "runtime-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        var catalogFetches = 0
        val cacheConnection = "fixture-${UUID.randomUUID()}"
        try {
            val loader: suspend () -> List<Pair<String, String>> =
                { catalogFetches++; listOf("gpt-6.1-sol" to "GPT-6.1 Sol") }
            val first = app.modelCatalog.available(cacheConnection, fetch = loader)
            assertEquals(first, ModelCatalog(app).available(cacheConnection, fetch = loader))
            assertEquals(1, catalogFetches)
            assertEquals(
                first,
                app.modelCatalog.available(cacheConnection, refresh = true, fetch = loader)
            )
            assertEquals(2, catalogFetches)
        } finally {
            app.modelCatalog.forget(cacheConnection)
        }
        var calls = 0
        val provider = ModelProvider { request, delta ->
            assertFalse(request.getBoolean("store"))
            assertFalse(request.getBoolean("parallel_tool_calls"))
            assertEquals("high", request.getJSONObject("reasoning").getString("effort"))
            val result = when (++calls) {
                1 -> response(tool = "learning_context")
                2 -> response(
                    "Let's diagnose your reasoning.",
                    "ask_user_question",
                    JSONObject().put("question", "Why does subtraction move left?")
                        .put("details", "").put("multiSelect", false).put("options", JSONArray())
                )

                3 -> response(
                    tool = "finish_probe", args = JSONObject().put(
                        "assessment", ProbeAssessment(
                            listOf(
                                ProbeAssessment.Strand(
                                    "Number-line movement",
                                    "Floor: subtraction reverses addition. Ceiling: negative values uncertain. Build from one-step movement."
                                )
                            ),
                            "Subtraction is familiar; negative values remain uncertain."
                        ).json()
                    )
                )

                4 -> {
                    assertEquals(
                        AgentInstructions.compose(app.assets, "teacher"),
                        request.getString("instructions")
                    )
                    response(
                        tool = "plan_init",
                        args = StudyPlan(
                            listOf(
                                StudyPlan.Node(
                                    "number-line",
                                    "Number-line movement",
                                    "Predict one subtraction step"
                                )
                            ), emptyList()
                        ).json().apply { remove("version") })
                }

                5 -> response(
                    "Take one step left from two.",
                    "quiz",
                    JSONObject().put("question", "Where do you land, and why?")
                        .put("details", "").put("multiSelect", false)
                        .put(
                            "options",
                            JSONArray(listOf("One", "Three").map {
                                JSONObject().put("label", it).put("value", it)
                            })
                        )
                        .put("nodeId", "number-line").put("type", "check")
                        .put("correctAnswer", "One")
                        .put("explanation", "One step left from two is one.")
                )

                else -> response(
                    tool = "finish_lesson",
                    args = JSONObject().put(
                        "summary",
                        "Covered one-step subtraction; transfer is still untested."
                    )
                )
            }
            val text =
                result.getJSONArray("output").objects().filter { it.optString("type") == "message" }
                    .joinToString("") {
                        it.getJSONArray("content").getJSONObject(0).getString("text")
                    }
            text.chunked(7).forEach { delta(it) }
            result
        }
        val runtime = SessionRuntime(app, store, provider)
        try {
            join(runtime.create("Understand subtraction", "fake-model"))
            join(runtime.configure("gpt-5.2", "high"))
            assertEquals(0, calls) // Picking a model/effort never generates or switches billing.
            val id = runtime.state.value!!.id
            join(runtime.retry())
            assertEquals(2, calls)
            assertEquals("question", runtime.state.value!!.status)
            assertTrue(runtime.state.value!!.expanded)
            edit(runtime, "Subtracting one reverses adding one")
            store.writableDatabase.execSQL("CREATE TRIGGER deny_checkpoint BEFORE UPDATE ON sessions BEGIN SELECT RAISE(FAIL, 'fixture storage failure'); END")
            join(runtime.send())
            assertEquals("storage", runtime.failure.value)
            assertEquals(2, calls) // No provider request before a durable answer/checkpoint.
            assertEquals("question", runtime.state.value!!.status)
            assertEquals("Subtracting one reverses adding one", runtime.state.value!!.draft)
            store.writableDatabase.execSQL("DROP TRIGGER deny_checkpoint")
            join(runtime.send())
            assertEquals("plan", runtime.state.value!!.status)
            assertEquals("teacher", runtime.state.value!!.phase)
            assertEquals(0, runtime.state.value!!.answers)
            assertEquals(4, calls) // Probe handoff immediately prepares the first plan.
            assertNotNull(store.load(id)!!.assessment)
            assertEquals(
                "Number-line movement",
                store.load(id)!!.assessment!!.strands.single().strand
            )
            assertEquals(40L, runtime.state.value!!.inputTokens)
            assertEquals(20L, runtime.state.value!!.outputTokens)
            assertEquals(
                10L,
                runtime.state.value!!.contextTokens
            ) // Last input, not accumulated billing usage.
            assertEquals(80, runtime.state.value!!.confidence)
            val answer =
                runtime.state.value!!.items.single { it.role == LessonDisplayItem.Role.LEARNER }.text
            assertEquals(1, Regex("Subtracting one reverses adding one").findAll(answer).count())
            assertEquals(
                "Why does subtraction move left?",
                runtime.state.value!!.items.single { it.role == LessonDisplayItem.Role.QUESTION }.text
            )
            val saved = store.load(id)!!
            assertEquals(runtime.state.value, saved)
            store.writableDatabase.delete(
                "messages",
                "session=? AND role=?",
                arrayOf(id, "QUESTION")
            )
            val legacy = store.load(id)!!
            assertEquals(1, legacy.answerCards.size)
            assertEquals(
                "Why does subtraction move left?",
                legacy.displayItems.filterIsInstance<LessonDisplayItem.AnsweredDisplayItem>()
                    .single().question
            )
            assertTrue(
                legacy.displayItems.filterIsInstance<LessonDisplayItem.AnsweredDisplayItem>()
                    .single().correctAnswer.isEmpty()
            )
            store.save(saved)
            assertEquals("teacher", runtime.state.value!!.phase)
            assertEquals("plan", runtime.state.value!!.status)
            assertNotNull(store.load(id)!!.plan)
            assertTrue(store.knowledge().nodes.isEmpty())
            join(runtime.approveRoute())
            assertEquals("question", runtime.state.value!!.status)
            assertFalse(runtime.state.value!!.expanded)
            edit(runtime, "Two minus one is one", 0)
            join(runtime.send())
            assertEquals("complete", runtime.state.value!!.status)
            assertEquals(6, calls)
            assertEquals("", runtime.failure.value)
            store.readableDatabase.rawQuery(
                "SELECT count(*) FROM answers WHERE session=?",
                arrayOf(id)
            ).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            store.readableDatabase.rawQuery(
                "SELECT sum(input_tokens),sum(output_tokens) FROM runs WHERE session=?",
                arrayOf(id)
            ).use {
                it.moveToFirst(); assertEquals(60L, it.getLong(0)); assertEquals(30L, it.getLong(1))
            }
            SessionStore(app, name).use { reopened ->
                assertEquals(
                    runtime.state.value,
                    reopened.load(id)
                )
            }
        } finally {
            join(withContext(Dispatchers.Main) { runtime.stop() }); store.close(); app.deleteDatabase(
                name
            )
        }
    }

    @Test
    fun committedAnswersAndPublicThinkingRenderWithoutLeakingPendingKeys() {
        val question = RuntimeQuestion(
            "fixture-call",
            "Which direction?",
            listOf("Left", "Right"),
            "Left",
            "Subtracting moves left.",
            "probe"
        )
        val answer = JSONObject().put("answer", "Left").put("reasoning", "Reverse addition")
            .put("confidence_self_report", 80)
        val item = question.answered(4, answer)
        assertFalse(question.transcript().contains(question.explanation))
        assertEquals(item, LessonDisplayItem.restore(item.save()))
        val args =
            JSONObject().put("question", question.text).put("details", "").put("multiSelect", false)
                .put(
                    "options",
                    JSONArray(question.choices.map {
                        JSONObject().put("label", it).put("value", it)
                    })
                )
                .put("explanation", question.explanation)
                .put("correctAnswer", question.correctAnswer)
        val result = response(tool = "probe", args = args)
        result.getJSONArray("output").put(
            JSONObject().put("type", "reasoning").put("encrypted_content", "NEVER_RENDER_OPAQUE")
                .put(
                    "summary",
                    JSONArray().put(
                        JSONObject().put("type", "summary_text")
                            .put("text", "A public reasoning summary.")
                    )
                )
        )
        assertEquals(
            "A public reasoning summary.",
            RuntimeTools.validate(result, RuntimeSession(goal = "Fixture", model = "fake")).thinking
        )
        try {
            RuntimeTools.validate(
                response(
                    tool = "probe",
                    args = JSONObject(args.toString()).put("correctAnswer", "Unknown option")
                ), RuntimeSession(goal = "Fixture", model = "fake")
            )
            fail("Invalid answer key accepted")
        } catch (error: ProviderFailure) {
            assertEquals("invalid_answer_key", error.reason)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val adapter = LessonAdapter(lessonMarkdown(activity))
                adapter.submit(listOf(item))
                val parent = android.widget.FrameLayout(activity)
                val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(0))
                adapter.onBindViewHolder(holder, 0)
                assertTrue(
                    holder.itemView.findViewById<android.widget.TextView>(R.id.answered_label).text.contains(
                        "CORRECT"
                    )
                )
                assertEquals(
                    View.GONE,
                    holder.itemView.findViewById<android.widget.TextView>(R.id.answered_why).markdownBody.visibility
                )
                holder.itemView.findViewById<View>(R.id.answered_why_toggle).performClick()
                assertEquals(
                    View.VISIBLE,
                    holder.itemView.findViewById<android.widget.TextView>(R.id.answered_why).markdownBody.visibility
                )
                assertEquals(
                    80,
                    holder.itemView.findViewById<android.widget.ProgressBar>(R.id.answered_confidence).progress
                )
                val check = question.copy(phase = "teacher", type = "check")
                val gap = check.answered(
                    item.id,
                    check.answer("Question thinking", -1, 60, "Reading thinking", true)
                )
                assertEquals(gap, LessonDisplayItem.restore(gap.save()))
                adapter.submit(listOf(gap)); adapter.onBindViewHolder(holder, 0)
                val label =
                    holder.itemView.findViewById<android.widget.TextView>(R.id.answered_label).text.toString()
                assertTrue(label.contains(activity.getString(R.string.runtime_dont_know)))
                assertFalse(label.contains("INCORRECT"))
                assertEquals(
                    View.GONE,
                    holder.itemView.findViewById<View>(R.id.answered_confidence).visibility
                )
                assertEquals(
                    View.GONE,
                    holder.itemView.findViewById<View>(R.id.answered_confidence_label).visibility
                )
                assertEquals(
                    View.GONE,
                    holder.itemView.findViewById<android.widget.TextView>(R.id.answered_reading).markdownBody.visibility
                )
                holder.itemView.findViewById<View>(R.id.answered_reading_toggle).performClick()
                assertEquals(
                    "Reading thinking",
                    holder.itemView.findViewById<android.widget.TextView>(R.id.answered_reading).text.toString()
                )
                assertEquals(
                    View.VISIBLE,
                    holder.itemView.findViewById<android.widget.TextView>(R.id.answered_reading).markdownBody.visibility
                )
                assertEquals(
                    "Question thinking",
                    holder.itemView.findViewById<android.widget.TextView>(R.id.answered_thinking).text.toString()
                )
                val home =
                    activity.fragmentManager.fragments.filterIsInstance<HomeFragment>().single()
                home.invalidateOptionsMenu()
                assertFalse(
                    activity.findViewById<android.widget.Toolbar>(me.grishka.appkit.R.id.toolbar).menu.findItem(
                        R.id.graph_stop_update
                    ).isVisible
                )
            }
        }
    }

    @Test
    fun legacySessionsAdoptOriginalInstructionsWithoutLosingTheirTranscript() = runBlocking {
        val name = "prompt-migration-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        val legacy = RuntimeSession(
            goal = "Understand subtraction", model = "fake-model", promptVersion = "1",
            protocol = JSONArray().put(
                JSONObject().put("role", "user").put("content", "Keep my original question")
            ).toString()
        )
        store.save(legacy)
        val runtime = SessionRuntime(app, store, ModelProvider { request, _ ->
            assertEquals(
                AgentInstructions.compose(app.assets, "probe"),
                request.getString("instructions")
            )
            assertEquals(
                "Keep my original question",
                request.getJSONArray("input").getJSONObject(0).getString("content")
            )
            response("What part needs clarification?")
        })
        try {
            join(runtime.open(legacy.id))
            join(runtime.retry())
            assertEquals("", runtime.failure.value)
            assertEquals(AgentInstructions.VERSION, store.load(legacy.id)!!.promptVersion)
            assertEquals("idle", runtime.state.value!!.status)
        } finally {
            join(withContext(Dispatchers.Main) { runtime.stop() }); store.close(); app.deleteDatabase(
                name
            )
        }
    }

    @Test
    fun cancellationAndMalformedToolsNeverCommitAQuestionOrInventCompletion() = runBlocking {
        val name = "runtime-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        var mode = 0
        val runtime = SessionRuntime(app, store, ModelProvider { _, delta ->
            if (mode == 0) {
                try {
                    delta("Provisional text"); entered.complete(Unit); awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            response(tool = "execute_shell", args = JSONObject().put("command", "not allowed"))
        })
        try {
            join(runtime.create("A bounded goal", "fake-model"))
            val id = runtime.state.value!!.id
            val running = runtime.retry()
            withTimeout(5000) { entered.await() }
            withContext(Dispatchers.Main) { runtime.stop() }
            join(running)
            assertTrue(cancelled)
            assertEquals("interrupted", runtime.state.value!!.status)
            assertTrue(runtime.state.value!!.items.last().text.contains("Interrupted response"))
            assertFalse(runtime.state.value!!.protocol.contains("Provisional text"))
            assertEquals(runtime.state.value, store.load(id))
            mode = 1
            join(runtime.retry())
            assertEquals("error", runtime.state.value!!.status)
            assertEquals("tool_denied", runtime.state.value!!.error)
            assertNull(runtime.state.value!!.question)
            assertFalse(runtime.state.value!!.protocol.contains("execute_shell"))
            val interrupted = runtime.state.value!!.copy(status = "running")
            store.save(interrupted)
            store.run("killed-run", interrupted, "running")
            store.recover()
            assertEquals("interrupted", store.load(id)!!.status)
        } finally {
            join(withContext(Dispatchers.Main) { runtime.stop() }); store.close(); app.deleteDatabase(
                name
            )
        }
    }

    @Test
    fun validatesToolSchemasReplayStepBudgetsAndSseCompletion() = runBlocking {
        val session = RuntimeSession(goal = "Test", model = "fake-model")
        fun rejected(body: JSONObject) {
            try {
                RuntimeTools.validate(body, session); fail("Malformed model output accepted")
            } catch (_: ProviderFailure) {
            }
        }
        rejected(
            response(
                tool = "ask_user_question",
                args = JSONObject().put("question", "?").put("details", "")
                    .put("multiSelect", false)
                    .put(
                        "options",
                        JSONArray().put(
                            JSONObject().put("label", "Only one").put("value", "one")
                                .put("description", "")
                        )
                    )
            )
        )
        rejected(
            response(
                tool = "ask_user_question",
                args = JSONObject().put("question", "?").put("details", "")
                    .put("multiSelect", false).put("options", JSONArray()).put("extra", true)
            )
        )
        rejected(
            response(
                tool = "finish_probe",
                args = JSONObject().put("summary", "No evidence")
                    .put("steps", JSONArray().put("Guess"))
            )
        )
        rejected(response(tool = "finish_lesson", args = JSONObject().put("summary", "Wrong role")))
        val duplicate = response(tool = "learning_context", call = "same-call")
        try {
            RuntimeTools.validate(
                duplicate,
                session.copy(protocol = JSONArray().put(toolResult("same-call", "done")).toString())
            ); fail("Replay accepted")
        } catch (error: ProviderFailure) {
            assertEquals("tool_replay", error.reason)
        }
        val valid = response("Hi")
        val source = Buffer().writeUtf8(
            ": heartbeat\n\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\ndata: " +
                    JSONObject().put("type", "response.completed").put("response", valid) + "\n\n"
        )
        var text = ""
        assertEquals(valid.toString(), readResponseStream(source) { text += it }.toString())
        assertEquals("Hi", text)
        for (broken in listOf(
            "data: {}\n\n",
            "data: not-json\n\n",
            "data: [DONE]\n\n",
            "data: {\"type\":\"response.incomplete\"}\n\n",
            "data: " + "x".repeat(262_145)
        )) {
            try {
                readResponseStream(Buffer().writeUtf8(broken)) { }; fail("Broken stream accepted")
            } catch (_: java.io.IOException) {
            }
        }
        val name = "runtime-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        var calls = 0
        val runtime = SessionRuntime(
            app,
            store,
            ModelProvider { _, _ -> calls++; response(tool = "learning_context") })
        try {
            join(runtime.create("Step limit", "fake-model")); join(runtime.retry())
            assertEquals(8, calls)
            assertEquals("step_limit", runtime.state.value!!.error)
        } finally {
            join(withContext(Dispatchers.Main) { runtime.stop() }); store.close(); app.deleteDatabase(
                name
            )
        }
    }

    @Test
    fun sseUsesFinishedItemsAndBackfillsOpaqueReasoningBeforeCommit() = runBlocking {
        val reasoning = JSONObject().put("type", "reasoning").put("id", "rs_fixture")
            .put("summary", JSONArray())
        val message = response("Canonical final text").getJSONArray("output").getJSONObject(0)
            .apply { remove("status") }
        val terminal = JSONObject().put("status", "completed").put(
            "output", JSONArray().put(
                JSONObject(reasoning.toString()).put("encrypted_content", "opaque-fixture")
            )
        )
        val events = listOf(
            JSONObject().put("type", "response.output_text.delta").put("delta", "Provisional text"),
            JSONObject().put("type", "response.output_item.done").put("output_index", 0)
                .put("item", reasoning),
            JSONObject().put("type", "response.output_item.done").put("output_index", 1)
                .put("item", message),
            JSONObject().put("type", "response.completed").put("response", terminal)
        )
        val wire = events.joinToString("") { "data: $it\n\n" }
        val assembled = readResponseStream(Buffer().writeUtf8(wire)) { }
        assertEquals(2, assembled.getJSONArray("output").length())
        assertEquals(
            "opaque-fixture",
            assembled.getJSONArray("output").getJSONObject(0).getString("encrypted_content")
        )
        assertEquals(
            "Canonical final text",
            RuntimeTools.validate(
                assembled,
                RuntimeSession(goal = "Fixture", model = "fake-model")
            ).text
        )
        val name = "assembled-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        val runtime = SessionRuntime(
            app,
            store,
            ModelProvider { _, delta -> delta("Provisional text"); assembled })
        try {
            val session = RuntimeSession(
                goal = "Fixture",
                model = "fake-model",
                phase = "teacher",
                proseIds = emptySet()
            )
            store.save(session)
            join(runtime.open(session.id)); join(runtime.retry())
            assertEquals("idle", runtime.state.value!!.status)
            assertEquals("Canonical final text", runtime.state.value!!.items.last().text)
            assertEquals(
                setOf(runtime.state.value!!.items.last().id),
                runtime.state.value!!.proseIds
            )
            assertEquals("Canonical final text", runtime.state.value!!.passages().single().text)
        } finally {
            withContext(Dispatchers.Main) { runtime.stop() }.join(); store.close(); app.deleteDatabase(
                name
            )
        }
        val started = JSONObject().put("type", "response.output_item.added").put("output_index", 0)
            .put("item", message)
        val completed = JSONObject().put("type", "response.completed")
            .put("response", response("Must not commit"))
        try {
            readResponseStream(Buffer().writeUtf8("data: $started\n\ndata: $completed\n\n")) { }; fail(
                "Unfinished item accepted"
            )
        } catch (error: ProviderFailure) {
            assertEquals("incomplete", error.reason)
        }
    }

    @Test
    fun httpAdapterUsesFixedEndpointAndSanitizesErrorsWithoutNetwork() = runBlocking {
        val credentials = CredentialStore(app, "transport-${UUID.randomUUID()}")
        val fake = "test-only-not-a-live-api-key-123456789"
        credentials.save(fake)
        var status = 200
        var requests = 0
        var contentType: String? = "text/event-stream"
        val completed = response("Local transport fixture")
        val stream =
            "\uFEFF: heartbeat\r\ndata\r\n\r\nretry: 1000\r\nid: fixture\r\nevent: response.output_text.delta\r\ndata: " +
                    JSONObject().put("type", "response.output_text.delta")
                        .put("delta", "Local transport fixture") +
                    "\r\n\r\nevent: response.completed\r\ndata: " + JSONObject().put(
                "type",
                "response.completed"
            ).put("response", completed) + "\r\n\r\n"
        var payload = stream
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("https://api.openai.com/v1/responses", chain.request().url.toString())
            assertEquals("Bearer $fake", chain.request().header("Authorization"))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(status).message("Fixture")
                .apply { contentType?.let { header("Content-Type", it) } }
                .body((if (status == 200) payload else fake).toResponseBody()).build()
        }.build()
        try {
            val provider = OpenAiProvider(credentials, client)
            for (mime in listOf(
                "text/event-stream",
                "TEXT/EVENT-STREAM; charset=utf-8",
                "text/plain; charset=utf-8",
                "application/octet-stream",
                "application/json",
                null
            )) {
                contentType = mime
                var text = ""
                assertEquals(
                    completed.toString(),
                    provider.respond(JSONObject()) { text += it }.toString()
                )
                assertEquals("Local transport fixture", text)
            }
            contentType = "text/event-stream"
            for (invalid in listOf(completed.toString() + "\n\n", "<!DOCTYPE html>\n" + stream)) {
                payload = invalid
                try {
                    provider.respond(JSONObject()) { }; fail("Non-SSE body accepted")
                } catch (error: ProviderFailure) {
                    assertEquals("protocol", error.reason)
                }
            }
            status = 401
            try {
                provider.respond(JSONObject()) { }; fail("Unauthorized response accepted")
            } catch (error: ProviderFailure) {
                assertEquals("unauthorized", error.reason); assertFalse(
                    error.toString().contains(fake)
                )
            }
            assertEquals(9, requests)
        } finally {
            credentials.forget(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test
    fun cancellingHttpStreamCancelsCallAndClosesBlockedBody() = runBlocking {
        val credentials = CredentialStore(app, "cancel-${UUID.randomUUID()}")
        credentials.save("test-only-not-a-live-api-key-123456789")
        val entered = CompletableDeferred<Unit>()
        val cancelled = java.util.concurrent.CountDownLatch(1)
        var closed = false
        val source = object : okio.Source {
            override fun timeout() = okio.Timeout.NONE
            override fun close() {
                closed = true
            }

            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.complete(Unit)
                if (!cancelled.await(
                        5,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                ) throw java.io.IOException("Test cancellation timed out")
                throw java.io.IOException("Test call cancelled")
            }
        }.buffer()
        val client = okhttp3.OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
            override fun canceled(call: okhttp3.Call) {
                cancelled.countDown()
            }
        }).addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("Fixture")
                .header("Content-Type", "text/event-stream").body(object : okhttp3.ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength() = -1L
                    override fun source() = source
                }).build()
        }.build()
        try {
            val running = launch { OpenAiProvider(credentials, client).respond(JSONObject()) { } }
            withTimeout(5000) { entered.await() }
            running.cancel()
            withTimeout(5000) { running.join() }
            assertEquals(0L, cancelled.count)
            assertTrue("Cancellation must close the response body", closed)
        } finally {
            credentials.forget(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test
    fun keystoreCiphertextDoesNotContainKeyAndInvalidationRequiresReset() {
        val name = "test-${UUID.randomUUID()}"
        val credentials = CredentialStore(app, name)
        val fake = "test-only-not-a-live-api-key-123456789"
        try {
            credentials.save(fake)
            assertEquals(fake, credentials.read())
            assertFalse(
                File(app.noBackupFilesDir, "$name.credential").readBytes()
                    .toString(Charsets.ISO_8859_1).contains(fake)
            )
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keystore.deleteEntry("reasoned.$name.v1")
            try {
                credentials.read(); fail("Missing Keystore key accepted")
            } catch (error: ProviderFailure) {
                assertEquals("credential_unavailable", error.reason)
            }
            credentials.forget()
            assertNull(credentials.read())
            credentials.save(fake)
            assertEquals(fake, credentials.read())
        } finally {
            credentials.forget()
        }
    }

    @Test
    fun setupProtectsKeysAndStartsNewSessionOnce() = runBlocking {
        val name = "start-ui-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val runtime = SessionRuntime(app, store, ModelProvider { _, _ ->
            calls.incrementAndGet()
            response(
                tool = "ask_user_question",
                args = JSONObject().put("question", "Why choose a small step?").put("details", "")
                    .put("multiSelect", false).put("options", JSONArray())
            )
        })
        val delegate = ReasonedApplication::class.java.getDeclaredField("runtimeDelegate")
            .apply { isAccessible = true }
        val previous = delegate.get(app)
        val preferences = app.getSharedPreferences("provider", 0)
        val wasPlan = preferences.getBoolean("chatgpt", false)
        val oldModel = preferences.getString("model:api", null)
        try {
            delegate.set(
                app,
                lazyOf(runtime)
            ) // No real credentials, account or inference requests.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    me.grishka.appkit.Nav.go(
                        it,
                        ProviderSettingsFragment::class.java,
                        null
                    )
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                    val key = activity.findViewById<EditText>(R.id.runtime_key)
                    assertFalse(key.isSaveEnabled)
                    key.setText("test-only-unsaved-not-a-real-key")
                }
                scenario.recreate()
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    assertEquals(
                        "",
                        activity.findViewById<EditText>(R.id.runtime_key).text.toString()
                    )
                    activity.findViewById<android.widget.RadioGroup>(R.id.runtime_billing)
                        .check(R.id.runtime_use_api)
                    activity.findViewById<EditText>(R.id.runtime_model).setText("fake-model")
                    activity.findViewById<Button>(R.id.runtime_save_model).performClick()
                }
                withTimeout(5000) {
                    while (preferences.getString(
                            "model:api",
                            ""
                        ) != "fake-model"
                    ) kotlinx.coroutines.delay(20)
                }
                scenario.onActivity { it.showFragmentClearingBackStack(NewSessionFragment()) }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.runtime_goal)
                        .setText("Understand why a small downhill step helps")
                    val start = activity.findViewById<Button>(R.id.runtime_start)
                    assertTrue(start.isEnabled)
                    assertEquals("Start session", start.text.toString())
                    start.performClick()
                }
                withTimeout(8000) { runtime.state.first { it?.status == "question" } }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                val id = runtime.state.value!!.id
                scenario.onActivity { activity ->
                    assertEquals(
                        "Why choose a small step?",
                        activity.findViewById<android.widget.TextView>(R.id.question_text).text.toString()
                    )
                    assertEquals(
                        0,
                        activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE
                    )
                }
                assertEquals(1, calls.get())
                scenario.recreate()
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { it.openSession(id) }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                assertEquals(1, calls.get()) // Restoration/reopening never starts another request.

                val idle = RuntimeSession(goal = "Existing local session", model = "fake-model")
                store.save(idle)
                scenario.onActivity { it.openSession(idle.id) }
                withTimeout(5000) { runtime.state.first { it?.id == idle.id } }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    val composer = activity.findViewById<EditText>(R.id.lesson_composer)
                    val action = activity.findViewById<Button>(R.id.composer_action)
                    val row = action.parent as android.widget.LinearLayout
                    assertEquals(
                        android.view.Gravity.TOP,
                        composer.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK
                    )
                    assertFalse(row.isBaselineAligned)
                    assertTrue(
                        "Action must fit inside its row",
                        action.top >= 0 && action.bottom <= row.height
                    )
                    val bounds = android.graphics.Rect()
                    assertTrue(
                        "Start must not be clipped",
                        action.getGlobalVisibleRect(bounds) && bounds.height() == action.height
                    )
                    assertEquals(activity.getString(R.string.runtime_start), action.text.toString())
                }
                assertEquals(1, calls.get())
            }
        } finally {
            join(withContext(Dispatchers.Main) { runtime.stop() })
            delegate.set(app, previous)
            preferences.edit().putBoolean("chatgpt", wasPlan).putString("model:api", oldModel)
                .commit()
            store.close(); app.deleteDatabase(name)
        }
    }

    @Test
    fun scrollButtonNotesAndAnswersReturnFromHistoryToLatestMessage() = runBlocking {
        val name = "scroll-ui-${UUID.randomUUID()}.db"
        val store = SessionStore(app, name)
        val runtime = SessionRuntime(app, store, ModelProvider { _, _ ->
            response(tool = "ask_user_question", args = JSONObject()
                .put("question", "Offline follow-up?").put("details", "")
                .put("multiSelect", false).put("options", JSONArray()))
        })
        val delegate = ReasonedApplication::class.java.getDeclaredField("runtimeDelegate")
            .apply { isAccessible = true }
        val previous = delegate.get(app)
        try {
            delegate.set(app, lazyOf(runtime))
            val session = RuntimeSession(goal = "Scroll fixture", model = "fake-model", phase = "teacher",
                items = (0L..39L).map { LessonDisplayItem.TextDisplayItem(it, "Offline history paragraph $it.") })
            store.save(session)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.openSession(session.id) }
                withTimeout(5000) { runtime.state.first { it?.id == session.id } }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                for (action in listOf("button", "note", "answer")) {
                    scenario.onActivity { activity ->
                        activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.lesson_list).scrollToPosition(0)
                    }
                    InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.lesson_list)
                        assertTrue("History must stay scrollable before sending", list.canScrollVertically(1))
                        assertNotNull(list.foreground)
                        assertTrue(list.paddingBottom >= activity.resources.getDimensionPixelSize(R.dimen.space_32))
                        val button = activity.findViewById<View>(R.id.scroll_to_bottom)
                        assertEquals(if (activity.findViewById<View>(R.id.question_panel).visibility == View.VISIBLE)
                            View.INVISIBLE else View.VISIBLE, button.visibility)
                        assertTrue(button.width >= activity.resources.getDimensionPixelSize(R.dimen.touch_target))
                        if (action == "button") button.performClick()
                        else {
                            activity.findViewById<EditText>(if (action == "answer") R.id.question_thinking else R.id.lesson_composer)
                                .setText(if (action == "answer") "Offline answer" else "Offline note")
                            activity.findViewById<Button>(R.id.composer_action).performClick()
                        }
                    }
                    withTimeout(8000) {
                        if (action != "button") runtime.state.first {
                            it?.status == "question" && it.answers == if (action == "answer") 1 else 0
                        }
                        var atBottom = false
                        while (!atBottom) {
                            scenario.onActivity { activity ->
                                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.lesson_list)
                                atBottom = !list.canScrollVertically(1) && list.scrollState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE
                            }
                            if (!atBottom) kotlinx.coroutines.delay(20)
                        }
                    }
                    scenario.onActivity { activity ->
                        assertEquals(View.INVISIBLE, activity.findViewById<View>(R.id.scroll_to_bottom).visibility)
                    }
                }
            }
        } finally {
            join(withContext(Dispatchers.Main) { runtime.stop() })
            delegate.set(app, previous)
            store.close(); app.deleteDatabase(name)
        }
    }

    @Test
    fun inlineMathKeepsItsBaselineWhenLineSpacingChanges() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val renderer = lessonMarkdown(app)
            val formatted = renderer.toMarkdown("Before \$A=(130,140)\$: after.\n\n\$\$\nx^2\n\$\$")
            val originals = formatted.getSpans(
                0,
                formatted.length,
                io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan::class.java
            )
            assertEquals(2, originals.size)
            val view = android.widget.TextView(app)
            renderer.setParsedMarkdown(view, formatted)
            val text = view.text as android.text.Spanned
            val spans =
                text.getSpans(0, text.length, io.noties.markwon.image.AsyncDrawableSpan::class.java)
            val inline = spans.single { it !is io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan }
            val block = spans.single { it is io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan }
            assertTrue(originals.any { it === block }) // Display math must keep the library's block layout.
            assertTrue(originals.any { it.drawable === inline.drawable }) // Preserve the existing loader/lifecycle.
            val start = text.getSpanStart(inline)
            val end = text.getSpanEnd(inline)
            for (size in listOf(32f, 64f)) for (formula in listOf(
                "A=(130,140)",
                "\\frac{x^2}{y_1}"
            )) {
                val paint = android.graphics.Paint().apply { textSize = size }
                val math = ru.noties.jlatexmath.JLatexMathDrawable.builder(formula).textSize(size)
                    .color(app.getColor(R.color.foreground)).build()
                for (width in listOf(1000, math.intrinsicWidth / 2)) {
                    inline.drawable.setResult(math)
                    inline.drawable.initWithKnownDimensions(width, size)
                    val fm = android.graphics.Paint.FontMetricsInt()
                    inline.getSize(paint, text, start, end, fm)
                    val baseline = math.icon().baseLine * inline.drawable.bounds.height()
                    assertTrue(fm.ascent <= -baseline)
                    assertTrue(fm.descent >= inline.drawable.bounds.height() - baseline)
                    assertTrue(fm.top <= fm.ascent); assertTrue(fm.bottom >= fm.descent)
                    val a = android.graphics.Bitmap.createBitmap(
                        width,
                        500,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val b = android.graphics.Bitmap.createBitmap(
                        width,
                        500,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    try {
                        inline.draw(
                            android.graphics.Canvas(a),
                            text,
                            start,
                            end,
                            0f,
                            0,
                            200,
                            300,
                            paint
                        )
                        inline.draw(
                            android.graphics.Canvas(b),
                            text,
                            start,
                            end,
                            0f,
                            0,
                            200,
                            480,
                            paint
                        )
                        assertTrue(
                            "Extra line spacing must not move the formula below surrounding text",
                            a.sameAs(b)
                        )
                    } finally {
                        a.recycle(); b.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun nativeQuestionAndDraftSurviveRecreationWithoutCallingOpenAi() {
        val renderer = lessonMarkdown(app)
        val formatted = renderer.toMarkdown("**Explain** why \$x\$ moves.")
        assertEquals("Explain why x moves.", formatted.toString())
        assertTrue(
            formatted.getSpans(
                0,
                formatted.length,
                io.noties.markwon.image.AsyncDrawableSpan::class.java
            ).isNotEmpty()
        )
        assertEquals("Cost \$5", renderer.toMarkdown("Cost \$5").toString())
        val code = renderer.toMarkdown("`\$x\$`")
        assertEquals("\$x\$", code.toString().trim())
        assertTrue(
            code.getSpans(
                0,
                code.length,
                io.noties.markwon.image.AsyncDrawableSpan::class.java
            ).isEmpty()
        )
        assertNull(app.modelCatalog["unknown-fixture"])
        val sol = requireNotNull(app.modelCatalog["gpt-6.1-sol"])
        assertTrue(sol.window > 0)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), sol.efforts)
        assertNull(app.modelCatalog["chat:gpt-6.1-sol"])
        val transcript = LessonAdapter(renderer)
        val rows = listOf(LessonDisplayItem.TextDisplayItem(1, "First message"))
        transcript.submit(rows, running = true)
        assertEquals(2, transcript.itemCount)
        val footerId = transcript.getItemId(1)
        val streaming = rows + LessonDisplayItem.TextDisplayItem(2, "Answer delta")
        transcript.submit(streaming, running = true)
        assertEquals(footerId, transcript.getItemId(2))
        assertEquals(R.layout.item_lesson_generating, transcript.getItemViewType(2))
        transcript.submit(streaming)
        assertEquals(2, transcript.itemCount)
        val question = RuntimeQuestion(
            "ui-${UUID.randomUUID()}",
            "**Explain** why the step works.",
            emptyList()
        )
        val session = RuntimeSession(
            goal = "A local UI fixture",
            model = "fake-model",
            status = "question",
            question = question,
            items = listOf(
                LessonDisplayItem.TextDisplayItem(
                    1,
                    "A locally seeded lesson",
                    LessonDisplayItem.Role.TITLE
                )
            ),
            draft = "Preserved reasoning"
        )
        app.sessions.save(session)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                me.grishka.appkit.Nav.go(
                    activity,
                    RuntimeSessionFragment::class.java,
                    Bundle().apply { putString("session", session.id) })
            }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
            scenario.onActivity { activity ->
                assertEquals(
                    "Preserved reasoning",
                    activity.findViewById<EditText>(R.id.question_thinking).text.toString()
                )
                assertEquals(
                    "Explain why the step works.",
                    activity.findViewById<android.widget.TextView>(R.id.question_text).text.toString()
                )
                assertEquals(
                    View.GONE,
                    activity.findViewById<View>(R.id.question_options).visibility
                )
                assertTrue(activity.findViewById<Button>(R.id.composer_action).isEnabled)
                assertEquals(
                    5,
                    activity.findViewById<android.widget.SeekBar>(R.id.question_confidence).max
                )
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.session_model).visibility
                )
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.session_thinking).visibility
                )
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
            scenario.onActivity { activity ->
                assertEquals(
                    "Preserved reasoning",
                    activity.findViewById<EditText>(R.id.question_thinking).text.toString()
                )
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                scenario.onActivity { activity ->
                    activity.findViewById<android.widget.ScrollView>(R.id.question_scroll)
                        .fullScroll(View.FOCUS_DOWN)
                }
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                val keyboardShown = java.util.concurrent.CountDownLatch(1)
                lateinit var listener: android.view.ViewTreeObserver.OnGlobalLayoutListener
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                        if (decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true) keyboardShown.countDown()
                    }
                    decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
                    val editor = activity.findViewById<EditText>(R.id.question_thinking)
                    assertTrue("Editor must be focusable", editor.requestFocus())
                    editor.post { activity.window.insetsController?.show(android.view.WindowInsets.Type.ime()) }
                }
                assertTrue(
                    "Wait for an actual IME inset, not just main-thread idle",
                    keyboardShown.await(5, java.util.concurrent.TimeUnit.SECONDS)
                )
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3000)
                scenario.onActivity { activity ->
                    activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                    val insets = activity.window.decorView.rootWindowInsets
                    assertTrue(
                        "Requested IME must become visible",
                        insets.isVisible(android.view.WindowInsets.Type.ime())
                    )
                    assertEquals(
                        View.VISIBLE,
                        activity.findViewById<View>(R.id.lesson_list).visibility
                    )
                    val editor = activity.findViewById<EditText>(R.id.question_thinking)
                    val visible = android.graphics.Rect()
                    assertTrue(editor.getGlobalVisibleRect(visible) && visible.height() >= editor.lineHeight)
                    val submit = activity.findViewById<Button>(R.id.composer_action)
                    val location = IntArray(2)
                    submit.getLocationOnScreen(location)
                    val keyboardTop =
                        activity.window.decorView.height - insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    assertTrue(location[1] + submit.height <= keyboardTop)
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(editor.windowToken, 0)
                }
            }
        }
    }
}
