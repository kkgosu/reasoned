package com.kkgosu.reasoned.api

import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.nativeTime
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Native pi ↔ Responses transport seam. No credentials, network or mutation of archived JSON.
 * Signature/id conventions follow upstream-locked pi-ai 1.4.0 openai-responses-shared.js (MIT).
 * Unknown provider response fields stay in reasonedResponses; missing usage is never invented.
 */
internal object NativeResponses {
    private fun id(now: Long) = "msg_${now.toString(36)}_${UUID.randomUUID().toString().take(8)}"
    fun append(chat: NativeChat, message: JSONObject, now: Long): NativeChat {
        val record = chat.snapshot()
        record.getJSONArray("messages").put(message)
        return NativeChat.parse(record.put("updatedAt", nativeTime(now)).put("version", 1).toString())
    }

    fun user(text: String, now: Long) = JSONObject().put("id", id(now)).put("role", "user")
        .put("content", text).put("timestamp", now)

    private fun provider(connection: String) = if (connection == "api") "openai" else "openai-codex"
    private fun api(connection: String) = if (connection == "api") "openai-responses" else "openai-codex-responses"

    fun assistant(response: JSONObject, model: String, connection: String, now: Long): JSONObject {
        val content = JSONArray()
        response.getJSONArray("output").objects().forEach { item ->
            when (item.getString("type")) {
                "message" -> {
                    val text = item.getJSONArray("content").objects().joinToString("") { block ->
                        require(block.getString("type") == "output_text")
                        block.getString("text")
                    }
                    content.put(JSONObject().put("type", "text").put("text", text).apply {
                        if (item.has("id")) put("textSignature", JSONObject().put("v", 1).put("id", item.getString("id"))
                            .put("phase", item.opt("phase")).toString())
                    })
                }
                "reasoning" -> content.put(JSONObject().put("type", "thinking")
                    .put("thinking", item.optJSONArray("summary")?.objects()?.filter { it.optString("type") == "summary_text" }
                        ?.joinToString("\n\n") { it.getString("text") }.orEmpty())
                    .put("thinkingSignature", item.toString()))
                "function_call" -> content.put(JSONObject().put("type", "toolCall")
                    .put("id", item.getString("call_id") + (item.optString("id").takeIf { it.isNotEmpty() }?.let { "|$it" } ?: ""))
                    .put("name", item.getString("name")).put("arguments", JSONObject(item.getString("arguments")))
                    .put("namespace", item.opt("namespace")))
                else -> throw ProviderFailure("invalid_output")
            }
        }
        return JSONObject().put("id", id(now)).put("role", "assistant").put("content", content)
            .put("api", api(connection)).put("provider", provider(connection)).put("model", model)
            .put("stopReason", if (content.objects().any { it.optString("type") == "toolCall" }) "toolUse" else "stop")
            .put("timestamp", now).put("rawStopReason", response.optString("status"))
            .put("responseId", response.opt("id")).put("reasonedResponses", JSONObject(response.toString()))
            .put("usage", usage(response))
    }

    /** Reported money survives refusal/tool rejection even when output cannot be projected. */
    fun usage(response: JSONObject): JSONObject? = response.optJSONObject("usage")?.let { raw ->
        fun count(value: JSONObject, key: String, max: Long = 1_000_000): Long? {
            if (!value.has(key) || value.isNull(key)) return null
            val number = value.get(key) as? Number ?: throw ProviderFailure("invalid_output")
            val result = number.toLong()
            if (result !in 0..max || number.toDouble() != result.toDouble()) throw ProviderFailure("invalid_output")
            return result
        }
        val usage = JSONObject()
        val cached = raw.optJSONObject("input_tokens_details")?.let { count(it, "cached_tokens") }
        val written = raw.optJSONObject("input_tokens_details")?.let { count(it, "cache_write_tokens") }
        count(raw, "input_tokens")?.let {
            val cache = (cached ?: 0) + (written ?: 0)
            if (cache > it) throw ProviderFailure("invalid_output")
            usage.put("input", it - cache)
        }
        if (cached != null) usage.put("cacheRead", cached)
        if (written != null) usage.put("cacheWrite", written)
        raw.optJSONObject("output_tokens_details")?.let { count(it, "reasoning_tokens") }?.let { usage.put("reasoning", it) }
        count(raw, "output_tokens")?.let { usage.put("output", it) }
        count(raw, "total_tokens", 2_000_000)?.let { usage.put("totalTokens", it) }
        val output = usage.opt("output") as? Number
        val reasoning = usage.opt("reasoning") as? Number
        if (output != null && reasoning != null && reasoning.toLong() > output.toLong()) throw ProviderFailure("invalid_output")
        usage
    }

    fun step(usage: JSONObject?): JSONObject = JSONObject().apply {
        for ((source, target) in listOf("input" to "inputTokens", "output" to "outputTokens",
            "cacheRead" to "cacheReadTokens", "cacheWrite" to "cacheWriteTokens", "reasoning" to "reasoningTokens")) {
            if (usage?.has(source) == true) put(target, usage.get(source))
        }
    }

    fun turn(id: String, messageId: String, steps: JSONArray, model: String, connection: String, now: Long) =
        JSONObject().put("id", id).put("messageId", messageId).put("at", nativeTime(now))
            .put("model", "${provider(connection)}:$model").put("steps", steps)

    /** Same append-only/idempotent law as the pinned ChatStore; no historical entries are rewritten. */
    fun appendTurn(chat: NativeChat, entry: JSONObject): NativeChat {
        val root = chat.snapshot()
        val turns = root.optJSONArray("turns") ?: JSONArray().also { root.put("turns", it) }
        if (turns.objects().any { it.optString("id") == entry.getString("id") }) return chat
        turns.put(entry)
        return NativeChat.parse(root.put("version", 1).toString())
    }

    /** Retain bounded terminal data only in the archive, never in error messages/UI or wire replay. */
    fun capture(target: JSONObject, response: JSONObject?): JSONObject = target.apply {
        response?.let {
            runCatching { usage(it) }.getOrNull()?.let { reported -> put("usage", reported) }
            if (it.toString().toByteArray(Charsets.UTF_8).size <= 262_144) {
                put("reasonedResponses", JSONObject(it.toString()))
                put("responseId", it.opt("id")); put("rawStopReason", it.opt("status"))
            }
        }
    }

    fun failure(model: String, connection: String, text: String, aborted: Boolean, reason: String, now: Long,
                response: JSONObject? = null) = capture(JSONObject()
        .put("id", id(now)).put("role", "assistant").put("api", api(connection)).put("provider", provider(connection))
        .put("model", model).put("timestamp", now).put("stopReason", if (aborted) "aborted" else "error")
        .put("errorMessage", reason).put("content", JSONArray().apply {
            if (text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", text))
        }), response)

    fun input(chat: NativeChat, model: String, connection: String, retry: Boolean = false): JSONArray = try {
        val drops = if (retry) chat.state().retryDrops else emptySet()
        val source = if (drops.isEmpty()) chat else NativeChat.parse(chat.snapshot().apply {
            put("messages", JSONArray(getJSONArray("messages").objects().filter { it.getString("id") !in drops }))
        }.toString())
        val out = JSONArray()
        source.modelMessages().objects().forEach { message ->
            when (message.getString("role")) {
                "user" -> {
                    val raw = message.get("content")
                    val blocks = if (raw is String) JSONArray().put(JSONObject().put("type", "text").put("text", raw)) else raw as JSONArray
                    out.put(JSONObject().put("role", "user").put("content", JSONArray(blocks.objects().map { block ->
                        when (block.getString("type")) {
                            "text" -> JSONObject().put("type", "input_text").put("text", block.getString("text"))
                            else -> throw ProviderFailure("native_runtime_unavailable")
                        }
                    })))
                }
                "assistant" -> if (message.optString("stopReason") !in setOf("error", "aborted")) {
                    val same = message.optString("provider") == provider(connection) && message.optString("api") == api(connection)
                    val sameModel = same && message.optString("model") == model
                    message.getJSONArray("content").objects().forEach { block ->
                        when (block.getString("type")) {
                            "thinking" -> if (sameModel && block.optString("thinkingSignature").isNotEmpty()) {
                                val item = JSONObject(block.getString("thinkingSignature"))
                                if (item.optString("type") != "reasoning") throw ProviderFailure("native_runtime_unavailable")
                                out.put(item)
                            }
                            "text" -> {
                                val signature = block.optString("textSignature")
                                val metadata = if (signature.startsWith("{")) JSONObject(signature) else JSONObject().put("id", signature)
                                out.put(JSONObject().put("type", "message").put("role", "assistant").put("status", "completed")
                                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", block.getString("text"))))
                                    .apply {
                                        if (sameModel && metadata.optString("id").matches(Regex("[A-Za-z0-9_-]{1,64}"))) put("id", metadata.getString("id"))
                                        if (metadata.optString("phase") in setOf("commentary", "final_answer")) put("phase", metadata.getString("phase"))
                                    })
                            }
                            "toolCall" -> {
                                val ids = block.getString("id").split('|', limit = 2)
                                require(ids[0].matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "Unsupported provider call identity" }
                                out.put(JSONObject().put("type", "function_call").put("call_id", ids[0])
                                    .put("name", block.getString("name")).put("arguments", block.getJSONObject("arguments").toString()).apply {
                                        if (sameModel && ids.getOrNull(1)?.matches(Regex("fc_[A-Za-z0-9_-]{1,61}")) == true) put("id", ids[1])
                                        if (sameModel) put("namespace", block.opt("namespace"))
                                    })
                            }
                            else -> throw ProviderFailure("native_runtime_unavailable")
                        }
                    }
                }
                "toolResult" -> {
                    val content = message.getJSONArray("content").objects()
                    if (content.any { it.optString("type") != "text" }) throw ProviderFailure("native_runtime_unavailable")
                    val text = content.joinToString("\n") { it.getString("text") }.ifBlank { "(no tool output)" }
                    out.put(toolResult(message.getString("toolCallId").substringBefore('|'), text))
                }
            }
        }
        out
    } catch (_: JSONException) { throw ProviderFailure("native_runtime_unavailable") }
      catch (_: IllegalArgumentException) { throw ProviderFailure("native_runtime_unavailable") }
}
