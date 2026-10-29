package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.chatGptPayload
import com.kkgosu.reasoned.api.readResponseStream
import com.kkgosu.reasoned.controllers.ToolSchemas
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatGptPayloadTest {
    private fun rejects(reason: String? = null, action: () -> Unit) {
        try {
            action(); fail("Invalid input accepted")
        } catch (failure: ProviderFailure) {
            if (reason != null) assertEquals(reason, failure.reason)
        }
    }


    @Test fun terminalUsageAndMetadataSurviveWithoutAcceptingFailedOrPendingOutput() = runBlocking {
        val terminal = JSONObject("""{"id":"resp_authored","status":"incomplete","output":[],
            "usage":{"input_tokens":40,"output_tokens":10},"error":{"message":"private fixture diagnostic"}}""")
        for (type in listOf("response.failed", "response.incomplete", "response.completed")) {
            val event = JSONObject().put("type", type).put("response", terminal)
            try {
                readResponseStream(Buffer().writeUtf8("data: $event\n\n")) { }; fail("Terminal failure accepted")
            } catch (failure: ProviderFailure) {
                assertEquals(terminal.toString(), failure.response!!.toString())
                assertFalse(failure.toString().contains("private"))
            }
        }
        terminal.remove("error"); terminal.put("status", "completed")
        val item = JSONObject("""{"type":"reasoning","id":"rs_authored","summary":[]}""")
        val added = JSONObject().put("type", "response.output_item.added").put("output_index", 0).put("item", item)
        val completed = JSONObject().put("type", "response.completed").put("response", terminal)
        try {
            readResponseStream(Buffer().writeUtf8("data: $added\n\ndata: $completed\n\n")) { }
            fail("Pending item accepted")
        } catch (failure: ProviderFailure) {
            assertEquals("incomplete", failure.reason)
            assertEquals(40, failure.response!!.getJSONObject("usage").getInt("input_tokens"))
        }
        val done = JSONObject().put("type", "response.output_item.done").put("output_index", 0).put("item", item)
        terminal.put("output", JSONArray().put(JSONObject(item.toString()).put("encrypted_content", "OPAQUE").put("future", "terminal-only")))
        val event = JSONObject().put("type", "response.completed").put("response", terminal)
        val stream = "data: $done\n\ndata: $event\n\n"
        val response = readResponseStream(Buffer().writeUtf8(stream)) { }
        assertEquals("OPAQUE", response.getJSONArray("output").getJSONObject(0).getString("encrypted_content"))
        assertEquals("terminal-only", response.getJSONArray("reasonedTerminalOutput").getJSONObject(0).getString("future"))
        assertEquals(40, response.getJSONObject("usage").getInt("input_tokens"))
    }

    @Test
    fun learnHarnessPayloadAndTerminalFailuresStayBoundedAndRedacted() = runBlocking {
        val raw = JSONObject().put("model", "wrong-model").put("store", true).put("stream", false)
            .put(
                "input",
                JSONArray().put(
                    JSONObject().put("role", "system").put("content", "Fixture instructions")
                )
            )
            .put("tools", ToolSchemas.schemas("probe")).put("instructions", "Fixture")
        val forbidden = listOf(
            "max_output_tokens",
            "previous_response_id",
            "background",
            "metadata",
            "temperature",
            "top_p",
            "prompt_cache_retention"
        )
        forbidden.forEach { raw.put(it, "must-not-be-sent") }
        val payload = chatGptPayload(raw, "selected-model")
        assertEquals("selected-model", payload.getString("model"))
        assertFalse(payload.getBoolean("store")); assertTrue(payload.getBoolean("stream"))
        forbidden.forEach { assertFalse(it, payload.has(it)) }
        assertEquals("developer", payload.getJSONArray("input").getJSONObject(0).getString("role"))
        assertEquals("system", raw.getJSONArray("input").getJSONObject(0).getString("role"))
        assertEquals(
            "learnharness",
            payload.getJSONArray("tools").getJSONObject(0).getString("name")
        )
        rejects("protocol") { chatGptPayload(JSONObject(), "selected-model") }
        raw.put("tools", JSONArray().put(JSONObject().put("type", "tool_search")))
        rejects("tool_denied") { chatGptPayload(raw, "selected-model") }
        val code = "subscription_sharing_usage_limit_exceeded"
        val error = JSONObject().put("code", code).put("message", "private provider diagnostic")
        val failed = listOf(
            JSONObject().put("type", "error").put("code", code),
            JSONObject().put("type", "response.failed")
                .put("response", JSONObject().put("error", error)),
            JSONObject().put("type", "response.completed")
                .put("response", JSONObject().put("status", "completed").put("error", error)),
            JSONObject().put("type", "response.created")
                .put("response", JSONObject().put("error", error))
        )
        for (event in failed) {
            try {
                readResponseStream(Buffer().writeUtf8("data: $event\n\n")) { }; fail("Failed response accepted")
            } catch (failure: ProviderFailure) {
                assertEquals(code, failure.reason); assertFalse(
                    failure.toString().contains("private")
                )
            }
        }
        for (stream in listOf(
            "",
            "data: [DONE]\n\n",
            "data: {\"type\":\"response.created\"}\n\n"
        )) {
            try {
                readResponseStream(Buffer().writeUtf8(stream)) { }; fail("Missing completion accepted")
            } catch (failure: ProviderFailure) {
                assertEquals("incomplete", failure.reason)
            }
        }
    }
}
