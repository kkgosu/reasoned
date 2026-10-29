package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.NativeResponses
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.chatGptPayload
import com.kkgosu.reasoned.controllers.RuntimeTools
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeTeaching
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NativeResponsesTest {
    private val fixtures = sequenceOf(File("../fixtures/learnharness/v1"), File("fixtures/learnharness/v1")).first { it.isDirectory }
    private fun chat(through: String): NativeChat {
        val root = JSONObject(File(fixtures, "data/chats/chat_contract_teach.json").readText())
        val messages = root.getJSONArray("messages").objects()
        return NativeChat.parse(root.put("messages", JSONArray(messages.take(messages.indexOfFirst { it.getString("id") == through } + 1))).toString())
    }
    private fun session(chat: NativeChat): RuntimeSession = NativeTeaching.project(RuntimeSession(
        id = chat.id, goal = "Authored offline fixture", model = "fixture", nativeRecord = chat,
        nativeDisplayIds = chat.parts().mapIndexed { i, part -> part to i + 1L }.toMap()))

    @Test fun nativeTeachingCheckpointsProjectOriginalIdentitiesAndKeepCommitmentsOutOfPendingRows() {
        for (through in listOf("m_predict", "m_predict_answer", "m_explain", "m_explain_answer", "m_compaction")) {
            val native = chat(through)
            val before = native.raw
            val s = session(native)
            assertEquals("teacher", s.phase)
            assertEquals("[]", s.protocol)
            assertEquals(native.state().pending?.id, s.question?.callId)
            assertEquals(native.state().pending?.let { "question" } ?: "idle", s.status)
            assertEquals(s.items.size, s.items.map { it.id }.distinct().size)
            assertTrue(s.items.drop(1).all { it.id in s.nativeDisplayIds.values })
            if (through == "m_explain") {
                assertFalse(s.expanded)
                assertFalse(s.items.any { it.text.contains(s.question!!.correctAnswer) })
                val sent = s.nativeDisplayIds.getValue(NativeChat.Part("m_teach", 2))
                assertTrue(s.proseIds?.contains(sent) == true)
                val rejected = native.snapshot()
                rejected.getJSONArray("messages").objects().first { it.getString("id") == "m_prose_result" }.put("isError", true)
                assertFalse(session(NativeChat.parse(rejected.toString())).items.any { it.id == sent })
            }
            assertEquals(before, native.raw)
        }
        val probing = session(chat("m_probe"))
        assertEquals("probe", probing.phase)
        assertEquals("question", probing.status)
        assertTrue(probing.expanded)
        val unknown = NativeResponses.append(chat("m_predict_answer"), JSONObject("""{"id":"unknown_call","role":"assistant",
            "content":[{"type":"toolCall","id":"unknown_job","name":"spawn_job","arguments":{"fixture":true}}]}"""), 1000)
        val raw = unknown.raw
        assertEquals("native_runtime_unavailable", assertThrows(ProviderFailure::class.java) { session(unknown) }.reason)
        assertEquals(raw, unknown.raw)
    }

    @Test fun settledHistoricalGraphReadsReplayWithoutExecutionOrBecomingLearnerEvidence() {
        for (name in listOf("list_sessions", "list_propositions", "find")) {
            val root = chat("m_predict").snapshot()
            val messages = root.getJSONArray("messages").objects()
            val call = JSONObject().put("id", "historical_read_call").put("type", "toolCall")
                .put("name", name).put("arguments", JSONObject())
            val owner = JSONObject(messages.first { it.optString("role") == "assistant" }.toString())
                .put("id", "historical_read_owner").put("content", JSONArray().put(call))
            val result = JSONObject().put("id", "historical_read_result").put("role", "toolResult")
                .put("toolCallId", call.getString("id")).put("toolName", name).put("timestamp", 1000)
                .put("isError", false).put("content", JSONArray().put(JSONObject().put("type", "text")
                    .put("text", "AUTHORED_READ_RESULT_NOT_TEACHER_PROSE")))
                .put("details", JSONObject().put("result", "opaque read metadata"))
            fun archive(includeResult: Boolean = true) = NativeChat.parse(JSONObject(root.toString())
                .put("messages", JSONArray().put(messages.first()).put(owner).apply { if (includeResult) put(result) }
                    .apply { messages.drop(1).forEach { put(it) } }).toString())
            val native = archive()
            val restored = session(native)
            assertFalse(restored.items.any { it.text.contains("AUTHORED_READ_RESULT_NOT_TEACHER_PROSE") })
            assertEquals(session(chat("m_predict")).answers, restored.answers)
            assertTrue(NativeResponses.input(native, "fixture", "api").toString().contains("AUTHORED_READ_RESULT_NOT_TEACHER_PROSE"))
            assertEquals(native.raw, restored.nativeRecord!!.raw)
            assertEquals("native_runtime_unavailable", assertThrows(ProviderFailure::class.java) { session(archive(false)) }.reason)
            val duplicate = archive().snapshot()
            duplicate.getJSONArray("messages").put(JSONObject(result.toString()).put("id", "duplicate_read_result"))
            assertEquals("native_runtime_unavailable", assertThrows(ProviderFailure::class.java) { session(NativeChat.parse(duplicate.toString())) }.reason)
            val outOfOrder = JSONObject(root.toString()).put("messages", JSONArray().put(messages.first())
                .put(result).put(owner).apply { messages.drop(1).forEach { put(it) } })
            assertEquals("native_runtime_unavailable", assertThrows(ProviderFailure::class.java) { session(NativeChat.parse(outOfOrder.toString())) }.reason)
            owner.put("stopReason", "error")
            assertNotNull(session(archive(false))) // A failed archived read never becomes runnable.
            owner.put("stopReason", "toolUse")
            result.put("toolName", "foreign_tool")
            assertEquals("native_runtime_unavailable", assertThrows(ProviderFailure::class.java) { session(archive()) }.reason)
        }
    }

    @Test fun dismissedQuestionsNeverRevealTheirHiddenGenerationOrBecomeAnswerCards() {
        val root = chat("m_explain").snapshot()
        root.getJSONArray("messages").objects().last().getJSONArray("content").put(JSONObject()
            .put("type", "thinking").put("thinking", "Authored hidden question commitment"))
        val pending = NativeChat.parse(root.toString())
        val source = pending.raw
        val dismissed = pending.dismissQuestion(pending.state().pending!!.id, 1000)
        for (native in listOf(pending, dismissed, NativeResponses.append(dismissed, NativeResponses.user("Move on", 1001), 1001))) {
            assertFalse(session(native).items.any { it.text.contains("Authored hidden question commitment") })
            assertTrue(native.raw.contains("Authored hidden question commitment"))
        }
        assertEquals(session(pending).answerCards.size, session(dismissed).answerCards.size)
        assertNull(session(dismissed).question); assertFalse(dismissed.state().continuation)
        assertEquals(source, pending.raw)
    }

    @Test fun answeredCardsUseSavedCommitmentsRatherThanRegradingFromTheCall() {
        val root = chat("m_explain_answer").snapshot()
        val messages = root.getJSONArray("messages").objects()
        messages.first { it.getString("id") == "m_probe" }.getJSONArray("content").objects()
            .first { it.optString("type") == "toolCall" }.getJSONObject("arguments").put("correctAnswer", "three")
        messages.first { it.getString("id") == "m_explain" }.getJSONArray("content").objects()
            .first { it.optString("type") == "toolCall" }.getJSONObject("arguments").put("correctAnswer", "Not the saved commitment")
        val native = NativeChat.parse(root.toString())
        val s = session(native)
        val quiz = s.answerCards.getValue(s.nativeDisplayIds.getValue(NativeChat.Part("m_probe_answer")))
        assertEquals(setOf("four", "two"), quiz.correctValues.toSet())
        val explain = s.answerCards.getValue(s.nativeDisplayIds.getValue(NativeChat.Part("m_explain_answer")))
        assertEquals(messages.first { it.getString("id") == "m_explain_answer" }.getJSONObject("details").getString("committed"), explain.correctAnswer)
    }

    @Test fun responsesSignaturesOpaqueMetadataAndReportedUsageSurviveWithoutInventingCostOrCacheUsage() {
        val response = JSONObject("""{"id":"resp_fixture","status":"completed","output":[
          {"type":"reasoning","id":"rs_fixture","encrypted_content":"OPAQUE","summary":[{"type":"summary_text","text":"Public summary"}]},
          {"type":"message","id":"msg_fixture","role":"assistant","phase":"final_answer","content":[{"type":"output_text","text":"Authoritative text","annotations":[]},{"type":"output_text","text":" continued"}]},
          {"type":"function_call","id":"fc_fixture","call_id":"call_fixture","name":"ask_user_question","arguments":"{\"question\":\"Preference?\"}"}],
          "usage":{"input_tokens":40,"output_tokens":10,"total_tokens":50,"input_tokens_details":{"cached_tokens":12}},"future":{"opaque":true}}""")
        val message = NativeResponses.assistant(response, "fixture", "api", 1000)
        val blocks = message.getJSONArray("content")
        assertEquals("Authoritative text continued", blocks.getJSONObject(1).getString("text"))
        assertEquals("resp_fixture", message.getString("responseId"))
        assertEquals("completed", message.getString("rawStopReason"))
        assertEquals("call_fixture|fc_fixture", blocks.getJSONObject(2).getString("id"))
        assertEquals(response.toString(), message.getJSONObject("reasonedResponses").toString())
        val usage = message.getJSONObject("usage")
        assertEquals(28, usage.getInt("input")); assertEquals(12, usage.getInt("cacheRead"))
        assertFalse(usage.has("cost")); assertFalse(usage.has("cacheWrite"))
        val original = chat("m_predict_answer")
        val combined = NativeResponses.append(original, message, 1000)
        val own = NativeResponses.input(combined, "fixture", "api").objects()
        assertTrue(own.any { it.optString("encrypted_content") == "OPAQUE" })
        assertTrue(own.any { it.optString("id") == "msg_fixture" && it.optString("phase") == "final_answer" })
        assertTrue(own.any { it.optString("id") == "fc_fixture" && it.optString("call_id") == "call_fixture" })
        val foreign = NativeResponses.input(combined, "different-model", "api").objects()
        assertFalse(foreign.any { it.optString("encrypted_content") == "OPAQUE" })
        assertTrue(foreign.any { it.optString("type") == "function_call" && it.optString("call_id") == "call_fixture" && !it.has("id") })
        assertEquals(response.toString(), combined.snapshot().getJSONArray("messages").objects().last().getJSONObject("reasonedResponses").toString())
        response.getJSONObject("usage").getJSONObject("input_tokens_details").put("cache_write_tokens", 5)
        response.getJSONObject("usage").put("output_tokens_details", JSONObject().put("reasoning_tokens", 7))
        val reported = NativeResponses.assistant(response, "fixture", "api", 1001).getJSONObject("usage")
        assertEquals(23, reported.getInt("input")); assertEquals(5, reported.getInt("cacheWrite")); assertEquals(7, reported.getInt("reasoning"))
        response.getJSONObject("usage").put("input_tokens_details", JSONObject())
        assertFalse(NativeResponses.assistant(response, "fixture", "api", 1001).getJSONObject("usage").has("cacheRead"))
        response.getJSONObject("usage").put("input_tokens_details", JSONObject().put("cached_tokens", 41))
        assertThrows(ProviderFailure::class.java) { NativeResponses.assistant(response, "fixture", "api", 1001) }
        response.remove("usage")
        assertFalse(NativeResponses.assistant(response, "fixture", "api", 1001).has("usage"))
    }

    @Test fun moneyChannelIsAppendOnlyIdempotentAndIndependentOfExecutableOutput() {
        val source = chat("m_predict_answer")
        val response = JSONObject("""{"id":"resp_rejected","status":"incomplete","output":[{"type":"unsupported"}],
            "usage":{"input_tokens":40,"output_tokens":10,"input_tokens_details":{"cached_tokens":12,"cache_write_tokens":5},
            "output_tokens_details":{"reasoning_tokens":7}}}""")
        val usage = NativeResponses.usage(response)!!
        assertEquals(23, usage.getInt("input"))
        val step = NativeResponses.step(usage)
        assertEquals(23, step.getInt("inputTokens")); assertEquals(12, step.getInt("cacheReadTokens"))
        assertEquals(5, step.getInt("cacheWriteTokens")); assertEquals(7, step.getInt("reasoningTokens"))
        assertFalse(step.has("cost")); assertFalse(step.has("cacheWrite1hTokens"))
        val failure = NativeResponses.failure("fixture", "api", "Partial", false, "incomplete", 2000, response)
        assertEquals(response.toString(), failure.getJSONObject("reasonedResponses").toString())
        assertEquals(usage.toString(), failure.getJSONObject("usage").toString())
        val oversized = NativeResponses.failure("fixture", "api", "", false, "output_limit", 2000,
            JSONObject(response.toString()).put("authored_large_output", "x".repeat(262_145)))
        assertFalse(oversized.has("reasonedResponses")); assertEquals(usage.toString(), oversized.getJSONObject("usage").toString())
        val failed = NativeResponses.append(source, failure, 2000)
        val entry = NativeResponses.turn("unique_turn", failure.getString("id"), JSONArray().put(step), "fixture", "api", 2000)
        val logged = NativeResponses.appendTurn(failed, entry)
        assertEquals(logged.raw, NativeResponses.appendTurn(logged, JSONObject(entry.toString()).put("future", "not a rewrite")).raw)
        val continuation = NativeResponses.appendTurn(logged, JSONObject(entry.toString()).put("id", "different_turn"))
        assertEquals(logged.snapshot().getJSONArray("turns").length() + 1, continuation.snapshot().getJSONArray("turns").length())
        assertEquals(failed.snapshot().getJSONArray("messages").toString(), continuation.snapshot().getJSONArray("messages").toString())
        assertFalse(NativeResponses.input(continuation, "fixture", "api", retry = true).toString().contains("resp_rejected"))
        assertEquals("{}", NativeResponses.step(null).toString())
        for (value in listOf(-1, 1.5, "40", 1_000_001)) {
            response.getJSONObject("usage").put("input_tokens", value)
            assertThrows(ProviderFailure::class.java) { NativeResponses.usage(response) }
            assertFalse(NativeResponses.failure("fixture", "api", "", false, "invalid_output", 2000, response).has("usage"))
        }
        response.getJSONObject("usage").put("input_tokens", 40).put("output_tokens", 6)
        assertThrows(ProviderFailure::class.java) { NativeResponses.usage(response) } // reasoning cannot exceed output.
    }

    @Test fun retryDropsOnlyFailedWireMessagesAndDoesNotMutateDurableHistory() {
        val base = chat("m_predict_answer")
        val failure = NativeResponses.failure("fixture", "api", "Partial text", false, "network", 2000)
        val failed = NativeResponses.append(base, failure, 2000)
        val raw = failed.raw
        val wire = NativeResponses.input(failed, "fixture", "api", retry = true).toString()
        assertFalse(wire.contains("Partial text"))
        assertEquals(raw, failed.raw)
        assertEquals(setOf(failure.getString("id")), failed.state().retryDrops)
    }

    @Test fun nativeQuestionBatchesValidateEveryCallWithoutRelaxingOtherEnvelopes() {
        val s = session(chat("m_predict_answer"))
        val tools = NativeTeaching.tools()
        fun call(id: String) = JSONObject().put("type", "function_call").put("call_id", id)
            .put("name", "ask_user_question").put("arguments", "{\"question\":\"Authored preference?\"}")
        fun response(vararg calls: JSONObject) = JSONObject().put("status", "completed").put("output", JSONArray(calls.toList()))
        val batch = response(call("first"), call("second"))
        assertEquals("first", RuntimeTools.validate(batch, s, tools, allowQuestionBatch = true).call!!.getString("call_id"))
        assertEquals("multiple_tool_calls", assertThrows(ProviderFailure::class.java) { RuntimeTools.validate(batch, s, tools) }.reason)
        val legacyArgs = "{\"question\":\"Authored preference?\",\"details\":\"\",\"options\":[],\"multiSelect\":false}"
        val legacyBatch = response(call("first").put("arguments", legacyArgs), call("second").put("arguments", legacyArgs))
        assertEquals("multiple_tool_calls", assertThrows(ProviderFailure::class.java) {
            RuntimeTools.validate(legacyBatch, s.copy(nativeRecord = null), tools, allowQuestionBatch = true)
        }.reason)
        assertEquals("multiple_tool_calls", assertThrows(ProviderFailure::class.java) {
            RuntimeTools.validate(batch, s.copy(phase = "practice"), tools, allowQuestionBatch = true)
        }.reason)
        for ((bad, reason) in listOf(
            response(call("first"), call("first").put("id", "different_provider_item")) to "tool_replay",
            response(call("first"), call("first|suffix")) to "invalid_output",
            response(call("first"), call("call_predict")) to "tool_replay",
            response(call("first"), call("second").put("namespace", "foreign")) to "tool_denied",
            response(call("first"), call("second").put("namespace", RuntimeTools.NAMESPACE)) to "tool_denied",
            response(call("first"), call("second").put("arguments", "{}")) to "invalid_tool_arguments",
            response(call("first"), call("second").put("name", "plan_init")) to "multiple_tool_calls",
            response(*(0..16).map { call("call_$it") }.toTypedArray()) to "invalid_output_count",
            response(call("first"), call("second").put("oversized", "x".repeat(262_145))) to "output_limit"
        )) assertEquals(reason, assertThrows(ProviderFailure::class.java) {
            RuntimeTools.validate(bad, s, tools, allowQuestionBatch = true)
        }.reason)
    }

    @Test fun nativeOptionalArgumentsDoNotWeakenToolPermissionsReplayGuardsOrPlanReferences() {
        val native = chat("m_predict_answer")
        val s = session(native)
        fun response(name: String, id: String, args: JSONObject, namespace: String = "") = JSONObject()
            .put("status", "completed").put("output", JSONArray().put(JSONObject().put("type", "function_call")
                .put("call_id", id).put("name", name).put("arguments", args.toString()).apply { if (namespace.isNotEmpty()) put("namespace", namespace) }))
        val tools = NativeTeaching.tools()
        RuntimeTools.validate(response("ask_user_question", "fresh", JSONObject().put("question", "Preference?")), s, tools)
        val namespace = chatGptPayload(JSONObject().put("input", JSONArray()).put("tools", tools), "fixture")
            .getJSONArray("tools").getJSONObject(0).getString("name")
        val account = s.copy(connection = "fixture-account")
        RuntimeTools.validate(response("ask_user_question", "fresh", JSONObject().put("question", "Preference?"), namespace), account, tools)
        assertEquals("tool_denied", assertThrows(ProviderFailure::class.java) {
            RuntimeTools.validate(response("ask_user_question", "fresh", JSONObject().put("question", "Wrong namespace"), "functions"), account, tools)
        }.reason)
        for (bad in listOf(
            response("research", "fresh", JSONObject().put("question", "No permission")),
            response("ask_user_question", "call_predict", JSONObject().put("question", "Replay")),
            response("ask_user_question", "fresh", JSONObject().put("question", "Foreign namespace"), "other"),
            response("ask_user_question", "fresh", JSONObject().put("question", "Empty options").put("options", JSONArray())),
            response("explain", "fresh", JSONObject().put("question", "Question").put("correctAnswer", "Commitment").put("type", "check").put("nodeId", "unknown"))
        )) assertThrows(ProviderFailure::class.java) { RuntimeTools.validate(bad, s, tools) }
    }
}
