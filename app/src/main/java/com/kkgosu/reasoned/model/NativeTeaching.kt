package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.controllers.QuestionContract
import com.kkgosu.reasoned.controllers.ToolSchemas
import com.kkgosu.reasoned.model.LessonDisplayItem.Role
import com.kkgosu.reasoned.model.LessonDisplayItem.TextDisplayItem
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Native lesson/practice projections and local tool events, not a migration or another archive. */
internal object NativeTeaching {
    const val VERSION = "learnharness-native-3dd52110-v3"
    // Replay-only historical graph reads. This does not expose or execute these tools on Android.
    private val HISTORICAL_GRAPH_READS = setOf("list_sessions", "list_propositions", "find")

    fun project(session: RuntimeSession): RuntimeSession = try {
        val chat = requireNotNull(session.nativeRecord)
        val root = chat.snapshot()
        val state = chat.state()
        // Do not make unsupported native sessions executable through the legacy runner.
        val practice = root.optString("kind") == "practice"
        if (!practice && root.optString("kind", "teach") != "teach") unavailable()
        val plan = if (practice) null else root.optJSONObject("tree")?.let(NativePlan::display)
        if (!practice && state.phase == "teach" && plan == null) unavailable()
        val messages = root.getJSONArray("messages").objects()
        val clean = messages.filter { it.optString("role") == "toolResult" && !it.optBoolean("isError") }
            .map { it.getString("toolCallId") }.toSet()
        val problems = mutableListOf<PracticeProblem>()
        val failedOutputs = messages.filter { it.optString("role") == "toolResult" && it.optString("toolName") == "send_to_user" && it.optBoolean("isError") }
            .map { it.getString("toolCallId") }.toSet()
        val dismissedCalls = messages.filter { it.optString("role") == "toolResult" &&
            it.optJSONObject("details")?.optBoolean("dismissed") == true }.map { it.getString("toolCallId") }.toSet()
        val dismissedOwners = messages.filter { it.optString("role") == "assistant" && it.getJSONArray("content").objects().any { block ->
            block.optString("type") == "toolCall" && block.getString("id") in dismissedCalls
        } }.map { it.getString("id") }.toSet()
        val answeredCalls = messages.filter { it.optString("role") == "toolResult" }.map { it.getString("toolCallId") }.toSet()
        val pendingOwner = messages.lastOrNull { it.optString("role") == "assistant" }?.takeIf { owner ->
            owner.getJSONArray("content").objects().any { block -> block.optString("type") == "toolCall" &&
                block.optString("name") in setOf("quiz", "explain", "probe", "recall") && block.getString("id") !in answeredCalls }
        }?.getString("id")
        val items = mutableListOf(session.items.firstOrNull { it.role == Role.TITLE }
            ?: TextDisplayItem(0, session.goal, Role.TITLE))
        val cards = mutableMapOf<Long, LessonDisplayItem.AnsweredDisplayItem>()
        val prose = mutableSetOf<Long>()
        val calls = mutableMapOf<String, Pair<NativeChat.Call, String>>()
        val results = messages.withIndex().filter { it.value.optString("role") == "toolResult" }
            .groupBy { it.value.optString("toolCallId") }
        var phase = "probe"
        val probeCalls = mutableSetOf<String>()
        var assessment: ProbeAssessment? = null
        fun row(message: JSONObject, index: Int, text: String, role: Role): Long {
            val id = session.nativeDisplayIds.getValue(NativeChat.Part(message.getString("id"), index))
            items += TextDisplayItem(id, text, role)
            if (role == Role.PROSE) prose += id
            return id
        }
        messages.forEachIndexed record@ { messageIndex, message ->
            when (message.getString("role")) {
                "custom" -> when (message.optString("customType")) {
                    "compaction", "plan-block" -> Unit
                    "probe-handoff" -> if (practice) unavailable()
                    "graph-context" -> if (!practice) unavailable() // Source context stays model-visible, not learner evidence.
                    "practice-thinking" -> {
                        if (!practice) unavailable()
                        val details = message.getJSONObject("details")
                        val active = problems.lastOrNull()?.takeIf { it.outcome.isEmpty() } ?: unavailable()
                        if (active.id != details.getString("problemCallId") || active.attempts.size >= 40) unavailable()
                        val text = details.getString("text")
                        if (text.isBlank() || text.length > 2000) unavailable()
                        problems[problems.lastIndex] = active.copy(attempts = active.attempts + text)
                        row(message, -1, text, Role.LEARNER)
                    }
                    "practice-next" -> { if (!practice) unavailable(); row(message, -1, "Next problem", Role.LEARNER) }
                    "problem-dismissed" -> {
                        if (!practice) unavailable()
                        val active = problems.lastOrNull()?.takeIf { it.outcome.isEmpty() } ?: unavailable()
                        if (active.id != message.getJSONObject("details").getString("problemCallId")) unavailable()
                        problems[problems.lastIndex] = active.copy(outcome = "dismissed", retest = true)
                        row(message, -1, "Problem dismissed without revealing the solution.", Role.LEARNER)
                    }
                    else -> unavailable()
                }
                "user" -> when (val content = message.get("content")) {
                    is String -> row(message, -1, content, Role.LEARNER)
                    is JSONArray -> content.objects().forEachIndexed { i, block ->
                        if (block.optString("type") != "text") unavailable()
                        row(message, i, block.getString("text"), Role.LEARNER)
                    }
                    else -> unavailable()
                }
                "assistant" -> message.getJSONArray("content").objects().forEachIndexed part@ { i, block ->
                    val failed = message.optString("stopReason") in setOf("error", "aborted")
                    when (block.getString("type")) {
                        "text" -> row(message, i, if (failed) "Interrupted response (not completed):\n\n" + block.getString("text") else block.getString("text"),
                            if (failed) Role.THINKING else Role.PROSE)
                        "thinking" -> if (!failed && !practice && message.getString("id") != pendingOwner && message.getString("id") !in dismissedOwners && block.optString("thinking").isNotBlank())
                            row(message, i, block.getString("thinking"), Role.THINKING)
                        "toolCall" -> {
                            val tool = block.getString("name")
                            if (tool in HISTORICAL_GRAPH_READS) {
                                if (!failed) {
                                    val result = results[block.getString("id")]?.singleOrNull() ?: unavailable()
                                    if (result.index <= messageIndex || result.value.optString("toolName") != tool) unavailable()
                                }
                                calls[block.getString("id")] = NativeChat.Call(block.getString("id"), tool, block.optJSONObject("arguments")) to phase
                                return@part
                            }
                            if (tool !in (if (practice) setOf("problem", "conclude") else setOf("probe", "probe_done", "recall", "quiz", "explain", "ask_user_question", "plan_init", "send_to_user"))) unavailable()
                            if (practice && !failed && block.getString("id") in clean) {
                                val args = block.getJSONObject("arguments")
                                if (tool == "problem") {
                                    if (problems.size >= 24 || problems.lastOrNull()?.outcome == "") unavailable()
                                    val posed = NativePractice.problem(block.getString("id"), args)
                                    problems += posed
                                    row(message, i, posed.statement, Role.QUESTION)
                                } else {
                                    val previous = problems.lastOrNull() ?: unavailable()
                                    if (args.optString("outcome") == "dismissed") {
                                        if (previous.outcome != "dismissed") unavailable()
                                    } else {
                                        if (previous.outcome.isNotEmpty()) unavailable()
                                        val concluded = NativePractice.conclude(previous, args)
                                        problems[problems.lastIndex] = concluded
                                        row(message, i, "Model judgment, not mastery evidence.\n\n${concluded.verdict}\n\nCommitted solution\n\n${concluded.solution}", Role.PRACTICE)
                                    }
                                }
                            }
                            if (tool == "send_to_user" && !failed && block.getString("id") !in failedOutputs) {
                                val text = block.optJSONObject("arguments")?.opt("message") as? String
                                if (!text.isNullOrBlank()) row(message, i, text, Role.PROSE)
                            }
                            if (tool == "probe_done" && !failed) probeCalls += block.getString("id")
                            if (phase == "plan" && tool in setOf("quiz", "explain") && block.optJSONObject("arguments")?.optString("nodeId").orEmpty().isNotEmpty()) phase = "teach"
                            calls[block.getString("id")] = NativeChat.Call(block.getString("id"), tool, block.optJSONObject("arguments")) to phase
                        }
                        else -> unavailable()
                    }
                }
                "toolResult" -> {
                    if (message.getJSONArray("content").objects().any { it.optString("type") != "text" }) unavailable()
                    val historicalRead = calls[message.optString("toolCallId")]?.first?.tool
                    if (historicalRead in HISTORICAL_GRAPH_READS) {
                        if (message.optString("toolName") != historicalRead) unavailable()
                        return@record // Read results are model history, not teacher prose or learner evidence.
                    }
                    if (message.optString("toolName") in HISTORICAL_GRAPH_READS) unavailable()
                    val details = message.optJSONObject("details")
                    if (details?.has("result") == true) unavailable() // Native artifact/report projection is still gated.
                    if (message.optString("toolName") == "probe_done" && message.optString("toolCallId") in probeCalls &&
                        !message.optBoolean("isError") && details?.opt("ok") != false) {
                        phase = "plan"
                        assessment = ProbeAssessment.parse(calls.getValue(message.getString("toolCallId")).first.input!!.getJSONObject("assessment"))
                        row(message, -1, assessment.transcript(), Role.ASSESSMENT)
                    }
                    if (!message.optBoolean("isError") && details?.optString("kind") in setOf("quiz", "explain", "ask")) {
                        if (details!!.optBoolean("dismissed")) return@record
                        val (call, at) = calls[message.getString("toolCallId")] ?: unavailable()
                        val q = NativeQuestion.parse(call, at).question
                        details.optJSONArray("options")?.let { options ->
                            if (options.objects().map { it.getString("label") } != q.choices) unavailable()
                        }
                        val selected = details.optJSONArray("answers")?.objects().orEmpty()
                        val values = selected.map { answer ->
                            val index = answer.getInt("index") - 1
                            val value = q.values.getOrNull(index) ?: unavailable()
                            if (answer.getString("label") != q.choices[index] || (answer.has("value") && answer.getString("value") != value)) unavailable()
                            value
                        }
                        val correct = if (details.optString("kind") == "quiz") details.getJSONArray("correctIndices").let { indices ->
                            (0 until indices.length()).map { q.values.getOrNull(indices.getInt(it) - 1) ?: unavailable() }
                        } else emptyList()
                        val committed = q.copy(text = details.optString("questionText", q.text),
                            correctAnswer = if (details.optString("kind") == "quiz") correct.joinToString("\n") else details.optString("committed", q.correctAnswer),
                            correctValues = correct, explanation = details.optString("explanation", q.explanation),
                            type = details.optString("questionType", q.type))
                        val text = listOf(selected.joinToString(", ") { it.getString("label") },
                            details.optString("text"), details.optString("other")).filter { it.isNotEmpty() }.joinToString("\n")
                        val answer = JSONObject().put("call", call.id).put("answer", text)
                            .put("selectedValues", JSONArray(values)).put("reasoning", details.optString("thinking", details.optString("note")))
                            .put("dontKnow", details.optBoolean("dontKnow")).put("thinkingWhileReading", details.optString("readingThinking"))
                        if (details.has("confidence")) answer.put("confidence_self_report", details.getInt("confidence"))
                        val id = row(message, -1, text, Role.LEARNER)
                        cards[id] = committed.answered(id, answer)
                    }
                    if (message.optString("toolName") == "plan_init" && !message.optBoolean("isError") && details?.opt("ok") != false) {
                        val shown = details?.optJSONObject("tree") ?: unavailable()
                        row(message, -1, NativePlan.display(shown).diagramMarkdown(), Role.PLAN)
                    }
                }
            }
        }
        val owner = messages.lastOrNull { it.optString("role") == "assistant" }
        val question = if (practice || owner?.optString("stopReason") in setOf("error", "aborted")) null else chat.pendingQuestion()
        val same = question?.question?.callId == session.question?.callId
        val last = messages.lastOrNull()
        val failure = last?.takeIf { it.optString("role") == "assistant" }?.optString("stopReason")
        if (practice && problems.lastOrNull()?.takeIf { it.outcome.isEmpty() }?.id != state.activeProblem?.id) unavailable()
        session.copy(phase = if (practice) "practice" else if (state.phase == "probe") "probe" else "teacher", protocol = "[]", items = items, answerCards = cards,
            problems = problems, practiceReady = if (practice) problems.isEmpty() else session.practiceReady,
            proseIds = prose, plan = plan, planPending = false, planApproved = state.phase == "teach", assessment = assessment,
            question = question?.question?.let { if (question.needsReveal && it.type.isEmpty()) it.copy(type = "check") else it },
            status = when {
                session.status == "running" -> "running"
                failure == "error" -> "error"
                failure == "aborted" -> "interrupted"
                question != null -> "question"
                else -> "idle"
            },
            error = if (failure == "error") session.error.ifEmpty { "network" } else "",
            expanded = if (same) session.expanded else question?.needsReveal != true,
            confidence = if (same) session.confidence else NativeQuestion.DEFAULT_CONFIDENCE,
            selection = if (same) session.selection else -1,
            selectedValues = if (same) session.selectedValues else emptyList(),
            answers = cards.size, route = "", routeCall = "", compaction = null, contextEnd = 0)
    } catch (_: JSONException) { unavailable() }
      catch (_: IllegalArgumentException) { unavailable() }
      catch (_: ProviderFailure) { unavailable() }

    /** Native optional fields stay optional; never turn absent ask options into an empty array. */
    fun tools(mechanic: String = "choice", phase: String = "teacher", allowQuestions: Boolean = true): JSONArray = JSONArray().apply {
        val names = listOfNotNull(ToolSchemas.questionTool(phase, mechanic).takeIf { allowQuestions }, "ask_user_question")
        for (name in names) {
            val props = QuestionContract.properties(name)
            val required = mutableListOf("question")
            if (name != "ask_user_question") {
                required += "correctAnswer"
                if (phase != "probe") {
                    required += listOf("type", "nodeId")
                    props.put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("predict", "check"))))
                        .put("nodeId", JSONObject().put("type", "string"))
                }
            }
            if (props.has("options")) {
                val options = props.getJSONObject("options").put("minItems", 2)
                options.getJSONObject("items").put("required", JSONArray().put("label"))
                if (name in setOf("quiz", "probe")) required += listOf("options", "explanation")
            }
            put(ToolSchemas.function(name, when (name) {
                "quiz" -> "Ask one precommitted multiple-choice predict/check question on an existing plan node; options shuffle locally."
                "probe" -> "Probe one dependency with a precommitted multiple-choice question; no nodeId or teaching role."
                "explain" -> "Ask one free-recall predict/check question on an existing plan node; precommit correctAnswer."
                "recall" -> "Probe one dependency with a free-recall question; precommit the reference answer."
                else -> "Ask a learner preference. Omit options for free text; otherwise supply at least two. Not evidence of learning."
            }, props).put("strict", false).apply { getJSONObject("parameters").put("required", JSONArray(required)) })
        }
        if (phase == "probe") {
            val props = ToolSchemas.schemas("probe").objects().first { it.getString("name") == "finish_probe" }
                .getJSONObject("parameters").getJSONObject("properties")
            props.getJSONObject("assessment").put("required", JSONArray().put("strands"))
            put(ToolSchemas.function("probe_done", "Conclude probing with evidence-based strand assessments; no open question. The teacher then plans.", props)
                .put("strict", false))
        } else put(ToolSchemas.function("plan_init", "Present or rewrite the dependency map as mermaid; slugs are permanent node ids. During planning, wait for the learner's natural reply; mid-teaching rewrites do not need another approval.",
            JSONObject().put("mermaid", JSONObject().put("type", "string").put("maxLength", 16_000))).put("strict", false))
    }

    /** Planning ends on the first node question, never a fabricated approval/verdict marker. */
    fun awaitingPlanReply(chat: NativeChat): Boolean {
        if (chat.snapshot().optString("kind") == "practice" || chat.state().phase != "plan") return false
        val messages = chat.snapshot().getJSONArray("messages").objects()
        val presented = messages.indexOfLast { it.optString("role") == "toolResult" && it.optString("toolName") == "plan_init" &&
            !it.optBoolean("isError") && it.optJSONObject("details")?.opt("ok") != false }
        return presented >= 0 && messages.drop(presented + 1).none {
            it.optString("role") == "user" || (it.optString("role") == "toolResult" && !it.optBoolean("isError") &&
                it.optJSONObject("details")?.optString("kind") in setOf("ask", "quiz", "explain") &&
                it.optJSONObject("details")?.optBoolean("dismissed") != true)
        }
    }

    fun handoffPending(chat: NativeChat): Boolean = chat.state().phase == "plan" &&
        chat.snapshot().getJSONArray("messages").objects().lastOrNull()?.optString("customType") == "probe-handoff"

    /** Only the runtime's newly validated call is eligible; this never scans/runs imported work. */
    fun completeTool(chat: NativeChat, callId: String, now: Long): NativeChat {
        val root = chat.snapshot()
        val messages = root.getJSONArray("messages").objects()
        val owner = messages.last()
        require(owner.getString("role") == "assistant" && owner.optString("stopReason") !in setOf("error", "aborted"))
        val call = owner.getJSONArray("content").objects().single { it.optString("type") == "toolCall" }
        require(call.getString("id") == callId && call.getString("name") in setOf("probe_done", "plan_init"))
        val tool = call.getString("name")
        val before = NativeChat.parse(JSONObject(root.toString()).put("messages", JSONArray(messages.dropLast(1))).toString())
        var text: String
        val details = try {
            if (tool == "probe_done") {
                require(before.state().phase == "probe" && before.pendingQuestion() == null) { "Resolve the pending question before concluding the probe; probe_done fires once" }
                ProbeAssessment.parse(call.getJSONObject("arguments").getJSONObject("assessment"))
                text = PROBE_ACK
                JSONObject().put("ok", true)
            } else {
                require(before.state().phase != "probe") { "Conclude the probe before planning" }
                val tree = NativePlan.parse(call.getJSONObject("arguments").getString("mermaid"))
                root.put("tree", tree)
                text = JSONObject().put("ok", true).put("nodeIds", JSONArray(tree.getJSONArray("nodes").objects().map { it.getString("id") }))
                    .put("reminder", NODE_REMINDER).toString()
                JSONObject().put("ok", true).put("tree", tree)
            }
        } catch (error: IllegalArgumentException) {
            JSONObject().put("ok", false).put("error", error.message ?: "Invalid native tool input").also { text = it.toString() }
        } catch (error: ProviderFailure) {
            JSONObject().put("ok", false).put("error", error.reason).also { text = it.toString() }
        }
        root.getJSONArray("messages").put(JSONObject().put("id", "msg_${UUID.randomUUID()}").put("role", "toolResult")
            .put("toolCallId", callId).put("toolName", tool).put("timestamp", now).put("isError", false)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("details", details))
        if (tool == "probe_done" && details.optBoolean("ok") && messages.none { it.optString("id") == "steer_probe_${chat.id}" }) {
            root.getJSONArray("messages").put(JSONObject().put("id", "steer_probe_${chat.id}").put("role", "custom")
                .put("customType", "probe-handoff").put("content", HANDOFF).put("display", true)
                .put("details", JSONObject().put("chatId", chat.id)).put("timestamp", now))
        }
        return NativeChat.parse(root.put("updatedAt", nativeTime(now)).put("version", 1).toString())
    }

    // Pinned core/contracts/probe.ts, chat.ts and agent/tools/plan.ts; details never enter model content.
    const val PROBE_ACK = "Assessment recorded — it is shown to the learner now and the teacher plans from it. Say your closing sentence and end your turn; the teaching phase takes over from here."
    const val NODE_REMINDER = "These are the plan's node ids — the only valid nodeId values for every quiz and explain call from here on. Reference them exactly."
    const val HANDOFF = "Next: plan the full teaching arc from my current understanding to the goal understanding. Apply the teaching methodology in your system prompt, and find the optimal way to teach me this. Find the unconditional truths, plan the motivated derivations/discoveries, so every reasoning step has to land as obviously true. Take your time here and really think this through. When it's genuinely reasoned out, call plan_init and present the plan; I'll accept it or ask for changes."

    private fun unavailable(): Nothing = throw ProviderFailure("native_runtime_unavailable")
}
