package com.kkgosu.reasoned.api

import com.kkgosu.reasoned.controllers.RuntimeTools
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strictString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Port of LearnHarness chatGPTPayload (0795ef197cca, AGPL-3.0; see assets/learnharness/NOTICE.md). */
internal fun chatGptPayload(raw: JSONObject, selectedModel: String): JSONObject {
    val out = JSONObject().put("model", selectedModel).put("store", false).put("stream", true)
    for (key in listOf(
        "instructions",
        "reasoning",
        "include",
        "text",
        "tool_choice",
        "parallel_tool_calls",
        "prompt_cache_key"
    )) {
        if (raw.has(key)) out.put(key, raw.get(key))
    }
    val input = raw.optJSONArray("input") ?: throw ProviderFailure("protocol")
    out.put("input", JSONArray().apply {
        input.objects().forEach { item ->
            put(
                if (item.optString("role") == "system") JSONObject(item.toString()).put(
                    "role",
                    "developer"
                ) else item
            )
        }
    })
    raw.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { tools ->
        if (tools.objects()
                .any { it.optString("type") !in listOf("function", "custom") }
        ) throw ProviderFailure("tool_denied")
        out.put(
            "tools", JSONArray().put(
                JSONObject().put("type", "namespace").put("name", RuntimeTools.NAMESPACE)
                    .put("description", "LearnHarness local learning tools").put("tools", tools)
            )
        )
    }
    return out
}

/** SSE limits apply before allocating a whole line/event. EOF is never successful completion. */
internal suspend fun readResponseStream(
    source: okio.BufferedSource,
    delta: suspend (String) -> Unit
): JSONObject {
    val data = StringBuilder()
    var bytes = 0L
    var visible = 0
    val finished = sortedMapOf<Int, JSONObject>()
    val pending = mutableSetOf<Int>()
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = try {
            source.readUtf8LineStrict(262_144).removePrefix("\uFEFF")
        } catch (_: java.io.EOFException) {
            throw ProviderFailure(if (source.buffer.size > 262_144) "output_limit" else "incomplete")
        }
        bytes += line.toByteArray(Charsets.UTF_8).size + 1
        if (bytes > 2_097_152) throw ProviderFailure("output_limit")
        if (line.isEmpty()) {
            if (data.isEmpty()) continue
            if (data.toString().trim() == "[DONE]") throw ProviderFailure("incomplete")
            val event = try {
                JSONObject(data.toString())
            } catch (_: Exception) {
                throw ProviderFailure("protocol")
            }
            data.setLength(0)
            if (event.optJSONObject("response")?.let { !it.isNull("error") } == true)
                throw ProviderFailure(openAiErrorCode(event.toString()) ?: "incomplete")
            val type = event.optString("type")
            if (type in listOf(
                    "response.created",
                    "response.output_item.added",
                    "response.output_item.done",
                    "response.completed",
                    "response.failed",
                    "response.incomplete",
                    "error"
                )
            )
                android.util.Log.d(
                    "ReasonedRuntime",
                    "stream event=$type; finished=${finished.size}; pending=${pending.size}; visible=$visible"
                )
            when (type) {
                "response.output_text.delta" -> {
                    val text = event.strictString("delta", 24_000, allowEmpty = true)
                    visible += text.length
                    if (visible > 24_000) throw ProviderFailure("output_limit")
                    delta(text)
                }

                "response.output_item.added", "response.output_item.done" -> {
                    val index = event.opt("output_index") as? Int
                        ?: throw ProviderFailure("invalid_output_item_index")
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
                    if (response.optString("status") != "completed" || pending.isNotEmpty()) throw ProviderFailure(
                        "incomplete"
                    )
                    val terminalCount = response.optJSONArray("output")?.length() ?: -1
                    if (finished.isNotEmpty()) {
                        if (finished.keys.withIndex()
                                .any { (expected, actual) -> expected != actual }
                        ) throw ProviderFailure("invalid_output_item_index")
                        val terminal = response.optJSONArray("output")?.objects().orEmpty()
                        // Pi's processResponsesStream uses finished items, with terminal-only encrypted
                        // reasoning backfilled by item ID. Never execute partial added/delta items.
                        finished.values.forEach { item ->
                            if (item.optString("type") == "reasoning" && item.opt("encrypted_content")
                                    .let { it !is String || it.isEmpty() }
                            ) {
                                terminal.firstOrNull {
                                    it.optString("type") == "reasoning" && it.optString(
                                        "id"
                                    ).isNotEmpty() && it.optString("id") == item.optString("id")
                                }
                                    ?.opt("encrypted_content")
                                    ?.let { if (it is String) item.put("encrypted_content", it) }
                            }
                        }
                        response.put("output", JSONArray(finished.values.toList()))
                    }
                    android.util.Log.d(
                        "ReasonedRuntime",
                        "stream completed: terminalItems=$terminalCount; assembled=${finished.size}; visible=$visible"
                    )
                    return response
                }

                "response.failed", "response.incomplete", "error" -> throw ProviderFailure(
                    openAiErrorCode(event.toString()) ?: "incomplete"
                )
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
    val code = listOf(
        root.opt("code"), root.optJSONObject("error")?.opt("code"),
        root.optJSONObject("response")?.optJSONObject("error")?.opt("code"), root.opt("error")
    )
        .filterIsInstance<String>().firstOrNull { it.isNotEmpty() }
    code?.takeIf {
        it in ChatGptAuth.TERMINAL_REFRESH || it in setOf(
            "invalid_client",
            "subscription_sharing_user_not_eligible",
            "subscription_sharing_usage_limit_exceeded",
            "subscription_sharing_usage_unavailable",
            "subscription_sharing_unsupported_capability",
            "subscription_sharing_route_not_supported",
            "subscription_sharing_invalid_user",
            "subscription_sharing_user_unavailable",
            "chatpass_v2_scope_not_authorized",
            "chatpass_v2_invalid_authorization_context"
        )
    }
} catch (_: Exception) {
    null
}

internal fun userMessage(text: String) = JSONObject().put("role", "user").put("content", text)
internal fun toolResult(id: String, output: String) =
    JSONObject().put("type", "function_call_output").put("call_id", id).put("output", output)
