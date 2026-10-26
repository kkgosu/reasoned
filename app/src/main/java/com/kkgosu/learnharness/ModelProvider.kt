package com.kkgosu.learnharness

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The fake provider exercises the same runner without credentials or network. */
fun interface ModelProvider {
    suspend fun respond(request: JSONObject, delta: suspend (String) -> Unit): JSONObject
}

class ProviderFailure(val reason: String) : IOException(reason)

internal fun openAiHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

class OpenAiProvider(
    private val accessToken: suspend () -> String,
    private val client: OkHttpClient = openAiHttpClient(),
) : ModelProvider {
    constructor(credentials: CredentialStore, client: OkHttpClient = openAiHttpClient()) :
        this({ credentials.read() ?: throw ProviderFailure("key_missing") }, client)

    internal suspend fun models(): List<String> {
        val token = accessToken()
        val call = client.newCall(Request.Builder().url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $token").build())
        return coroutineScope {
            val reader = async(Dispatchers.IO) {
                call.execute().use { response ->
                    val source = response.body.source()
                    if (source.request(2_097_153)) throw ProviderFailure("model_catalog_limit")
                    val body = source.readUtf8()
                    if (!response.isSuccessful) throw ProviderFailure(openAiErrorCode(body) ?: "http_${response.code}")
                    JSONObject(body).getJSONArray("data").objects().map { it.strictString("id", 100) }
                        .filter { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) && !it.startsWith("sk-") }.distinct().take(500)
                }
            }
            try { reader.await() } finally { call.cancel() }
        }
    }

    override suspend fun respond(request: JSONObject, delta: suspend (String) -> Unit): JSONObject {
        val key = accessToken()
        val call = client.newCall(Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream")
            .post(request.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        // Cancellation must close a blocked body read as well as a request awaiting headers.
        return coroutineScope {
            val reader = async(Dispatchers.IO) {
                try {
                    call.execute().use {
                        if (!it.isSuccessful) {
                            val source = it.body.source()
                            val body = if (source.request(65_537)) "" else source.readUtf8()
                            throw ProviderFailure(openAiErrorCode(body) ?: when (it.code) {
                                401, 403 -> "unauthorized"
                                429 -> "rate_limit"
                                else -> "http_${it.code}"
                            })
                        }
                        // Temporary format-only diagnostics: never log headers, tokens or response content.
                        android.util.Log.d("ReasonedRuntime", "transport status=${it.code}; parsing SSE")
                        // Plan responses can carry SSE under a different media type. Validate the wire
                        // framing and completed response, not the header label; never accept JSON fallback.
                        readResponseStream(it.body.source(), delta)
                    }
                } catch (error: IOException) {
                    // Closing a blocked socket can throw IOException after its coroutine was cancelled.
                    currentCoroutineContext().ensureActive()
                    throw error
                }
            }
            try { reader.await() } finally { call.cancel() }
        }
    }
}

/** Port of LearnHarness chatGPTPayload (0795ef197cca, AGPL-3.0; see assets/learnharness/NOTICE.md). */
internal fun chatGptPayload(raw: JSONObject, selectedModel: String): JSONObject {
    val out = JSONObject().put("model", selectedModel).put("store", false).put("stream", true)
    for (key in listOf("instructions", "reasoning", "include", "text", "tool_choice", "parallel_tool_calls", "prompt_cache_key")) {
        if (raw.has(key)) out.put(key, raw.get(key))
    }
    val input = raw.optJSONArray("input") ?: throw ProviderFailure("protocol")
    out.put("input", JSONArray().apply {
        input.objects().forEach { item ->
            put(if (item.optString("role") == "system") JSONObject(item.toString()).put("role", "developer") else item)
        }
    })
    raw.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { tools ->
        if (tools.objects().any { it.optString("type") !in listOf("function", "custom") }) throw ProviderFailure("tool_denied")
        out.put("tools", JSONArray().put(JSONObject().put("type", "namespace").put("name", RuntimeTools.NAMESPACE)
            .put("description", "LearnHarness local learning tools").put("tools", tools)))
    }
    return out
}

/** SSE limits apply before allocating a whole line/event. EOF is never successful completion. */
internal suspend fun readResponseStream(source: okio.BufferedSource, delta: suspend (String) -> Unit): JSONObject {
    val data = StringBuilder()
    var bytes = 0L
    var visible = 0
    val finished = sortedMapOf<Int, JSONObject>()
    val pending = mutableSetOf<Int>()
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = try { source.readUtf8LineStrict(262_144).removePrefix("\uFEFF") }
        catch (_: java.io.EOFException) {
            throw ProviderFailure(if (source.buffer.size > 262_144) "output_limit" else "incomplete")
        }
        bytes += line.toByteArray(Charsets.UTF_8).size + 1
        if (bytes > 2_097_152) throw ProviderFailure("output_limit")
        if (line.isEmpty()) {
            if (data.isEmpty()) continue
            if (data.toString().trim() == "[DONE]") throw ProviderFailure("incomplete")
            val event = try { JSONObject(data.toString()) } catch (_: Exception) { throw ProviderFailure("protocol") }
            data.setLength(0)
            if (event.optJSONObject("response")?.let { !it.isNull("error") } == true)
                throw ProviderFailure(openAiErrorCode(event.toString()) ?: "incomplete")
            val type = event.optString("type")
            if (type in listOf("response.created", "response.output_item.added", "response.output_item.done", "response.completed", "response.failed", "response.incomplete", "error"))
                android.util.Log.d("ReasonedRuntime", "stream event=$type; finished=${finished.size}; pending=${pending.size}; visible=$visible")
            when (type) {
                "response.output_text.delta" -> {
                    val text = event.strictString("delta", 24_000, allowEmpty = true)
                    visible += text.length
                    if (visible > 24_000) throw ProviderFailure("output_limit")
                    delta(text)
                }
                "response.output_item.added", "response.output_item.done" -> {
                    val index = event.opt("output_index") as? Int ?: throw ProviderFailure("invalid_output_item_index")
                    if (index !in 0..15 || finished.containsKey(index)) throw ProviderFailure("invalid_output_item_index")
                    if (event.getString("type") == "response.output_item.added") {
                        if (!pending.add(index)) throw ProviderFailure("invalid_output_item_index")
                    } else {
                        finished[index] = event.getJSONObject("item")
                        pending.remove(index)
                    }
                }
                "response.completed" -> {
                    val response = event.getJSONObject("response")
                    if (response.optString("status") != "completed" || pending.isNotEmpty()) throw ProviderFailure("incomplete")
                    val terminalCount = response.optJSONArray("output")?.length() ?: -1
                    if (finished.isNotEmpty()) {
                        if (finished.keys.withIndex().any { (expected, actual) -> expected != actual }) throw ProviderFailure("invalid_output_item_index")
                        val terminal = response.optJSONArray("output")?.objects().orEmpty()
                        // Pi's processResponsesStream uses finished items, with terminal-only encrypted
                        // reasoning backfilled by item ID. Never execute partial added/delta items.
                        finished.values.forEach { item ->
                            if (item.optString("type") == "reasoning" && item.opt("encrypted_content").let { it !is String || it.isEmpty() }) {
                                terminal.firstOrNull { it.optString("type") == "reasoning" && it.optString("id").isNotEmpty() && it.optString("id") == item.optString("id") }
                                    ?.opt("encrypted_content")?.let { if (it is String) item.put("encrypted_content", it) }
                            }
                        }
                        response.put("output", JSONArray(finished.values.toList()))
                    }
                    android.util.Log.d("ReasonedRuntime", "stream completed: terminalItems=$terminalCount; assembled=${finished.size}; visible=$visible")
                    return response
                }
                "response.failed", "response.incomplete", "error" -> throw ProviderFailure(openAiErrorCode(event.toString()) ?: "incomplete")
                // Tool arguments are deliberately not executable until response.completed validates the full output.
            }
        } else if (line == "data" || line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substringAfter(':', "").removePrefix(" "))
            if (data.length > 262_144) throw ProviderFailure("output_limit")
        } else if (!line.startsWith(":")) {
            when (line.substringBefore(':')) {
                "event", "id", "retry" -> Unit
                else -> throw ProviderFailure("protocol")
            }
        }
    }
}

/** Only known machine codes reach UI/checkpoints, never arbitrary provider messages or tokens. */
internal fun openAiErrorCode(body: String): String? = try {
    val root = JSONObject(body)
    val code = listOf(root.opt("code"), root.optJSONObject("error")?.opt("code"),
        root.optJSONObject("response")?.optJSONObject("error")?.opt("code"), root.opt("error"))
        .filterIsInstance<String>().firstOrNull { it.isNotEmpty() }
    code?.takeIf { it in ChatGptAuth.TERMINAL_REFRESH || it in setOf(
        "invalid_client", "subscription_sharing_user_not_eligible", "subscription_sharing_usage_limit_exceeded",
        "subscription_sharing_usage_unavailable", "subscription_sharing_unsupported_capability", "subscription_sharing_route_not_supported",
        "subscription_sharing_invalid_user", "subscription_sharing_user_unavailable", "chatpass_v2_scope_not_authorized",
        "chatpass_v2_invalid_authorization_context") }
} catch (_: Exception) { null }

internal fun JSONObject.strictString(name: String, max: Int, allowEmpty: Boolean = false): String {
    val value = opt(name) as? String ?: throw ProviderFailure("invalid_output")
    if (value.length > max || (!allowEmpty && value.isBlank())) throw ProviderFailure("invalid_output")
    return value
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun userMessage(text: String) = JSONObject().put("role", "user").put("content", text)
internal fun toolResult(id: String, output: String) = JSONObject().put("type", "function_call_output").put("call_id", id).put("output", output)
