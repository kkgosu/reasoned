package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.Provider
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.Providers
import com.kkgosu.reasoned.api.Wire
import com.kkgosu.reasoned.api.WireProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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

/**
 * Every registered endpoint keeps its URL, credential header and provider-level headers after the
 * wire translation. An interceptor answers with a canonical 400, so no socket leaves the JVM.
 */
class ProviderEndpointTest {
    private val registry: String by lazy {
        listOf(File("src/main/assets/providers.json"), File("app/src/main/assets/providers.json"))
            .first { it.exists() }.readText()
    }

    private data class Captured(val request: Request, val body: String)

    private val captured = mutableListOf<Captured>()

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
        captured += Captured(request, body)
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body("""{"error":{"param":"probe"}}""".toResponseBody(JSON))
            .build()
    }.build()

    @Test fun everyProviderSendsItsEndpointCredentialAndProviderHeaders() = runBlocking {
        Providers.parse(registry)
        val providers = Providers.all()
        assertTrue(providers.isNotEmpty())
        for (provider in providers) {
            captured.clear()
            val modelId = provider.models.first().id
            val transport = WireProvider(provider, token = { KEY }, client = client)
            try {
                transport.respond(request(modelId), { }, SESSION)
                fail("${provider.id}: the probe rejection was not raised")
            } catch (failure: ProviderFailure) {
                assertEquals("${provider.id} reason", "invalid_request:probe", failure.reason)
            }
            assertEndpoint(provider, modelId, captured.single())
        }
    }

    @Test fun modelListOmitsTheSessionHeaderButKeepsTheCredential() = runBlocking {
        Providers.parse(registry)
        val provider = requireNotNull(Providers.byId("opencode-go")) { "Missing opencode-go" }
        val transport = WireProvider(provider, token = { KEY }, client = client)
        try {
            transport.models()
            fail("the probe rejection was not raised")
        } catch (failure: ProviderFailure) {
            assertEquals("invalid_request:probe", failure.reason)
        }
        val sent = captured.single()
        assertEquals(
            "https://opencode.ai/inference/go/v1/models",
            sent.request.url.toString(),
        )
        assertEquals(USER_AGENT, sent.request.header("User-Agent"))
        assertEquals("Reasoned", sent.request.header("x-opencode-client"))
        assertNull(sent.request.header(SESSION_HEADER))
        assertEquals("Bearer $KEY", sent.request.header("Authorization"))
    }

    @Test fun anUnsafeSessionIdIsNotSentAsAHeader() = runBlocking {
        Providers.parse(registry)
        val provider = requireNotNull(Providers.byId("opencode-go")) { "Missing opencode-go" }
        val transport = WireProvider(provider, token = { KEY }, client = client)
        try {
            transport.respond(request(provider.models.first().id), { }, "lesson-\u00e9\n")
            fail("the probe rejection was not raised")
        } catch (failure: ProviderFailure) {
            assertEquals("invalid_request:probe", failure.reason)
        }
        assertNull(captured.single().request.header(SESSION_HEADER))
    }

    @Test fun malformedProviderHeadersAreRejectedAtParseTime() {
        val invalidName = registry.replace("\"NVCF-POLL-SECONDS\": \"3600\"", "\"Bad Header\": \"3600\"")
        assertRejected(invalidName)
        val injected = registry.replace("\"NVCF-POLL-SECONDS\": \"3600\"", "\"X-Poll\": \"3600\\r\\nInjected: 1\"")
        assertRejected(injected)
        val nonString = registry.replace("\"x-opencode-session\"", "42")
        assertRejected(nonString)
    }

    private fun assertRejected(asset: String) {
        try {
            Providers.parse(asset)
            fail("Malformed provider headers were accepted")
        } catch (_: IllegalArgumentException) {
            // The registry keeps its previous value; only a generated asset should reach parse().
        }
    }

    private fun assertEndpoint(provider: Provider, modelId: String, sent: Captured) {
        val url = sent.request.url
        val root = EXPECTED_BASE_URLS.getValue(provider.id)
        val expected = when (provider.wire) {
            Wire.RESPONSES -> "$root/responses"
            Wire.CHAT -> "$root/chat/completions"
            Wire.ANTHROPIC -> "$root/v1/messages"
            Wire.GEMINI -> "$root/models/$modelId:streamGenerateContent?alt=sse"
        }
        assertEquals("${provider.id} endpoint", expected, url.toString())
        when (provider.wire) {
            Wire.ANTHROPIC -> assertEquals(provider.id, KEY, sent.request.header("x-api-key"))
            Wire.GEMINI -> assertEquals(provider.id, KEY, sent.request.header("x-goog-api-key"))
            else -> assertEquals(provider.id, "Bearer $KEY", sent.request.header("Authorization"))
        }
        assertEquals(
            "${provider.id} user agent",
            USER_AGENT,
            sent.request.header("User-Agent"),
        )
        assertEquals(
            "${provider.id} opencode client",
            if (provider.id in OPENCODE) "Reasoned" else null,
            sent.request.header("x-opencode-client"),
        )
        assertEquals(
            "${provider.id} billing origin",
            if (provider.id == "nvidia") "Reasoned" else null,
            sent.request.header("X-BILLING-INVOKE-ORIGIN"),
        )
        assertEquals(
            "${provider.id} session header",
            if (provider.id in OPENCODE) SESSION else null,
            sent.request.header(SESSION_HEADER),
        )
        assertEquals(
            "${provider.id} poll header",
            if (provider.id == "nvidia") "3600" else null,
            sent.request.header("NVCF-POLL-SECONDS"),
        )
        if (provider.wire == Wire.GEMINI) {
            // Gemini carries the model in the URL, so the body has no model field to compare.
            assertFalse(provider.id, JSONObject(sent.body).has("model"))
            assertTrue(provider.id, url.encodedPath.contains(modelId))
        } else {
            assertEquals(provider.id, modelId, JSONObject(sent.body).getString("model"))
        }
    }

    /** The smallest canonical request every wire translates without a tool or reasoning block. */
    private fun request(modelId: String) = JSONObject().put("model", modelId)
        .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", "probe")))

    companion object {
        private const val KEY = "sk-fixture"
        private const val SESSION = "session-fixture"
        private const val SESSION_HEADER = "x-opencode-session"
        private val USER_AGENT = "Reasoned/${BuildConfig.VERSION_NAME} (Android)"
        private val OPENCODE = setOf("opencode", "opencode-go")

        /**
         * Every registry endpoint, pinned literally. Mistral and Vercel AI Gateway were corrected
         * after live probes returned 404 on their old base URLs.
         */
        private val EXPECTED_BASE_URLS = mapOf(
            "openai" to "https://api.openai.com/v1",
            "xai" to "https://api.x.ai/v1",
            "meta" to "https://api.meta.ai/v1",
            "anthropic" to "https://api.anthropic.com",
            "minimax" to "https://api.minimax.io/anthropic",
            "minimax-cn" to "https://api.minimaxi.com/anthropic",
            "google" to "https://generativelanguage.googleapis.com/v1beta",
            "ant-ling" to "https://api.ant-ling.com/v1",
            "baseten" to "https://inference.baseten.co/v1",
            "cerebras" to "https://api.cerebras.ai/v1",
            "deepseek" to "https://api.deepseek.com",
            "fireworks" to "https://api.fireworks.ai/inference/v1",
            "groq" to "https://api.groq.com/openai/v1",
            "huggingface" to "https://router.huggingface.co/v1",
            "kimi-coding" to "https://api.kimi.com/coding/v1",
            "mistral" to "https://api.mistral.ai/v1",
            "moonshotai" to "https://api.moonshot.ai/v1",
            "moonshotai-cn" to "https://api.moonshot.cn/v1",
            "nvidia" to "https://integrate.api.nvidia.com/v1",
            "opencode" to "https://opencode.ai/inference/v1",
            "opencode-go" to "https://opencode.ai/inference/go/v1",
            "openrouter" to "https://openrouter.ai/api/v1",
            "qwen-token-plan" to "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
            "qwen-token-plan-cn" to "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            "qwen-token-plan-individual" to "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1",
            "together" to "https://api.together.ai/v1",
            "vercel-ai-gateway" to "https://ai-gateway.vercel.sh/v1",
            "xiaomi" to "https://api.xiaomimimo.com/v1",
            "xiaomi-token-plan-ams" to "https://token-plan-ams.xiaomimimo.com/v1",
            "xiaomi-token-plan-cn" to "https://token-plan-cn.xiaomimimo.com/v1",
            "xiaomi-token-plan-sgp" to "https://token-plan-sgp.xiaomimimo.com/v1",
            "zai" to "https://api.z.ai/api/coding/paas/v4",
            "zai-coding-cn" to "https://open.bigmodel.cn/api/coding/paas/v4",
        )
        private val JSON = "application/json".toMediaType()
    }
}
