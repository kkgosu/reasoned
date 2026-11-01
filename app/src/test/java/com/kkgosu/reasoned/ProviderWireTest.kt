package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.AnthropicWire
import com.kkgosu.reasoned.api.ChatWire
import com.kkgosu.reasoned.api.Connections
import com.kkgosu.reasoned.api.GeminiWire
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.ProviderModel
import com.kkgosu.reasoned.api.Providers
import com.kkgosu.reasoned.api.Wire
import com.kkgosu.reasoned.controllers.canonicalRequest
import com.kkgosu.reasoned.controllers.ToolSchemas
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ProviderWireTest {
    private val registry: String by lazy {
        listOf(File("src/main/assets/providers.json"), File("app/src/main/assets/providers.json"))
            .first { it.exists() }.readText()
    }

    private fun provider(id: String) = run {
        Providers.parse(registry)
        requireNotNull(Providers.byId(id)) { "Missing provider $id" }
    }

    private fun rejects(reason: String, action: () -> Unit) {
        try {
            action(); fail("Invalid input accepted")
        } catch (failure: ProviderFailure) {
            assertEquals(reason, failure.reason)
        }
    }

    private fun sse(vararg events: String) = Buffer().writeUtf8(events.joinToString("") { "data: $it\n\n" })

    @Test fun registryCarriesEverySupportedProviderAndModelWithAbsoluteEndpoints() {
        Providers.parse(registry)
        val providers = Providers.all()
        assertEquals(33, providers.size)
        assertEquals(providers.size, providers.map { it.id }.distinct().size)
        for (provider in providers) {
            assertTrue(provider.id, provider.baseUrl.startsWith("https://"))
            assertFalse(provider.id, provider.models.isEmpty())
            assertTrue(provider.id != Connections.CUSTOM)
            for (model in provider.models) {
                assertTrue("${provider.id}/${model.id}", model.window > 0)
                assertTrue("${provider.id}/${model.id}", model.name.isNotBlank())
                assertTrue(
                    "${provider.id}/${model.id}",
                    model.efforts.all { it in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max") }
                )
            }
        }
        assertEquals(Wire.CHAT, provider("deepseek").wire)
        assertEquals(Wire.ANTHROPIC, provider("anthropic").wire)
        assertEquals(Wire.GEMINI, provider("google").wire)
        assertEquals(Wire.RESPONSES, provider("openai").wire)
        assertTrue(provider("openrouter").models.size > 300)
    }

    @Test fun unknownWireNameInTheAssetIsRejected() {
        val broken = registry.replaceFirst("\"wire\": \"chat\"", "\"wire\": \"future-wire\"")
        try {
            Providers.parse(broken); fail("Unknown wire accepted")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("future-wire"))
        }
    }

    @Test fun connectionIdsKeepLegacySessionsReadable() {
        assertEquals("openai", Connections.providerId("api"))
        assertEquals("deepseek", Connections.providerId("key:deepseek"))
        assertEquals("openai-codex", Connections.providerId("oaiapp_abc-123"))
        assertEquals("openai-responses", Providers.apiName("api"))
        assertEquals("openai-completions", Providers.apiName("key:deepseek"))
        assertEquals("openai-codex-responses", Providers.apiName("oaiapp_abc"))
        assertTrue(Connections.valid("api"))
        assertTrue(Connections.valid("key:deepseek"))
        assertTrue(Connections.valid("key:custom"))
        assertTrue(Connections.valid("oaiapp_abc"))
        assertFalse(Connections.valid("key:"))
        assertFalse(Connections.valid("key:DeepSeek"))
        assertFalse(Connections.valid("oaiapp_"))
    }

    @Test fun customEndpointAllowsHttpOnlyForTheThreeCleartextHosts() {
        assertTrue(Connections.customUrl("https://api.example.com/v1"))
        assertTrue(Connections.customUrl("http://localhost:11434/v1"))
        assertTrue(Connections.customUrl("http://127.0.0.1:8080"))
        assertTrue(Connections.customUrl("http://10.0.2.2:1234/v1"))
        assertFalse(Connections.customUrl("http://localhost.evil.example/v1"))
        assertFalse(Connections.customUrl("http://localhost@evil.example/v1"))
        assertFalse(Connections.customUrl("http://192.168.1.5:11434/v1"))
        assertFalse(Connections.customUrl("http://10.0.2.3:11434/v1"))
        assertFalse(Connections.customUrl("https://api.example.com/v1?key=1"))
        assertFalse(Connections.customUrl("https://api.example.com/v1#models"))
        assertFalse(Connections.customUrl("http://localhost:11434/v 1"))
    }

    @Test fun chatRequestSplitsSystemMessagesToolsAndProviderThinking() {
        val model = provider("deepseek").model("deepseek-flash")!!
        val input = JSONArray()
            .put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", "Teach me")))
            )
            .put(JSONObject().put("type", "reasoning").put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "plan"))))
            .put(JSONObject().put("type", "function_call").put("call_id", "call_1").put("name", "probe").put("arguments", "{\"q\":1}"))
            .put(JSONObject().put("type", "function_call_output").put("call_id", "call_1").put("output", "answered"))
        val canonical = canonicalRequest(
            "deepseek-flash", "You teach.", input, JSONArray(ToolSchemas.schemas("teach").toString()),
            effort = "high", reasoning = true, maxOutput = 4096
        )
        val request = ChatWire.request(canonical, model, "deepseek-flash")
        assertEquals("deepseek-flash", request.getString("model"))
        assertTrue(request.getBoolean("stream"))
        assertFalse(request.has("instructions"))
        assertFalse(request.has("input"))
        val messages = request.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Teach me", messages.getJSONObject(1).getString("content"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
        assertEquals("call_1", messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", messages.getJSONObject(3).getString("role"))
        assertEquals("call_1", messages.getJSONObject(3).getString("tool_call_id"))
        val tool = request.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("learning_context", tool.getJSONObject("function").getString("name"))
        // deepseek-flash declares effort levels, so the thinking request gets the shared 8192 cap.
        assertEquals(8192, request.getInt("max_tokens"))
        assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
        assertEquals("high", request.getString("reasoning_effort"))
    }

    @Test fun openRouterAndZaiUseTheirOwnThinkingShape() {
        val openRouter = provider("openrouter").let { it.model(it.models.first { model -> model.id.startsWith("anthropic/") }.id)!! }
        val reasoning = JSONObject().put("effort", "medium")
        val canonical = JSONObject().put("model", openRouter.id)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))
            .put("instructions", "hi").put("reasoning", reasoning)
        val request = ChatWire.request(canonical, openRouter, openRouter.id)
        assertEquals("medium", request.getJSONObject("reasoning").getString("effort"))
        assertNull(request.opt("reasoning_effort"))
    }

    @Test fun chatStreamAssemblesTextToolCallsAndUsage() = runBlocking {
        val chunks = sse(
            """{"id":"chat_1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}""",
            """{"id":"chat_1","choices":[{"index":0,"delta":{"content":"lo"}}]}""",
            """{"id":"chat_1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_9","type":"function","function":{"name":"probe","arguments":"{\"q\":"}}]}}]}""",
            """{"id":"chat_1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"2}"}}]}}]}""",
            """{"id":"chat_1","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":11,"completion_tokens":7}}""",
            "[DONE]"
        )
        val seen = StringBuilder()
        val response = ChatWire.read(chunks) { seen.append(it) }
        assertEquals("Hello", seen.toString())
        val output = response.getJSONArray("output")
        assertEquals("message", output.getJSONObject(0).getString("type"))
        assertEquals("Hello", output.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals("function_call", output.getJSONObject(1).getString("type"))
        assertEquals("call_9", output.getJSONObject(1).getString("call_id"))
        assertEquals("{\"q\":2}", output.getJSONObject(1).getString("arguments"))
        assertEquals(11, response.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(7, response.getJSONObject("usage").getInt("output_tokens"))
    }

    @Test fun chatStreamWithoutTerminalFinishIsIncomplete() = runBlocking {
        val body = sse("""{"id":"chat_1","choices":[{"index":0,"delta":{"content":"partial"}}]}""")
        rejects("incomplete") { runBlocking { ChatWire.read(body) { } } }
    }

    @Test fun anthropicRequestAndStreamUseNativeBlocks() = runBlocking {
        val model = provider("anthropic").model("claude-sonnet-5-5")!!
        val input = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "Explain"))
            .put(JSONObject().put("type", "function_call").put("call_id", "toolu_1").put("name", "probe").put("arguments", "{\"q\":\"x\"}"))
            .put(JSONObject().put("type", "function_call_output").put("call_id", "toolu_1").put("output", "A"))
        val canonical = canonicalRequest("claude-sonnet-5-5", "Teach", input, JSONArray(ToolSchemas.schemas("teach").toString()), effort = "high")
        val request = AnthropicWire.request(canonical, model, "claude-sonnet-5-5")
        assertEquals("Teach", request.getString("system"))
        val messages = request.getJSONArray("messages")
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("tool_use", messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))
        assertEquals("tool_result", messages.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("adaptive", request.getJSONObject("thinking").getString("type"))
        assertEquals("high", request.getJSONObject("output_config").getString("effort"))

        val stream = Buffer().writeUtf8(
            listOf(
                "message_start" to """{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":20,"cache_read_input_tokens":5}}}""",
                "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"cut the problem"}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig_1"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":0}""",
                "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_2","name":"probe","input":{}}}""",
                "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"y\"}"}}""",
                "content_block_stop" to """{"type":"content_block_stop","index":1}""",
                "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""",
                "message_stop" to """{"type":"message_stop"}"""
            ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
        )
        val response = AnthropicWire.read(stream) { }
        val output = response.getJSONArray("output")
        assertEquals("reasoning", output.getJSONObject(0).getString("type"))
        assertEquals("sig_1", output.getJSONObject(0).getString("encrypted_content"))
        assertEquals("toolu_2", output.getJSONObject(1).getString("call_id"))
        assertEquals("{\"q\":\"y\"}", output.getJSONObject(1).getString("arguments"))
        assertEquals(25, response.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(5, response.getJSONObject("usage").getJSONObject("input_tokens_details").getInt("cached_tokens"))
        assertEquals(9, response.getJSONObject("usage").getInt("output_tokens"))
    }

    @Test fun geminiRequestAndStreamTranslateAcrossTheWire() = runBlocking {
        val model = provider("google").models.first { it.compat.thinkingParam != null }
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", "Why?"))
        val canonical = canonicalRequest(model.id, "Teach", input, JSONArray(ToolSchemas.schemas("teach").toString()), effort = "high")
        val request = GeminiWire.request(canonical, model, model.id)
        val tools = request.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertFalse(tools.getJSONObject(0).getJSONObject("parameters").has("additionalProperties"))
        assertEquals("AUTO", request.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))
        assertEquals(8192, request.getJSONObject("generationConfig").getInt("maxOutputTokens"))

        val stream = Buffer().writeUtf8(
            listOf(
                """{"candidates":[{"content":{"parts":[{"text":"Because "}],"role":"model"},"index":0}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"it follows."}],"role":"model"},"index":0}]}""",
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"probe","args":{"q":"z"}}}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":6,"thoughtsTokenCount":2}}"""
            ).joinToString("") { "data: $it\n\n" }
        )
        val seen = StringBuilder()
        val response = GeminiWire.read(stream) { seen.append(it) }
        assertEquals("Because it follows.", seen.toString())
        val output = response.getJSONArray("output")
        assertEquals("message", output.getJSONObject(0).getString("type"))
        assertEquals("function_call", output.getJSONObject(1).getString("type"))
        assertEquals("probe", output.getJSONObject(1).getString("name"))
        assertEquals("z", JSONObject(output.getJSONObject(1).getString("arguments")).getString("q"))
        assertEquals(8, response.getJSONObject("usage").getInt("output_tokens"))
    }

    @Test fun oversizedVisibleTextFailsInsteadOfTruncating() = runBlocking {
        val huge = "x".repeat(25_000)
        val body = sse(
            JSONObject().put("id", "chat_1")
                .put("choices", JSONArray().put(JSONObject().put("index", 0)
                    .put("delta", JSONObject().put("content", huge))
                    .put("finish_reason", "stop"))).toString()
        )
        rejects("output_limit") { runBlocking { ChatWire.read(body) { } } }
    }

    @Test fun providerModelDefaultsDoNotInventMetadata() {
        val provider = provider("groq")
        assertNull(provider.model("not-a-real-model"))
        assertEquals(listOf<String>(), ProviderModel("x", "x", 1, 0, emptyList()).efforts)
    }
}
