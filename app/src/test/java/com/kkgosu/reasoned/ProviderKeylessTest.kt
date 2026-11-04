package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.Connections
import com.kkgosu.reasoned.api.Provider
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.ProviderModel
import com.kkgosu.reasoned.api.Wire
import com.kkgosu.reasoned.api.WireProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Only a custom endpoint can be keyless, and a keyless one sends no auth header even when a key is
 * saved. A registry provider always sends its key.
 */
class ProviderKeylessTest {
    private val captured = mutableListOf<Request>()

    /** Canned bodies let [WireProvider] finish; the captured request is what each test inspects. */
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        captured += request
        val body = if (request.url.encodedPath.endsWith("/models")) {
            """{"data":[{"id":"custom-model"}]}""".toResponseBody(JSON)
        } else {
            sse(
                """{"id":"chat_1","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]"
            ).asResponseBody(SSE)
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }.build()

    @Test fun keylessModelListSendsNoAuthEvenWithASavedKey() = runBlocking {
        val models = transport(provider(Connections.CUSTOM, keyless = true), "sk-saved").models()
        assertEquals(listOf("custom-model" to "custom-model"), models)
        assertEquals(1, captured.size)
        assertNull(captured.single().header("Authorization"))
    }

    @Test fun keylessRespondSendsNoAuthEvenWithASavedKey() = runBlocking {
        val response = transport(provider(Connections.CUSTOM, keyless = true), "sk-saved")
            .respond(chatRequest()) { }
        val text = response.getJSONArray("output").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals("ok", text)
        assertEquals(1, captured.size)
        assertNull(captured.single().header("Authorization"))
    }

    @Test fun nonKeylessCustomWithoutAKeyFailsBeforeTheModelList() = runBlocking {
        rejects("key_missing") { transport(provider(Connections.CUSTOM, keyless = false), "").models() }
        assertTrue(captured.isEmpty())
    }

    @Test fun nonKeylessCustomWithoutAKeyFailsBeforeRespond() = runBlocking {
        rejects("key_missing") {
            transport(provider(Connections.CUSTOM, keyless = false), "").respond(chatRequest()) { }
        }
        assertTrue(captured.isEmpty())
    }

    @Test fun aRegistryProviderIsNeverKeyless() = runBlocking {
        transport(provider("deepseek", keyless = true), "sk-saved").models()
        assertEquals("Bearer sk-saved", captured.single().header("Authorization"))
    }

    @Test fun customBaseUrlsAreUsedVerbatimForModelsAndGeneration() = runBlocking {
        for (base in listOf("https://endpoint.example", "https://endpoint.example/tenant/openai", "https://endpoint.example/v1/")) {
            val endpoint = provider(Connections.CUSTOM, keyless = true).copy(baseUrl = base)
            val wire = transport(endpoint, "")
            wire.models()
            wire.respond(chatRequest()) { }
            assertEquals("${base.trimEnd('/')}/models", captured[captured.lastIndex - 1].url.toString())
            assertEquals("${base.trimEnd('/')}/chat/completions", captured.last().url.toString())
        }
    }

    private fun provider(id: String, keyless: Boolean) = Provider(
        id = id,
        label = id,
        wire = Wire.CHAT,
        baseUrl = "https://endpoint.example/v1",
        docs = null,
        models = listOf(ProviderModel("custom-model", "Custom", 128_000, 8_192, emptyList())),
        keyless = keyless,
    )

    private fun transport(provider: Provider, key: String) =
        WireProvider(provider, token = { key }, client = client)

    private fun chatRequest() = JSONObject().put("model", "custom-model")
        .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))

    private suspend fun rejects(reason: String, action: suspend () -> Unit) {
        try {
            action(); fail("Invalid input accepted")
        } catch (failure: ProviderFailure) {
            assertEquals(reason, failure.reason)
        }
    }

    private fun sse(vararg events: String) =
        Buffer().writeUtf8(events.joinToString("") { "data: $it\n\n" })

    companion object {
        private val JSON = "application/json".toMediaType()
        private val SSE = "text/event-stream".toMediaType()
    }
}
