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
