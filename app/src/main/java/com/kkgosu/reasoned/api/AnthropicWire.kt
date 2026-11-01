package com.kkgosu.reasoned.api

import com.kkgosu.reasoned.utils.objects
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Messages wire, used directly by Anthropic and by Anthropic-compatible endpoints. */
internal object AnthropicWire {
    fun request(canonical: JSONObject, model: ProviderModel, modelId: String): JSONObject {
        val compat = model.compat
        val thinking = canonical.optJSONObject("reasoning")
            ?.optString("effort")
            ?.takeIf { it.isNotEmpty() && (it != "none" || !compat.canDisable) }
        val budget = budget(thinking)
        val cap = canonical.optInt("max_output_tokens", 4096)
        val request = JSONObject().put("model", modelId).put("stream", true)
            .put("max_tokens", if (budget > 0) maxOf(cap, 4096) + budget else cap)
        canonical.optString("instructions").takeIf { it.isNotBlank() }?.let { request.put("system", it) }
        request.put("messages", translate(canonical.optJSONArray("input") ?: throw ProviderFailure("protocol")))
        canonical.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { tools ->
            request.put("tools", JSONArray(tools.objects().map { tool ->
                if (tool.optString("type") != "function") throw ProviderFailure("tool_denied")
                JSONObject().put("name", tool.getString("name"))
                    .put("description", tool.optString("description"))
                    .put("input_schema", tool.getJSONObject("parameters"))
            }))
            request.put("tool_choice", JSONObject().put("type", "auto").put("disable_parallel_tool_use", true))
        }
        if (budget > 0) {
            request.put(
                "thinking",
                if (compat.adaptive) JSONObject().put("type", "adaptive").put("display", "summarized")
                else JSONObject().put("type", "enabled").put("budget_tokens", budget).put("display", "summarized")
            )
            if (compat.effort && thinking != null) request.put("output_config", JSONObject().put("effort", thinking))
        }
        return request
    }

    private fun budget(effort: String?): Int = when (effort) {
        null -> 0
        "minimal" -> 1024
        "low" -> 2048
        "medium" -> 4096
        "high" -> 8192
        "xhigh" -> 12288
        else -> 16384
    }

    /** Anthropic rejects two consecutive same-role turns, so adjacent blocks merge. */
    private fun translate(input: JSONArray): JSONArray {
        val messages = JSONArray()
        var role = ""
        var blocks = JSONArray()

        fun flush() {
            if (role.isEmpty() || blocks.length() == 0) return
            messages.put(JSONObject().put("role", role).put("content", blocks))
            role = ""; blocks = JSONArray()
        }

        fun append(target: String, block: JSONObject) {
            if (role.isNotEmpty() && role != target) flush()
            role = target
            blocks.put(block)
        }

        input.objects().forEach { item ->
            when (item.itemType()) {
                "reasoning" -> {
                    val signature = item.optString("encrypted_content")
                    val text = item.optJSONArray("summary")?.objects()
                        ?.filter { it.optString("type") == "summary_text" }
                        ?.joinToString("") { it.optString("text") }.orEmpty()
                    if (text.isNotEmpty() && signature.isNotEmpty()) append(
                        "assistant",
                        JSONObject().put("type", "thinking").put("thinking", text).put("signature", signature)
                    )
                }

                "function_call" -> append(
                    "assistant",
                    JSONObject().put("type", "tool_use").put("id", item.getString("call_id"))
                        .put("name", item.getString("name"))
                        .put("input", JSONObject(item.optString("arguments", "{}")))
                )

                "function_call_output" -> append(
                    "user",
                    JSONObject().put("type", "tool_result").put("tool_use_id", item.getString("call_id"))
                        .put("content", item.optString("output"))
                )

                "message" -> {
                    val text = text(item)
                    if (text.isNotBlank()) append(
                        if (item.optString("role") == "assistant") "assistant" else "user",
                        JSONObject().put("type", "text").put("text", text)
                    )
                }

                else -> throw ProviderFailure("native_runtime_unavailable")
            }
        }
        flush()
        if (messages.length() == 0) throw ProviderFailure("protocol")
        return messages
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
        val signatures = mutableMapOf<Int, String>()
        var terminal = false
        readSse(source) { event, data ->
            val payload = try {
                if (data.isBlank()) JSONObject() else JSONObject(data)
            } catch (_: Exception) {
                throw ProviderFailure("protocol")
            }
            when (event.ifEmpty { payload.optString("type") }) {
                "message_start" -> payload.optJSONObject("message")?.let { message ->
                    message.optString("id").takeIf { it.isNotEmpty() }?.let(response::stop)
                    message.optJSONObject("usage")?.let { usage(it, response) }
                }

                "content_block_start" -> {
                    val block = payload.optJSONObject("content_block") ?: return@readSse false
                    val index = payload.optInt("content_block_index", payload.optInt("index", 0))
                    if (block.optString("type") == "tool_use") {
                        response.call(
                            key = "block-$index",
                            id = block.optString("id").takeIf { it.isNotEmpty() },
                            name = block.optString("name").takeIf { it.isNotEmpty() },
                            arguments = null,
                        )
                    }
                }

                "content_block_delta" -> {
                    val index = payload.optInt("index", 0)
                    when (payload.optJSONObject("delta")?.optString("type")) {
                        "text_delta" -> response.text(payload.getJSONObject("delta").optString("text"), delta)
                        "thinking_delta" -> response.reasoning(payload.getJSONObject("delta").optString("thinking"))
                        "signature_delta" -> {
                            val value = payload.getJSONObject("delta").optString("signature")
                            signatures[index] = signatures[index].orEmpty() + value
                        }

                        "input_json_delta" -> response.call(
                            key = "block-$index", id = null, name = null,
                            arguments = payload.getJSONObject("delta").optString("partial_json"),
                        )
                    }
                }

                "content_block_stop" -> {
                    val block = payload.optInt("index", 0)
                    signatures[block]?.let { response.signature(it) }
                }

                "message_delta" -> {
                    payload.optJSONObject("delta")?.optString("stop_reason")?.terminalReason()?.let {
                        when (it) {
                            "end_turn", "tool_use", "stop_sequence", "pause_turn" -> Unit
                            "max_tokens" -> throw ProviderFailure("output_limit")
                            "refusal" -> throw ProviderFailure("refusal")
                            else -> throw ProviderFailure("incomplete")
                        }
                        terminal = true
                    }
                    payload.optJSONObject("usage")?.let { usage(it, response) }
                }

                "message_stop" -> terminal = true

                "error" -> throw ProviderFailure(
                    when (payload.optJSONObject("error")?.optString("type")) {
                        "overloaded_error" -> "provider_unavailable"
                        "authentication_error", "permission_error" -> "unauthorized"
                        "rate_limit_error" -> "rate_limit"
                        "invalid_request_error" -> "invalid_request"
                        else -> "incomplete"
                    }
                )

                else -> Unit
            }
            false
        }
        if (!terminal) throw ProviderFailure("incomplete")
        return response.complete()
    }

    private fun usage(raw: JSONObject, response: WireResponse) {
        fun count(key: String): Long? = (raw.opt(key) as? Number)?.toLong()
        val cached = count("cache_read_input_tokens")
        val written = count("cache_creation_input_tokens")
        val base = count("input_tokens")
        response.usage(
            input = base?.let { it + (cached ?: 0) + (written ?: 0) },
            output = count("output_tokens"),
            cached = cached,
            cacheWrite = written,
        )
    }
}
