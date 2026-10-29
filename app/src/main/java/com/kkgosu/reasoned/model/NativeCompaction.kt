package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.api.NativeResponses
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** LearnHarness 3dd52110 core/compaction.ts (AGPL-3.0): record-id cuts, never transcript deletion.
 * The cutoff/token walk follows its bounded Pi 1.4.0 port (MIT; see upstream compaction notice).
 */
internal object NativeCompaction {
    const val HEADER = "[SESSION HISTORY COMPACTED] The transcript before this point was summarized to fit the context window; the summary below replaces it. Everything after this message is the verbatim recent transcript."

    fun active(messages: List<JSONObject>): JSONObject? = messages.lastOrNull {
        it.optString("role") == "custom" && it.optString("customType") == "compaction" &&
            it.optJSONObject("details")?.opt("firstKeptMessageId") is String
    }

    private fun kept(messages: List<JSONObject>, marker: JSONObject?): Int = if (marker == null) -1 else
        messages.indexOfFirst { it.optString("id") == marker.getJSONObject("details").getString("firstKeptMessageId") }

    fun tokens(message: JSONObject): Long {
        val content = message.opt("content")
        val chars = if (message.optString("role") == "assistant") {
            (content as? JSONArray)?.objects().orEmpty().sumOf { block ->
                when (block.optString("type")) {
                    "text" -> block.optString("text").length.toLong()
                    "thinking" -> block.optString("thinking").length.toLong()
                    "toolCall" -> block.optString("name").length.toLong() + (block.optJSONObject("arguments")?.toString() ?: "{}").length
                    else -> 0L
                }
            }
        } else when (content) {
            is String -> content.length.toLong()
            is JSONArray -> (0 until content.length()).sumOf { index ->
                when (val block = content.get(index)) {
                    is String -> block.length.toLong()
                    is JSONObject -> when (block.optString("type")) {
                        "image" -> 4800L
                        "text" -> block.optString("text").length.toLong()
                        else -> 0L
                    }
                    else -> 0L
                }
            }
            else -> 0L
        }
        return (chars + 3) / 4
    }

    private fun usageTokens(message: JSONObject): Long = message.optJSONObject("usage")?.let { usage ->
        usage.optLong("totalTokens").takeIf { it != 0L }
            ?: listOf("input", "output", "cacheRead", "cacheWrite").sumOf { usage.optLong(it) }
    } ?: 0

    fun estimate(chat: NativeChat, overhead: Long): Long {
        val messages = chat.snapshot().getJSONArray("messages").objects()
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (message.optString("role") == "custom" && message.optString("customType") == "compaction") break
            if (message.optString("role") == "assistant" && message.optString("stopReason") !in setOf("error", "aborted")) {
                val usage = usageTokens(message)
                if (usage > 0) return usage + messages.drop(index + 1).sumOf(::tokens)
            }
        }
        return overhead + chat.modelMessages().objects().sumOf(::tokens)
    }

    private fun visible(message: JSONObject) = message.optString("role") != "custom" ||
        message.optString("customType") !in setOf("compaction", "plan-block", "problem-dismissed")

    /** Pi's next valid entry, including the preceding call when a large trailing result fills the tail. */
    fun cut(chat: NativeChat, keep: Long = 20_000): Int {
        val messages = chat.snapshot().getJSONArray("messages").objects()
        val start = kept(messages, active(messages)).coerceAtLeast(0)
        val points = (start until messages.size).filter { visible(messages[it]) && messages[it].optString("role") in setOf("user", "assistant", "custom") }
        if (points.isEmpty()) return start
        var cut = points.first()
        var accumulated = 0L
        for (index in messages.lastIndex downTo start) {
            val count = if (visible(messages[index])) tokens(messages[index]) else 0L
            if (count == 0L) continue
            accumulated += count
            if (accumulated >= keep) {
                cut = points.firstOrNull { it >= index } ?: points.last()
                break
            }
        }
        while (cut > start) {
            val before = messages[cut - 1]
            if (before.optString("customType") == "compaction" || visible(before)) break
            cut--
        }
        return cut
    }

    fun span(chat: NativeChat, cut: Int): String {
        val messages = chat.snapshot().getJSONArray("messages").objects()
        val previous = active(messages)
        val start = kept(messages, previous).coerceAtLeast(0)
        if (cut <= start || cut >= messages.size) throw ProviderFailure("compaction_no_progress")
        val prior = previous?.takeIf { start > 0 }?.getString("content")
        val lines = mutableListOf<String>()
        if (prior != null) lines += "[SYSTEM NOTE — prior compaction summary, still authoritative where not superseded]\n$prior"
        messages.subList(start, cut).forEach { message ->
            val content = message.opt("content")
            when (message.getString("role")) {
                "user" -> lines += "[LEARNER] $content"
                "assistant" -> {
                    val blocks = (content as JSONArray).objects().mapNotNull {
                        when (it.optString("type")) {
                            "text" -> it.getString("text")
                            "toolCall" -> "(tool call ${it.getString("name")}: ${it.getJSONObject("arguments")})"
                            else -> null // Private scratch/signatures are not learner evidence.
                        }
                    }
                    if (blocks.isNotEmpty()) lines += "[TUTOR] ${blocks.joinToString("\n")}"
                }
                "toolResult" -> {
                    val text = (content as JSONArray).objects().joinToString("\n") {
                        if (it.optString("type") == "text") it.getString("text") else it.toString()
                    }
                    lines += "[RESULT for ${message.getString("toolName")}${if (message.optBoolean("isError")) " (error)" else ""}] " +
                        if (text.length > 2000) text.take(2000) + "…[truncated]" else text
                }
                "custom" -> if (message.optString("customType") != "plan-block" &&
                    !(prior != null && message.optString("customType") == "compaction")) lines += "[SYSTEM NOTE] $content"
            }
        }
        return lines.joinToString("\n\n").also { if (it.length > 1_000_000) throw ProviderFailure("compaction_input_limit") }
    }

    fun plan(chat: NativeChat): String {
        val tree = chat.snapshot().optJSONObject("tree")
        val nodes = tree?.getJSONArray("nodes")?.objects().orEmpty()
        if (nodes.isEmpty()) return "No plan tree exists in this session (nothing was persisted)."
        val state = chat.state()
        val byId = nodes.associateBy { it.getString("id") }
        return (nodes.map { node ->
            val id = node.getString("id")
            val status = if (state.current == id) "current" else if (id in state.visited) "visited" else "upcoming"
            "- [$status] ${node.getString("title")} ($id)" + node.optString("summary").takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty()
        } + tree!!.getJSONArray("edges").objects().map { edge ->
            val from = edge.getString("from"); val to = edge.getString("to")
            "- ${byId.getValue(from).getString("title")} → ${byId.getValue(to).getString("title")} ($from → $to)"
        }).joinToString("\n")
    }

    /** A marker and its native money-channel entry commit together; the summarizer is not teaching. */
    fun complete(chat: NativeChat, cut: Int, summary: String, before: Long, model: String,
                 connection: String, response: JSONObject, now: Long, turnId: String = UUID.randomUUID().toString()): NativeChat {
        require(summary.isNotBlank() && summary.length <= 24_000)
        span(chat, cut) // Also rejects repeated/backward/missing boundaries.
        val root = chat.snapshot()
        val messages = root.getJSONArray("messages")
        val id = "msg_${UUID.randomUUID()}"
        val qualified = "${if (connection == "api") "openai" else "openai-codex"}:$model"
        val at = nativeTime(now)
        val usage = NativeResponses.assistant(response, model, connection, now).optJSONObject("usage")
        val step = NativeResponses.step(usage)
        messages.put(JSONObject().put("id", id).put("role", "custom").put("customType", "compaction")
            .put("content", "$HEADER\n\n${summary.trim()}").put("display", true).put("timestamp", now)
            .put("details", JSONObject().put("firstKeptMessageId", messages.getJSONObject(cut).getString("id"))
                .put("tokensBefore", before).put("model", qualified).put("at", at))
            .put("reasonedResponses", JSONObject(response.toString())))
        return NativeResponses.appendTurn(NativeChat.parse(root.put("version", 1).put("updatedAt", at).toString()),
            NativeResponses.turn(turnId, id, JSONArray().put(step), model, connection, now)
                .put("reasonedPurpose", "compaction").put("reasonedStatus", "completed"))
    }
}
