package com.kkgosu.reasoned.api

import com.kkgosu.reasoned.utils.objects
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/** Google Generative Language wire (Gemini). */
internal object GeminiWire {
    fun request(canonical: JSONObject, model: ProviderModel, modelId: String): JSONObject {
        val request = JSONObject()
        canonical.optString("instructions").takeIf { it.isNotBlank() }?.let {
            request.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it))))
        }
        request.put("contents", translate(canonical.optJSONArray("input") ?: throw ProviderFailure("protocol")))
        canonical.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { tools ->
            request.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray(tools.objects().map { tool ->
                if (tool.optString("type") != "function") throw ProviderFailure("tool_denied")
                JSONObject().put("name", tool.getString("name"))
                    .put("description", tool.optString("description"))
                    .put("parameters", schema(tool.getJSONObject("parameters")))
            }))))
            request.put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO")))
        }
        val cap = canonical.optInt("max_output_tokens", 4096)
        val config = JSONObject().put("maxOutputTokens", cap)
        val effort = canonical.optJSONObject("reasoning")?.optString("effort").orEmpty()
        if (effort.isNotEmpty() && model.compat.thinkingParam != null) {
            val thinking = JSONObject().put("includeThoughts", true)
            when (model.compat.thinkingParam) {
                "level" -> thinking.put("thinkingLevel", model.compat.thinkingMap[effort] ?: level(effort))
                else -> thinking.put("thinkingBudget", budget(effort))
            }
            config.put("thinkingConfig", thinking)
            config.put("maxOutputTokens", maxOf(cap, 8192))
        }
        request.put("generationConfig", config)
        return request
    }

    private fun level(effort: String) = when (effort) {
        "none", "minimal" -> "low"
        "low" -> "low"
        "medium" -> "medium"
        else -> "high"
    }

    private fun budget(effort: String) = when (effort) {
        "none" -> 0
        "minimal" -> 128
        "low" -> 2048
        "medium" -> 8192
        "high" -> 24576
        "xhigh" -> 32768
        else -> 32768
    }

    /** Gemini rejects unknown JSON Schema keywords in a function declaration. */
    private fun schema(raw: JSONObject): JSONObject {
        val out = JSONObject()
        raw.keys().forEach { key ->
            if (key == "additionalProperties" || key == "strict" || key == "\$schema") return@forEach
            val value = raw.opt(key)
            out.put(
                key, when (value) {
                    is JSONObject -> schema(value)
                    is JSONArray -> JSONArray((0 until value.length()).map { index ->
                        value.opt(index)?.let { if (it is JSONObject) schema(it) else it }
                    })
                    else -> value
                }
            )
        }
        return out
    }

    private fun translate(input: JSONArray): JSONArray {
        val contents = JSONArray()
        val callNames = mutableMapOf<String, String>()
        var role = ""
        var parts = JSONArray()

        fun flush() {
            if (role.isEmpty() || parts.length() == 0) return
            contents.put(JSONObject().put("role", role).put("parts", parts))
            role = ""; parts = JSONArray()
        }

        fun append(target: String, part: JSONObject) {
            if (role.isNotEmpty() && role != target) flush()
            role = target
            parts.put(part)
        }

        input.objects().forEach { item ->
            when (item.itemType()) {
                "reasoning" -> Unit

                "function_call" -> {
                    callNames[item.getString("call_id")] = item.getString("name")
                    append(
                        "model",
                        JSONObject().put(
                            "functionCall",
                            JSONObject().put("name", item.getString("name"))
                                .put("args", JSONObject(item.optString("arguments", "{}")))
                        )
                    )
                }

                "function_call_output" -> append(
                    "user",
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", callNames[item.getString("call_id")] ?: "tool")
                            .put("response", JSONObject().put("result", item.optString("output")))
                    )
                )

                "message" -> {
                    val text = text(item)
                    if (text.isNotBlank()) append(
                        if (item.optString("role") == "assistant") "model" else "user",
                        JSONObject().put("text", text)
                    )
                }

                else -> throw ProviderFailure("native_runtime_unavailable")
            }
        }
        flush()
        if (contents.length() == 0) throw ProviderFailure("protocol")
        return contents
    }

    private fun text(item: JSONObject): String = when (val content = item.opt("content")) {
        is String -> content
        is JSONArray -> content.objects().joinToString("") { block ->
            when (block.optString("type")) {
                "input_text", "output_text", "text" -> block.optString("text")
                else -> throw ProviderFailure("native_runtime_unavailable")
            }
        }

        else -> ""
    }

    suspend fun read(source: BufferedSource, delta: suspend (String) -> Unit): JSONObject {
        val response = WireResponse()
        var terminal = false
        readSse(source) { _, data ->
            val event = try {
                JSONObject(data)
            } catch (_: Exception) {
                throw ProviderFailure("protocol")
            }
            if (!event.isNull("error")) throw ProviderFailure(status(event.getJSONObject("error").optString("status")))
            event.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
                throw ProviderFailure("refusal")
            }
            event.optJSONObject("usageMetadata")?.let { usage ->
                fun count(key: String) = (usage.opt(key) as? Number)?.toLong()
                response.usage(
                    input = count("promptTokenCount"),
                    output = count("candidatesTokenCount")?.let { it + (count("thoughtsTokenCount") ?: 0) },
                    cached = count("cachedContentTokenCount"),
                    reasoningTokens = count("thoughtsTokenCount"),
                )
            }
            val candidate = event.optJSONArray("candidates")?.objects()?.firstOrNull() ?: return@readSse false
            candidate.optJSONObject("content")?.optJSONArray("parts")?.objects()?.forEachIndexed { index, part ->
                when {
                    part.has("functionCall") -> part.getJSONObject("functionCall").let { call ->
                        response.call(
                            key = "call-$index",
                            id = null,
                            name = call.optString("name").takeIf { it.isNotEmpty() },
                            arguments = call.optJSONObject("args")?.toString() ?: "{}",
                        )
                    }

                    part.optBoolean("thought") -> response.reasoning(part.optString("text"))
                    else -> part.optString("text").takeIf { it.isNotEmpty() }?.let { response.text(it, delta) }
                }
            }
            candidate.optString("finishReason").terminalReason()?.let {
                when (it) {
                    "STOP", "FINISH_REASON_UNSPECIFIED" -> Unit
                    "MAX_TOKENS" -> throw ProviderFailure("output_limit")
                    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> throw ProviderFailure("refusal")
                    else -> throw ProviderFailure("incomplete")
                }
                response.stop()
                terminal = true
            }
            false
        }
        if (!terminal) throw ProviderFailure("incomplete")
        return response.complete()
    }

    private fun status(code: String) = when (code) {
        "RESOURCE_EXHAUSTED" -> "rate_limit"
        "UNAUTHENTICATED", "PERMISSION_DENIED" -> "unauthorized"
        "INVALID_ARGUMENT", "FAILED_PRECONDITION", "NOT_FOUND" -> "invalid_request"
        "UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED" -> "provider_unavailable"
        else -> "incomplete"
    }
}
