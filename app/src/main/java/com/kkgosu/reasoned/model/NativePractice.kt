package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.controllers.ToolSchemas
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strictString
import com.kkgosu.reasoned.utils.strings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Pinned LearnHarness practice tools/input-events. No requests, persistence or legacy events. */
internal object NativePractice {
    const val POSE_ACK = "Problem posed — the turn ends here; the learner's thinking traces and answer commits arrive as labeled messages."
    const val CONCLUDE_ACK = "Problem concluded. Say your closing words about the learner's work if useful, then END YOUR TURN — no further tool calls. The next problem is NEVER posed here: it waits for the learner's explicit go-ahead (a *Next problem* message, or their own words asking for the next one). Between problems, answer their questions about the concluded one."
    const val NEXT = "[NEXT PROBLEM] — the learner's explicit go-ahead: pose the next problem now per the selection rules, or wrap the session in text if the arc is complete."

    fun problem(id: String, args: JSONObject): PracticeProblem {
        val targets = args.optJSONArray("target")?.strings(8, 100).orEmpty()
        require(!args.has("target") || args.opt("target") is JSONArray)
        require(targets.distinct().size == targets.size)
        val mode = args.strictString("mode", 30)
        require(mode in setOf("recall", "discrimination", "composition"))
        return PracticeProblem(id, args.strictString("statement", 4000), args.strictString("modelSolution", 6000), mode, targets)
    }

    fun conclude(problem: PracticeProblem, args: JSONObject): PracticeProblem {
        val outcome = args.strictString("outcome", 30)
        require(outcome in setOf("solved", "solvedWithHints", "movedOn", "surrendered"))
        fun nullable(key: String, allowed: Set<String>): String {
            require(args.has(key))
            if (args.isNull(key)) return ""
            return args.strictString(key, 30).also { require(it in allowed) }
        }
        val rung = nullable("rung", (0..5).map { "R$it" }.toSet())
        val miss = nullable("missClass", setOf("cue", "discrimination", "execution", "careless", "structural"))
        require(args.opt("retest") is Boolean)
        return problem.copy(outcome = outcome, verdict = args.strictString("verdict", 3000), rung = rung,
            missClass = miss, retest = args.getBoolean("retest"))
    }

    fun tools(): JSONArray {
        val tools = JSONArray(ToolSchemas.schemas("practice").objects().filter { it.getString("name") in setOf("problem", "conclude") })
        tools.objects().forEach { tool ->
            tool.put("strict", false)
            val schema = tool.getJSONObject("parameters")
            val props = schema.getJSONObject("properties")
            if (tool.getString("name") == "problem") {
                schema.put("required", JSONArray(listOf("statement", "mode", "modelSolution")))
                props.getJSONObject("target").put("maxItems", 8)
            } else {
                for (key in listOf("rung", "missClass")) {
                    val value = props.getJSONObject(key)
                    value.put("type", JSONArray(listOf("string", "null")))
                    value.put("enum", JSONArray(value.getJSONArray("enum").let { raw ->
                        (0 until raw.length()).map { raw.get(it) }.filter { it != "" }
                    }).put(JSONObject.NULL))
                }
                tool.put("description", "Conclude the active problem and reveal its originally committed solution. Use null for no rung/missClass. Then closing prose only; wait for explicit learner go-ahead before another problem.")
            }
        }
        return tools
    }

    /** Complete only a newly validated runtime call; clean acknowledgments own activation. */
    fun completeTool(chat: NativeChat, callId: String, now: Long): NativeChat {
        val root = chat.snapshot()
        val owner = root.getJSONArray("messages").objects().last()
        require(owner.getString("role") == "assistant" && owner.optString("stopReason") == "toolUse")
        val call = owner.getJSONArray("content").objects().single { it.optString("type") == "toolCall" }
        require(call.getString("id") == callId && call.getString("name") in setOf("problem", "conclude"))
        // Native prepareArguments canonicalizes [] to absent; raw provider JSON remains untouched.
        if (call.getString("name") == "problem") call.getJSONObject("arguments").let { args ->
            if (args.optJSONArray("target")?.length() == 0) args.remove("target")
        }
        root.getJSONArray("messages").put(result(callId, call.getString("name"), now))
        return finish(root, now)
    }

    /** Explicit learner inputs commit write-ahead. Dismissal alone is silent and UI-only. */
    fun input(chat: NativeChat, type: String, text: String = "", callId: String? = null, now: Long = System.currentTimeMillis()): NativeChat {
        val root = chat.snapshot()
        require(root.optString("kind") == "practice")
        val state = chat.state()
        when (type) {
            "dismiss-problem", "thinking" -> require(state.activeProblem?.id == callId && callId != null)
            "next" -> require(state.activeProblem == null)
            "text", "continue" -> Unit
            else -> error("Unsupported practice input")
        }
        if (type in setOf("thinking", "text")) require(text.isNotBlank() && text.length <= 2000)
        val messages = root.getJSONArray("messages")
        if (type == "thinking") require(messages.objects().count {
            it.optString("customType") == "practice-thinking" && it.optJSONObject("details")?.optString("problemCallId") == callId
        } < 40)
        if (type != "dismiss-problem") state.pendingDismissal?.let { dismissed ->
            val id = "call_conc_$dismissed"
            val zero = JSONObject().put("input", 0).put("output", 0).put("cacheRead", 0).put("cacheWrite", 0).put("totalTokens", 0)
                .put("cost", JSONObject().put("input", 0).put("output", 0).put("cacheRead", 0).put("cacheWrite", 0).put("total", 0))
            messages.put(message("assistant", now).put("api", "faux").put("provider", "synthetic").put("model", "synthetic-conclude")
                .put("usage", zero).put("stopReason", "toolUse").put("content", JSONArray().put(JSONObject().put("type", "toolCall")
                    .put("id", id).put("name", "conclude").put("arguments", JSONObject().put("outcome", "dismissed")))))
            messages.put(result(id, "conclude", now).put("details", JSONObject().put("outcome", "dismissed").put("problemCallId", dismissed)))
        }
        val details = JSONObject()
        val marker = when (type) {
            "dismiss-problem" -> { details.put("problemCallId", callId); "problem-dismissed" to "" }
            "thinking" -> { details.put("problemCallId", callId).put("text", text); "practice-thinking" to "Your thinking:\n$text" }
            "next" -> "practice-next" to NEXT
            else -> null
        }
        if (marker != null) messages.put(message("custom", now).put("customType", marker.first).put("content", marker.second)
            .put("display", true).put("details", details))
        else if (type == "text") messages.put(message("user", now).put("content", text))
        return finish(root, now)
    }

    private fun message(role: String, now: Long) = JSONObject().put("id", "msg_${UUID.randomUUID()}").put("role", role).put("timestamp", now)
    private fun result(id: String, tool: String, now: Long) = message("toolResult", now).put("toolCallId", id).put("toolName", tool)
        .put("isError", false).put("details", JSONObject.NULL)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", if (tool == "problem") POSE_ACK else CONCLUDE_ACK)))
    private fun finish(root: JSONObject, now: Long) = NativeChat.parse(root.put("version", 1).put("updatedAt", nativeTime(now)).toString())
}
