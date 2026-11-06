package com.kkgosu.reasoned.api

import com.kkgosu.reasoned.BuildConfig
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strictString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * One transport for every API-key wire. The provider registry decides the endpoint and the
 * translation object; nothing above [ModelProvider] sees a vendor format.
 */
internal class WireProvider(
    private val provider: Provider,
    private val token: suspend () -> String,
    private val client: OkHttpClient = openAiHttpClient(),
) : ModelProvider {
    override suspend fun respond(
        request: JSONObject,
        delta: suspend (String) -> Unit,
        sessionId: String?,
    ): JSONObject {
        val modelId = request.optString("model")
        if (!modelId.matches(MODEL_ID)) throw ProviderFailure("model_id_invalid")
        val model = provider.model(modelId) ?: ProviderModel(modelId, modelId, 0, 0, emptyList())
        val payload = when (provider.wire) {
            Wire.RESPONSES -> responsesPayload(request, modelId, model)
            Wire.CHAT -> ChatWire.request(request, model, modelId)
            Wire.ANTHROPIC -> AnthropicWire.request(request, model, modelId)
            Wire.GEMINI -> GeminiWire.request(request, model, modelId)
        }
        val key = authKey()
        val call = client.newCall(
            Request.Builder().url(url(modelId))
                .apply { headers(key, sessionId).forEach { (name, value) -> header(name, value) } }
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        )
        return coroutineScope {
            val reader = async(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val source = response.body.source()
                            val body = if (source.request(65_537)) "" else source.readUtf8()
                            throw httpFailure(response.code, body)
                        }
                        when (provider.wire) {
                            Wire.RESPONSES -> readResponseStream(response.body.source(), delta)
                            Wire.CHAT -> ChatWire.read(response.body.source(), delta)
                            Wire.ANTHROPIC -> AnthropicWire.read(response.body.source(), delta)
                            Wire.GEMINI -> GeminiWire.read(response.body.source(), delta)
                        }
                    }
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw error
                }
            }
            try {
                reader.await()
            } finally {
                call.cancel()
            }
        }
    }

    override suspend fun respond(request: JSONObject, delta: suspend (String) -> Unit): JSONObject =
        respond(request, delta, null)

    suspend fun models(): List<Pair<String, String>> {
        val key = authKey()
        val call = client.newCall(
            Request.Builder().url(modelsUrl())
                .apply { headers(key, null).forEach { (name, value) -> header(name, value) } }
                .get().build()
        )
        return coroutineScope {
            val reader = async(Dispatchers.IO) {
                call.execute().use { response ->
                    val source = response.body.source()
                    if (source.request(2_097_153)) throw ProviderFailure("model_catalog_limit")
                    val body = source.readUtf8()
                    if (!response.isSuccessful) throw httpFailure(response.code, body)
                    val rows = when (provider.wire) {
                        Wire.GEMINI -> JSONObject(body).getJSONArray("models").objects().map { model ->
                            model.getString("name").removePrefix("models/") to
                                    (model.optString("displayName").takeIf { it.isNotBlank() }
                                        ?: model.getString("name").removePrefix("models/"))
                        }

                        else -> JSONObject(body).getJSONArray("data").objects().map { model ->
                            model.strictString("id", 100) to
                                    (model.optString("display_name").takeIf { it.isNotBlank() } ?: model.strictString("id", 100))
                        }
                    }
                    rows.filter { (id, _) -> id.matches(MODEL_ID) && !id.startsWith("sk-") }
                        .distinctBy { it.first }.take(500)
                }
            }
            try {
                reader.await()
            } finally {
                call.cancel()
            }
        }
    }

    /** A registry provider always needs a key; a keyless custom endpoint sends none, even when one is saved. */
    private val keyless get() = provider.id == Connections.CUSTOM && provider.keyless

    private suspend fun authKey(): String? {
        if (keyless) return null
        return token().takeIf { it.isNotBlank() } ?: throw ProviderFailure("key_missing")
    }

    /**
     * Upstream headers first, then this client's identity, then the credential the transport owns,
     * then the conversation id. A provider entry can never replace the credential header derived
     * from the saved key, and the SSE `Accept` is applied after all of them.
     */
    private fun headers(key: String?, sessionId: String?): Map<String, String> = buildMap {
        putAll(provider.headers)
        put("User-Agent", USER_AGENT)
        CLIENT_HEADERS[provider.id]?.let { name -> put(name, CLIENT_NAME) }
        when (provider.wire) {
            Wire.ANTHROPIC -> {
                key?.let { put("x-api-key", it) }
                put("anthropic-version", "2023-06-01")
            }

            Wire.GEMINI -> key?.let { put("x-goog-api-key", it) }
            else -> key?.let { put("Authorization", "Bearer $it") }
        }
        provider.sessionHeader?.let { name ->
            // An imported lesson id is peer data; never pass a non-ASCII byte to OkHttp's header.
            sessionId?.takeIf { it.isNotBlank() && it.matches(SESSION_ID) }?.let { put(name, it) }
        }
    }

    private fun url(modelId: String) = when (provider.wire) {
        Wire.RESPONSES -> "${provider.root}/responses"
        Wire.CHAT -> "${provider.root}/chat/completions"
        Wire.ANTHROPIC -> "${provider.root}/v1/messages"
        Wire.GEMINI -> "${provider.root}/models/$modelId:streamGenerateContent?alt=sse"
    }

    private fun modelsUrl() = when (provider.wire) {
        Wire.RESPONSES -> "${provider.root}/models"
        Wire.CHAT -> "${provider.root}/models"
        Wire.ANTHROPIC -> "${provider.root}/v1/models?limit=1000"
        Wire.GEMINI -> "${provider.root}/models?pageSize=1000"
    }

    private fun responsesPayload(canonical: JSONObject, modelId: String, model: ProviderModel): JSONObject =
        JSONObject(canonical.toString()).apply {
            put("model", modelId)
            put("stream", true)
            put("max_output_tokens", canonical.optInt("max_output_tokens", 4096))
            if (optJSONArray("tools")?.length() == 0) remove("tools")
            if (!model.compat.encryptedReasoning) {
                remove("include")
                optJSONObject("reasoning")?.remove("summary")
            }
        }

    companion object {
        val MODEL_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:/@~-]{0,99}")

        /** A session header value must stay visible ASCII; the same bound as an imported chat id. */
        private val SESSION_ID = Regex("[\\x20-\\x7E]{1,200}")

        /** The client name pi-ai reports upstream, reused for endpoints that require attribution. */
        private const val CLIENT_NAME = "Reasoned"
        private val USER_AGENT = "Reasoned/${BuildConfig.VERSION_NAME} (Android)"

        /** Client identity header each endpoint expects; everything else needs none. */
        private val CLIENT_HEADERS = mapOf(
            "opencode" to "x-opencode-client",
            "opencode-go" to "x-opencode-client",
            "nvidia" to "X-BILLING-INVOKE-ORIGIN",
        )
    }
}

/** Only machine codes reach the UI, never provider prose or credentials. */
internal fun httpFailure(status: Int, body: String): ProviderFailure {
    val mapped = openAiErrorCode(body) ?: when (status) {
        400, 404, 405, 422 -> "invalid_request"
        401, 403 -> "unauthorized"
        408 -> "timeout"
        413 -> "context_limit"
        429 -> "rate_limit"
        in 500..599 -> "provider_unavailable"
        else -> "http_$status"
    }
    if (mapped != "invalid_request") return ProviderFailure(mapped)
    val detail = errorDetail(body) ?: return ProviderFailure(mapped)
    android.util.Log.d("ReasonedRuntime", "invalid_request $status field $detail")
    return ProviderFailure("$mapped:$detail")
}

/** One field name the provider rejected, taken from its top-level error object and nothing deeper. */
private val ERROR_DETAIL = Regex("[A-Za-z0-9_.-]{1,40}")

private fun errorDetail(body: String): String? = try {
    val error = JSONObject(body).optJSONObject("error")
    listOf("param", "code", "type", "status").firstNotNullOfOrNull { key ->
        when (val value = error?.opt(key)) {
            is String -> value.takeIf { it.matches(ERROR_DETAIL) }
            is Number -> value.toString().takeIf { it.matches(ERROR_DETAIL) }
            else -> null
        }
    }
} catch (_: Exception) {
    null
}
