package com.kkgosu.reasoned.api

import com.kkgosu.reasoned.utils.objects
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Chat Completions wire. Translates the app's canonical Responses-shaped request into
 * chat messages and folds the stream back into the same canonical response envelope.
 */
internal object ChatWire {
    fun request(canonical: JSONObject, model: ProviderModel, modelId: String): JSONObject {
        val compat = model.compat
        val messages = JSONArray()
        canonical.optString("instructions").takeIf { it.isNotBlank() }?.let {
            messages.put(
                JSONObject().put("role", if (compat.developerRole) "developer" else "system").put("content", it)
            )
        }
        translate(canonical.optJSONArray("input") ?: throw ProviderFailure("protocol"), compat).also { rows ->
            (0 until rows.length()).forEach { messages.put(rows.get(it)) }
        }

        val request = JSONObject().put("model", modelId).put("messages", messages).put("stream", true)
        canonical.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { tools ->
            request.put("tools", JSONArray(tools.objects().map { tool ->
                if (tool.optString("type") != "function") throw ProviderFailure("tool_denied")
                JSONObject().put("type", "function").put(
                    "function", JSONObject().put("name", tool.getString("name"))
                        .put("description", tool.optString("description"))
                        .put("parameters", tool.getJSONObject("parameters"))
                        .apply { if (compat.strict && tool.optBoolean("strict")) put("strict", true) }
                )
            }))
            request.put("tool_choice", "auto")
            if (compat.thinkingFormat == "openai") request.put("parallel_tool_calls", false)
        }
        val thinking = canonical.optJSONObject("reasoning")?.let { applyReasoning(request, it, model) } == true
        // Reasoning tokens share this budget, so a thinking turn gets room to answer afterwards.
        val cap = canonical.optInt("max_output_tokens", 4096)
        request.put(compat.maxTokensField, if (thinking) maxOf(cap, 8192) else cap)
        return request
    }

    /** One assistant message per turn: text and tool calls are buffered until the turn closes. */
    private fun translate(input: JSONArray, compat: ModelCompat): JSONArray {
        val messages = JSONArray()
        var content = StringBuilder()
        var toolCalls = JSONArray()
        var reasoning = StringBuilder()

        fun flush() {
            if (content.isEmpty() && toolCalls.length() == 0) {
                reasoning = StringBuilder()
                return
            }
            val message = JSONObject().put("role", "assistant")
            if (content.isNotEmpty()) message.put("content", content.toString())
            if (toolCalls.length() > 0) message.put("tool_calls", toolCalls)
            if (compat.reasoningContent && reasoning.isNotEmpty()) message.put("reasoning_content", reasoning.toString())
            messages.put(message)
            content = StringBuilder(); toolCalls = JSONArray(); reasoning = StringBuilder()
        }

        input.objects().forEach { item ->
            when (item.itemType()) {
                "reasoning" -> if (compat.reasoningContent) {
                    reasoning = StringBuilder()
                    item.optJSONArray("summary")?.objects()
                        ?.filter { it.optString("type") == "summary_text" }
                        ?.forEach { reasoning.append(it.optString("text")) }
                }

                "function_call" -> toolCalls.put(
                    JSONObject().put("id", item.getString("call_id")).put("type", "function")
                        .put("function", JSONObject().put("name", item.getString("name"))
                            .put("arguments", item.optString("arguments", "{}")))
                )

                "function_call_output" -> {
                    flush()
                    messages.put(
                        JSONObject().put("role", "tool").put("tool_call_id", item.getString("call_id"))
                            .put("content", item.optString("output"))
                    )
                }

                "message" -> {
                    val text = text(item)
                    if (item.optString("role") == "assistant") content.append(text)
                    else {
                        flush()
                        if (text.isNotBlank()) messages.put(
                            JSONObject().put("role", item.optString("role", "user")).put("content", text)
                        )
                    }
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

    /** pi-ai's per-format thinking parameters; returns whether thinking was actually enabled. */
    private fun applyReasoning(request: JSONObject, reasoning: JSONObject, model: ProviderModel): Boolean {
        if (model.efforts.isEmpty()) return false
        val compat = model.compat
        val requested = reasoning.optString("effort").takeIf { it.isNotEmpty() && it != "none" }
        val mapped = requested?.let(compat::effortValue)
        val off = compat.thinkingMap["off"]
        when (compat.thinkingFormat) {
            "zai" -> {
                request.put("thinking", JSONObject().put("type", if (requested == null) "disabled" else "enabled")
                    .apply { if (requested != null) put("clear_thinking", false) })
                if (requested != null && compat.reasoningEffort && mapped != null) request.put("reasoning_effort", mapped)
            }

            "deepseek" -> {
                if (requested != null) request.put("thinking", JSONObject().put("type", "enabled"))
                else if (off != null) request.put("thinking", JSONObject().put("type", "disabled"))
                if (requested != null && compat.reasoningEffort && mapped != null) request.put("reasoning_effort", mapped)
            }

            "openrouter" -> when {
                requested != null -> request.put("reasoning", JSONObject().put("effort", mapped ?: requested))
                off != null -> request.put("reasoning", JSONObject().put("effort", off))
            }

            "together" -> {
                request.put("reasoning", JSONObject().put("enabled", requested != null))
                if (requested != null && compat.reasoningEffort && mapped != null) request.put("reasoning_effort", mapped)
            }

            "ant-ling" -> if (requested != null && mapped != null) request.put(
                "reasoning", JSONObject().put("effort", mapped)
            )

            else -> when {
                requested != null && compat.reasoningEffort -> mapped?.let { request.put("reasoning_effort", it) }
                requested == null && off != null -> request.put("reasoning_effort", off)
            }
        }
        return requested != null
    }

    suspend fun read(source: BufferedSource, delta: suspend (String) -> Unit): JSONObject {
        val response = WireResponse()
        var terminal = false
        readSse(source) { _, data ->
            if (data == "[DONE]") return@readSse true
            val event = try {
                JSONObject(data)
            } catch (_: Exception) {
                throw ProviderFailure("protocol")
            }
            if (!event.isNull("error")) throw ProviderFailure(
                openAiErrorCode(JSONObject().put("error", event.opt("error")).toString()) ?: "incomplete"
            )
            event.optString("id").takeIf { it.isNotEmpty() }?.let(response::stop)
            usage(event.optJSONObject("usage") ?: event.optJSONObject("x_groq")?.optJSONObject("usage"), response)
            val choice = event.optJSONArray("choices")?.objects()?.firstOrNull() ?: return@readSse false
            choice.optJSONObject("delta")?.let { part ->
                when (val chunk = part.opt("content")) {
                    is String -> if (chunk.isNotEmpty()) response.text(chunk, delta)
                    is JSONArray -> chunk.objects().forEach { block ->
                        block.optString("text").takeIf { it.isNotEmpty() }?.let { response.text(it, delta) }
                    }
                }
                when (val reasoning = part.opt("reasoning_content") ?: part.opt("reasoning")) {
                    is String -> response.reasoning(reasoning)
                }
                part.optJSONArray("tool_calls")?.objects()?.forEach { call ->
                    val function = call.optJSONObject("function")
                    response.call(
                        key = "call-${call.optInt("index", 0)}",
                        id = call.optString("id").takeIf { it.isNotEmpty() },
                        name = function?.optString("name")?.takeIf { it.isNotEmpty() },
                        arguments = function?.optString("arguments"),
                    )
                }
            }
            val finish = choice.optString("finish_reason").terminalReason()
            if (finish != null) {
                when (finish) {
                    "stop", "end", "tool_calls", "function_call" -> Unit
                    "length" -> throw ProviderFailure("output_limit")
                    "content_filter" -> throw ProviderFailure("refusal")
                    else -> throw ProviderFailure("incomplete")
                }
                terminal = true
            }
            false
        }
        if (!terminal) throw ProviderFailure("incomplete")
        return response.complete()
    }

    private fun usage(raw: JSONObject?, response: WireResponse) {
        raw ?: return
        fun JSONObject.count(key: String): Long? = (opt(key) as? Number)?.toLong()
        val details = raw.optJSONObject("prompt_tokens_details")
        response.usage(
            input = raw.count("prompt_tokens") ?: raw.count("input_tokens"),
            output = raw.count("completion_tokens") ?: raw.count("output_tokens"),
            cached = details?.count("cached_tokens"),
            reasoningTokens = raw.optJSONObject("completion_tokens_details")?.count("reasoning_tokens"),
        )
    }
}
