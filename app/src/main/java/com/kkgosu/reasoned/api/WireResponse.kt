package com.kkgosu.reasoned.api

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.util.UUID

/** Bounded SSE framing shared by every non-OpenAI wire. A caller's terminal event decides success. */
internal suspend fun readSse(
    source: BufferedSource,
    maxBytes: Long = 2_097_152,
    onEvent: suspend (event: String, data: String) -> Boolean,
) {
    val data = StringBuilder()
    var event = ""
    var bytes = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = try {
            source.readUtf8LineStrict(262_144).removePrefix("\uFEFF")
        } catch (_: EOFException) {
            if (source.buffer.size > 262_144) throw ProviderFailure("output_limit")
            return
        }
        bytes += line.toByteArray(Charsets.UTF_8).size + 1
        if (bytes > maxBytes) throw ProviderFailure("output_limit")
        when {
            line.isEmpty() -> {
                if (data.isEmpty()) {
                    event = ""
                    continue
                }
                val payload = data.toString()
                data.setLength(0)
                val name = event
                event = ""
                if (onEvent(name, payload)) return
            }

            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }

            line.startsWith("event:") -> event = line.removePrefix("event:").removePrefix(" ")
            line.startsWith(":") || line.startsWith("id:") || line.startsWith("retry:") -> Unit
            else -> throw ProviderFailure("protocol")
        }
        if (data.length > 262_144) throw ProviderFailure("output_limit")
    }
}

/** Responses-shaped input items carry no `type` for a plain message; every other type is explicit. */
internal fun JSONObject.itemType(): String = optString("type").ifEmpty { "message" }

/** A bounded inline image from a Responses-shaped `input_image` block. */
internal data class InlineImage(val mime: String, val data: String)

private val INLINE_IMAGE = Regex("^data:(image/(?:png|jpeg|webp));base64,([A-Za-z0-9+/]+={0,2})$")

/** Only app-rendered PNG data URLs are accepted; never a remote URL or another scheme. */
internal fun JSONObject.inlineImage(): InlineImage {
    val raw = when (val value = opt("image_url")) {
        is String -> value
        is JSONObject -> value.optString("url")
        else -> ""
    }
    val match = INLINE_IMAGE.matchEntire(raw) ?: throw ProviderFailure("protocol")
    val data = match.groupValues[2]
    if (data.length > 1_048_576) throw ProviderFailure("output_limit")
    return InlineImage(match.groupValues[1], data)
}

/** Android's org.json renders a JSON null as the literal string "null" instead of "". */
internal fun String.terminalReason(): String? = takeIf { it.isNotEmpty() && it != "null" }

/**
 * Provider-neutral accumulator. Every wire parses into the Responses-shaped envelope the
 * runtime already archives, so request translation is the only per-provider code.
 */
internal class WireResponse {
    private data class Call(
        val id: String,
        val name: String,
        val arguments: StringBuilder,
        val signature: String = "",
    )

    private val reasoning = StringBuilder()
    private var signature = ""
    private val text = StringBuilder()
    private val calls = linkedMapOf<String, Call>()
    private var usage: JSONObject? = null
    private var stopped = false
    private var id: String? = null

    suspend fun text(chunk: String, delta: suspend (String) -> Unit) {
        if (text.length + chunk.length > 24_000) throw ProviderFailure("output_limit")
        text.append(chunk)
        delta(chunk)
    }

    fun reasoning(chunk: String) {
        if (reasoning.length + chunk.length > 24_000) throw ProviderFailure("output_limit")
        reasoning.append(chunk)
    }

    /** Provider proof that the reasoning block came from it, replayed verbatim on the next turn. */
    fun signature(value: String) {
        if (signature.length + value.length > 65_536) throw ProviderFailure("output_limit")
        signature += value
    }

    /** Arguments arrive in fragments; a call is keyed by stream index when the id is not yet known. */
    fun call(
        key: String,
        id: String?,
        name: String?,
        arguments: String?,
        signature: String? = null,
    ) {
        val existing = calls[key]
        if (existing == null) {
            if (calls.size >= 16) throw ProviderFailure("invalid_output_item_index")
            calls[key] = Call(
                id = id?.takeIf { it.isNotBlank() } ?: "call_${UUID.randomUUID().toString().take(12)}",
                name = name.orEmpty(),
                arguments = StringBuilder().append(arguments.orEmpty()),
                signature = signature.orEmpty(),
            )
        } else {
            if (id != null && id.isNotBlank() && existing.id != id) throw ProviderFailure("invalid_output_item_index")
            if (!signature.isNullOrEmpty() && existing.signature.length + signature.length > 65_536) {
                throw ProviderFailure("output_limit")
            }
            val signed = if (signature.isNullOrEmpty()) existing.signature
            else existing.signature + signature
            calls[key] = existing.copy(
                name = if (existing.name.isEmpty() && !name.isNullOrBlank()) name else existing.name,
                signature = signed,
            )
            existing.arguments.append(arguments.orEmpty())
        }
        if (calls[key]!!.arguments.length > 65_536) throw ProviderFailure("output_limit")
    }

    fun callName(key: String, name: String) {
        val existing = calls[key] ?: return
        if (existing.name.isNotEmpty() && existing.name != name) throw ProviderFailure("invalid_output_item_index")
        calls[key] = existing.copy(name = name)
    }

    /** Providers report usage in pieces, so each supplied field merges into what earlier events supplied. */
    fun usage(input: Long?, output: Long?, cached: Long? = null, cacheWrite: Long? = null, reasoningTokens: Long? = null) {
        val value = usage ?: JSONObject()
        if (input != null) value.put("input_tokens", input)
        if (output != null) value.put("output_tokens", output)
        if (cached != null || cacheWrite != null) {
            val details = value.optJSONObject("input_tokens_details") ?: JSONObject()
            if (cached != null) details.put("cached_tokens", cached)
            if (cacheWrite != null) details.put("cache_write_tokens", cacheWrite)
            value.put("input_tokens_details", details)
        }
        if (reasoningTokens != null) {
            val details = value.optJSONObject("output_tokens_details") ?: JSONObject()
            details.put("reasoning_tokens", reasoningTokens)
            value.put("output_tokens_details", details)
        }
        if (value.length() > 0) usage = value
    }

    fun stop(responseId: String? = null) {
        stopped = true
        if (!responseId.isNullOrBlank()) id = responseId
    }

    fun complete(): JSONObject {
        if (!stopped) throw ProviderFailure("incomplete")
        if (calls.values.any { it.name.isBlank() }) throw ProviderFailure("invalid_output")
        val output = JSONArray()
        if (reasoning.isNotEmpty()) output.put(
            JSONObject().put("type", "reasoning").put("id", "rs_${token()}")
                .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", reasoning.toString())))
                .apply { if (signature.isNotEmpty()) put("encrypted_content", signature) }
        )
        if (text.isNotEmpty()) output.put(
            JSONObject().put("type", "message").put("id", "msg_${token()}").put("role", "assistant")
                .put("status", "completed")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text.toString())))
        )
        calls.values.forEach { call ->
            output.put(
                JSONObject().put("type", "function_call").put("id", "fc_${token()}")
                    .put("call_id", call.id).put("name", call.name)
                    .put("arguments", call.arguments.toString().ifBlank { "{}" })
                    .apply { if (call.signature.isNotEmpty()) put("thoughtSignature", call.signature) }
            )
        }
        return JSONObject().put("id", id ?: "resp_${token()}").put("status", "completed").put("output", output)
            .apply { usage?.let { put("usage", it) } }
    }

    private fun token() = UUID.randomUUID().toString().replace("-", "").take(16)
}
