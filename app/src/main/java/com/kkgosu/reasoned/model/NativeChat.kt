package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Immutable original LearnHarness record, not a Responses request or an Android checkpoint.
 * Folds/projection follow Amos Blomqvist's LearnHarness 3dd52110bbcbcba81645453ce0a8fdc230594c47
 * (AGPL v3): core/native-messages.ts, phases.ts, contracts/plan.ts and input-events.ts.
 * This is not the external-package validator or a provider execution adapter.
 */
internal class NativeChat private constructor(val raw: String, val id: String) {
    // Never expose mutable canonical JSON. Unknown fields, signatures and inline artifacts stay raw.
    fun snapshot() = JSONObject(raw)
    override fun equals(other: Any?) = other is NativeChat && raw == other.raw
    override fun hashCode() = raw.hashCode()

    /** -1 identifies the whole message; array indices identify its ordered content blocks. */
    data class Part(val message: String, val index: Int = -1)

    fun parts(): List<Part> = snapshot().getJSONArray("messages").objects().flatMap { message ->
        val id = message.getString("id")
        listOf(Part(id)) + (0 until (message.optJSONArray("content")?.length() ?: 0))
            .map { Part(id, it) }
    }

    data class Call(val id: String, val tool: String, val input: JSONObject?)
    data class State(
        val phase: String,
        val pending: Call?,
        val activeProblem: Call?,
        val pendingDismissal: String?,
        val continuation: Boolean,
        val current: String?,
        val visited: Set<String>,
        val retryDrops: Set<String>,
    )

    /** Read-only native restoration. A continuation flag is never permission to run anything. */
    fun state(): State {
        val record = snapshot()
        val messages = record.getJSONArray("messages").objects()
        val clean = messages.filter { it.optString("role") == "toolResult" && !it.optBoolean("isError") }
            .map { it.optString("toolCallId") }.toSet()
        val probeCalls = mutableSetOf<String>()
        var phase = "probe"
        val nodes = record.optJSONObject("tree")?.optJSONArray("nodes")?.objects()
            ?.map { it.optString("id") }?.toSet().orEmpty()
        val visits = mutableListOf<String>()
        messages.forEach { message ->
            when (message.optString("role")) {
                "assistant" -> {
                    message.calls().forEach { block ->
                        val tool = block.optString("name")
                        if (!message.failed() && tool == "probe_done") probeCalls += block.getString("id")
                        val node = block.optJSONObject("arguments")?.opt("nodeId") as? String
                        if (tool in setOf("quiz", "explain") && !node.isNullOrEmpty()) {
                            if (phase == "plan") phase = "teach"
                            if (node in nodes && visits.lastOrNull() != node) visits += node
                        }
                    }
                }
                "toolResult" -> if (phase == "probe" && message.optString("toolName") == "probe_done" &&
                    message.optString("toolCallId") in probeCalls && !message.optBoolean("isError") &&
                    message.optJSONObject("details")?.opt("ok") != false
                ) phase = "plan"
            }
        }
        // The newest assistant owns the pending-question dock, even if older calls dangle.
        val newest = messages.indexOfLast { it.optString("role") == "assistant" }
        val answered = messages.drop(newest + 1).filter { it.optString("role") == "toolResult" }
            .map { it.optString("toolCallId") }.toSet()
        val pending = messages.getOrNull(newest)?.calls()?.firstOrNull {
            it.optString("name") in QUESTION_TOOLS && it.optString("id") !in answered
        }?.asCall()
        var active: Call? = null
        var dismissal: String? = null
        var activeDecided = false
        var dismissalDecided = false
        for (message in messages.asReversed()) {
            if (message.optString("role") == "custom" && message.optString("customType") == "problem-dismissed") {
                if (!activeDecided) activeDecided = true
                if (!dismissalDecided) {
                    dismissal = message.optJSONObject("details")?.opt("problemCallId") as? String
                    dismissalDecided = true
                }
            }
            for (block in message.calls().asReversed()) {
                if (block.optString("id") !in clean) continue
                when (block.optString("name")) {
                    "problem" -> {
                        if (!activeDecided) { active = block.asCall(); activeDecided = true }
                        dismissalDecided = true
                    }
                    "conclude" -> { activeDecided = true; dismissalDecided = true }
                }
            }
            if (activeDecided && dismissalDecided) break
        }
        val drops = linkedSetOf<String>()
        var before = messages.lastIndex
        while (before >= 0) {
            val message = messages[before]
            if (message.optString("role") == "assistant" && message.optString("stopReason") == "error")
                drops += message.getString("id")
            else if (!(message.optString("role") == "custom" && message.optString("customType") == "plan-block")) break
            before--
        }
        val retry = if (before >= 0 && messages[before].optString("role") != "assistant") drops else emptySet()
        return State(phase, pending, active, dismissal, needsContinuation(messages),
            visits.lastOrNull(), visits.dropLast(1).toSet(), retry)
    }

    /** A pending question is a projection of its original call, never a persisted placeholder. */
    fun pendingQuestion(): NativeQuestion? {
        val state = state()
        val call = state.pending ?: return null
        val owner = snapshot().getJSONArray("messages").objects().last { it.optString("role") == "assistant" }
        require(!owner.failed()) { "A failed generation cannot pose a native question" }
        return NativeQuestion.parse(call, state.phase)
    }

    /** Explicit learner action only. Produces a new record; does not save, continue or call providers. */
    fun answerPending(callId: String, request: JSONObject, now: Long = System.currentTimeMillis()): NativeChat {
        val pending = pendingQuestion()
        require(pending != null && pending.question.callId == callId) { "Answer targets no pending native question" }
        return appendAnswer(callId, pending.tool, pending.answer(request, now), now)
    }

    fun dismissQuestion(callId: String, now: Long = System.currentTimeMillis()): NativeChat {
        val pending = pendingQuestion()
        require(pending != null && pending.question.callId == callId) { "Dismissal targets no pending native question" }
        val answer = JSONObject().put("kind", pending.kind).put("questionText", pending.question.text)
            .put("dontKnow", false).put("answers", JSONArray()).put("dismissed", true).put("answeredAt", nativeTime(now))
        return appendAnswer(callId, pending.tool, answer, now)
    }

    private fun appendAnswer(callId: String, tool: String, answer: JSONObject, now: Long): NativeChat {
        val record = snapshot()
        record.getJSONArray("messages").put(JSONObject()
            .put("id", "msg_${now.toString(36)}_${UUID.randomUUID().toString().take(8)}")
            .put("role", "toolResult").put("toolCallId", callId).put("toolName", tool)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", compactAnswer(answer).toString())))
            .put("details", answer).put("isError", false).put("timestamp", now))
        return parse(record.put("updatedAt", nativeTime(now)).put("version", 1).toString())
    }

    /** Native pi-message projection only, NOT a Responses API payload. Never executes imported data. */
    fun modelMessages(): JSONArray {
        val messages = snapshot().getJSONArray("messages").objects()
        val compaction = NativeCompaction.active(messages)
        val kept = compaction?.optJSONObject("details")?.optString("firstKeptMessageId")
        val cut = messages.indexOfFirst { it.optString("id") == kept }.coerceAtLeast(0)
        val result = JSONArray()
        if (cut > 0) result.put(JSONObject().put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", compaction!!.getString("content"))))
            .put("timestamp", compaction.get("timestamp")))
        // A suffix lookup avoids rescanning the remaining transcript for every assistant call.
        val lastResult = mutableMapOf<String, Int>()
        messages.forEachIndexed { i, message ->
            if (message.optString("role") == "toolResult") lastResult[message.getString("toolCallId")] = i
        }
        for (i in cut until messages.size) {
            val message = messages[i]
            if (message.optString("role") == "custom") {
                val type = message.optString("customType")
                if (type in setOf("plan-block", "problem-dismissed") || (type == "compaction" && cut > 0)) continue
                result.put(JSONObject().put("role", "user")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message.getString("content"))))
                    .put("timestamp", message.get("timestamp")))
            } else {
                result.put(JSONObject(message.toString()).apply { remove("id") })
                if (message.failed()) continue
                message.calls().filter { (lastResult[it.getString("id")] ?: -1) <= i }.forEach { call ->
                    result.put(JSONObject().put("role", "toolResult").put("toolCallId", call.getString("id"))
                        .put("toolName", call.getString("name"))
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", INTERRUPTED_TEXT)))
                        .put("isError", false).put("timestamp", message.get("timestamp")))
                }
            }
        }
        return result
    }

    companion object {
        const val MAX_BYTES = 16_000_000
        val QUESTION_TOOLS = setOf("probe", "quiz", "recall", "explain", "ask_user_question")
        const val INTERRUPTED_TEXT = "The question was interrupted — the learner never answered; continue without holding a result for it."

        fun parse(raw: String): NativeChat {
            require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Native archive too large" }
            val record = JSONObject(raw)
            val version = record.opt("version")
            require(version == null || (version is Number && version.toDouble() == 1.0)) { "Unsupported native chat version" }
            val id = record.get("id") as? String
            require(id != null && id.isNotBlank() && id.length <= 200) { "Invalid native chat identity" }
            val messages = record.getJSONArray("messages")
            require(messages.length() <= 10_000) { "Native message limit" }
            val ids = mutableSetOf<String>()
            messages.objects().forEach {
                // System/tool declarations are request chrome, never persisted learner history.
                require(it.optString("role") in setOf("user", "assistant", "toolResult", "custom")) { "Invalid native message role" }
                val message = it.get("id") as? String
                require(message != null && message.isNotBlank() && message.length <= 512 && ids.add(message)) { "Invalid native message identity" }
            }
            return NativeChat(raw, id)
        }

        /** Learner-only model content; full answer/teacher commitments remain in native details. */
        fun compactAnswer(answer: JSONObject): JSONObject {
            val out = JSONObject().put("kind", answer.get("kind"))
            if (answer.optBoolean("dismissed")) return out.put("dismissed", true)
            if (answer.optBoolean("dontKnow")) out.put("dontKnow", true)
            answer.optJSONArray("answers")?.takeIf { it.length() > 0 }?.let { answers ->
                out.put("answers", JSONArray(answers.objects().map {
                    JSONObject().put("index", it.opt("index")).put("label", it.opt("label"))
                }))
            }
            for (key in listOf("text", "other")) answer.optString(key).takeIf { it.isNotEmpty() }?.let { out.put(key, it) }
            if (answer.has("correct") && !answer.optBoolean("dontKnow")) out.put("correct", answer.get("correct"))
            if (answer.has("confidence")) out.put("confidence", answer.get("confidence"))
            val thinking = if (answer.isNull("thinking")) answer.optString("note") else answer.optString("thinking")
            if (thinking.isNotEmpty()) out.put("thinking", thinking)
            answer.optString("readingThinking").takeIf { it.isNotEmpty() }?.let { out.put("thinkingWhileReading", it) }
            return out
        }

        private fun JSONObject.calls() = if (optString("role") == "assistant")
            optJSONArray("content")?.objects()?.filter { it.optString("type") == "toolCall" }.orEmpty() else emptyList()
        private fun JSONObject.failed() = optString("stopReason") in setOf("error", "aborted")
        private fun JSONObject.asCall() = Call(getString("id"), getString("name"), optJSONObject("arguments"))

        private fun needsContinuation(messages: List<JSONObject>): Boolean {
            val last = messages.lastOrNull() ?: return false
            if (last.optString("role") != "toolResult" || last.optJSONObject("details")?.optBoolean("dismissed") == true) return false
            var start = messages.lastIndex
            while (start > 0 && messages[start - 1].optString("role") == "toolResult") start--
            val owner = messages.getOrNull(start - 1) ?: return false
            val results = messages.drop(start)
            if (results.any { it.optString("toolName") == "problem" && !it.optBoolean("isError") && it.isNull("details") }) return false
            val answered = results.map { it.optString("toolCallId") }.toSet()
            val calls = owner.calls()
            return calls.isNotEmpty() && calls.all { it.optString("id") in answered }
        }
    }
}
