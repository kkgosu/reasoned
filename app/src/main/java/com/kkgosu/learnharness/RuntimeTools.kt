package com.kkgosu.learnharness

import org.json.JSONArray
import org.json.JSONObject

internal object RuntimeTools {
    const val NAMESPACE = "learnharness"

    data class Validated(
        val text: String,
        val call: JSONObject?,
        val thinking: String = "",
        val toolError: String = ""
    )

    fun questionTool(phase: String, mechanic: String) = when {
        mechanic == "recall" -> if (phase == "probe") "recall" else "explain"
        else -> if (phase == "probe") "probe" else "quiz"
    }

    val questionNames = setOf("quiz", "probe", "explain", "recall", "ask_user_question")

    fun objectSchema(properties: JSONObject) =
        JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(properties.keys().asSequence().toList()))
            .put("additionalProperties", false)

    fun function(name: String, description: String, properties: JSONObject) =
        JSONObject().put("type", "function")
            .put("name", name).put("description", description).put("strict", true)
            .put("parameters", objectSchema(properties))

    fun schemas(
        phase: String,
        researchAllowed: Boolean = false,
        mechanic: String = "choice"
    ): JSONArray {
        require(mechanic in setOf("choice", "recall"))
        val tools = JSONArray()
        fun tool(name: String, description: String, properties: JSONObject) {
            tools.put(function(name, description, properties))
        }

        fun string() = JSONObject().put("type", "string")
        fun strings() = JSONObject().put("type", "array").put("items", string())
        tool(
            "learning_context",
            "Read this session's goal, plan, assessment and practice material. To explore prior learning use list_sessions then list_propositions; prior content is not mastery.",
            JSONObject()
        )
        if (phase in setOf("probe", "practice")) {
            tool(
                "list_sessions",
                "List all knowledge-graph birth sessions, newest first: IDs, names, kind, proposition counts and last-active dates. Read this before probing prior learning. No transcript or proposition statements.",
                JSONObject()
            )
            tool(
                "list_propositions",
                "List the proposition NAMES born in a session returned by list_sessions. No statements or transcript; prior coverage is not proof of understanding.",
                JSONObject().put("session", string())
            )
        }
        if (researchAllowed) tool(
            "research",
            "Research a self-contained question in an isolated foreground context. Requires this session's web permission. Extra selected-model usage and Brave search billing; sources and report persist. No PDFs.",
            JSONObject().put("question", string())
        )
        if (phase == "practice") {
            tool(
                "problem",
                "Pose one solved-and-checked practice problem; hidden modelSolution is precommitted, revealed verbatim only at conclude. Ends the turn. One active problem. target is known proposition IDs or [] for topic-only material.",
                JSONObject().put("statement", string()).put("modelSolution", string())
                    .put(
                        "mode",
                        string().put(
                            "enum",
                            JSONArray(listOf("recall", "discrimination", "composition"))
                        )
                    )
                    .put("target", strings())
            )
            tool(
                "conclude",
                "Conclude ONLY when solved or learner explicitly asks to end. Reveal committed solution; end turn, no next problem until learner go-ahead. Outcomes/assessment are model judgments, not mastery certification. Empty rung/missClass means none.",
                JSONObject().put(
                    "outcome",
                    string().put(
                        "enum",
                        JSONArray(listOf("solved", "solvedWithHints", "movedOn", "surrendered"))
                    )
                )
                    .put("verdict", string()).put(
                        "rung",
                        string().put(
                            "enum",
                            JSONArray(listOf("", "R0", "R1", "R2", "R3", "R4", "R5"))
                        )
                    )
                    .put(
                        "missClass",
                        string().put(
                            "enum",
                            JSONArray(
                                listOf(
                                    "",
                                    "cue",
                                    "discrimination",
                                    "execution",
                                    "careless",
                                    "structural"
                                )
                            )
                        )
                    )
                    .put("retest", JSONObject().put("type", "boolean"))
            )
            return tools
        }
        tool(
            "ask_user_question",
            "One ungraded clarification, then wait. Empty options for text, or 2–32 options with stable values; allow a written Other response. multiSelect permits several selections. Empty details/description means none; empty value defaults to label. Never substitute this for a knowledge check.",
            QuestionContract.properties("ask_user_question")
        )
        tool(
            questionTool(phase, mechanic),
            if (mechanic == "choice") "One multiple-choice knowledge question, then wait. options contain label and stable value (empty value defaults to label). Commit explanation BEFORE correctAnswer: one exact option value, or an array of exact values with multiSelect=true. Grade by exact set equality, not labels or positions. 2–32 options; empty details means none. Don't add a don't-know option: the UI provides it. Never reveal the key before submission."
            else "One free-recall knowledge question, then wait. Commit the reference answer AND reasoning in correctAnswer before asking. Assess the learner's written answer against that commitment after submission; no automatic semantic grading.",
            QuestionContract.properties(questionTool(phase, mechanic)).apply {
                if (phase == "teacher") {
                    put(
                        "nodeId",
                        string().put(
                            "description",
                            "A node ID in the current dependency plan; position, not mastery."
                        )
                    )
                    put(
                        "type", string().put("enum", JSONArray(listOf("predict", "check")))
                            .put(
                                "description",
                                "predict opens BEFORE teaching and ends the turn; resolve the learner's prediction in the next teaching. check arrives collapsed AFTER completed teaching; diagnose/repair a miss."
                            )
                    )
                }
            })
        if (phase == "probe") {
            tool(
                "finish_probe",
                "Conclude only when the goal is concrete and EVERY dependency strand is bracketed. Record one assessment per strand: reliable floor, ceiling, misconceptions and what teaching should build from, grounded in learner answers. At least one learner answer is only a validation minimum, not a stopping rule. The Teacher receives the structured assessment. No mastery claim.",
                JSONObject().put(
                    "assessment", objectSchema(
                        JSONObject()
                            .put(
                                "strands",
                                JSONObject().put("type", "array").put("minItems", 1)
                                    .put("maxItems", 24)
                                    .put(
                                        "items", objectSchema(
                                            JSONObject().put("strand", string())
                                                .put(
                                                    "assessment",
                                                    string().put(
                                                        "description",
                                                        "Evidence-based floor, ceiling, misconceptions, what the lesson should build from on this dependency strand."
                                                    )
                                                )
                                        )
                                    )
                            )
                            .put(
                                "summary",
                                string().put(
                                    "description",
                                    "Overall goal and entry point; empty if unnecessary."
                                )
                            )
                    )
                )
            )
        } else {
            tool(
                "visual",
                "Make ONE precise static SVG figure from a self-contained brief. Foreground, selected model/connection, extra usage; write/render/vision/revise before delivery. One figure per learner action. Explain the concept before calling. For flows and relationships use inline mermaid instead.",
                JSONObject().put("brief", string())
            )
            tool(
                "plan_init",
                "Write or rewrite the session dependency plan, not the knowledge graph. Stable lowercase slug IDs; edges from prerequisite to dependent. The app renders its Mermaid map, so do not repeat it in prose. The FIRST plan (including revisions before approval) ends the turn and requires learner approval. Mid-lesson rewrites after initial approval continue teaching without another approval barrier.",
                JSONObject().put(
                    "nodes",
                    JSONObject().put("type", "array").put(
                        "items",
                        objectSchema(
                            JSONObject().put("id", string()).put("title", string())
                                .put("summary", string())
                        )
                    )
                )
                    .put(
                        "edges",
                        JSONObject().put("type", "array").put(
                            "items",
                            objectSchema(JSONObject().put("from", string()).put("to", string()))
                        )
                    )
            )
            tool(
                "finish_lesson",
                "After a learner check, summarize what was covered and what remains uncertain. Does not certify mastery.",
                JSONObject().put("summary", string())
            )
        }
        return tools
    }

    fun validate(
        response: JSONObject,
        session: RuntimeSession,
        tools: JSONArray = schemas(session.phase, session.researchAllowed, session.mechanic),
        allowRepair: Boolean = false
    ): Validated {
        if (!response.isNull("error")) throw ProviderFailure(
            openAiErrorCode(response.toString()) ?: "incomplete"
        )
        if (response.optString("status") != "completed") throw ProviderFailure("incomplete")
        val output =
            response.optJSONArray("output") ?: throw ProviderFailure("invalid_output_shape")
        android.util.Log.d(
            "ReasonedRuntime",
            "validating completed output: items=${output.length()}; plan=${session.connection != "api"}"
        )
        if (output.length() !in 1..16) throw ProviderFailure("invalid_output_count")
        if (output.toString()
                .toByteArray(Charsets.UTF_8).size > 262_144
        ) throw ProviderFailure("output_limit")
        val text = StringBuilder()
        val thinking = StringBuilder()
        var call: JSONObject? = null
        var toolError = ""
        output.objects().forEach { item ->
            when (item.getString("type")) {
                "message" -> {
                    if (item.optString("role") != "assistant" || item.optString("status") !in listOf(
                            "",
                            "completed"
                        )
                    ) throw ProviderFailure("invalid_output_message")
                    item.getJSONArray("content").objects().forEach {
                        when (it.getString("type")) {
                            "output_text" -> text.append(
                                it.strictString(
                                    "text",
                                    24_000,
                                    allowEmpty = true
                                )
                            )

                            "refusal" -> throw ProviderFailure("refusal")
                            else -> throw ProviderFailure("invalid_output")
                        }
                    }
                }

                "reasoning" -> {
                    // Only provider-supplied public summaries are visible; encrypted reasoning stays opaque.
                    item.optJSONArray("summary")?.objects()
                        ?.filter { it.optString("type") == "summary_text" }?.forEach {
                        if (thinking.isNotEmpty()) thinking.append("\n\n")
                        thinking.append(it.strictString("text", 24_000, allowEmpty = true))
                    }
                    if (thinking.length > 24_000) throw ProviderFailure("output_limit")
                }

                "function_call" -> {
                    if (call != null) throw ProviderFailure("multiple_tool_calls")
                    if (item.optString("status") !in listOf("", "completed")) throw ProviderFailure(
                        "invalid_output_tool_status"
                    )
                    call = item
                    val id = item.strictString("call_id", 200)
                    // A replay must not create a second question/answer or apply an already-acknowledged tool.
                    if (JSONArray(session.protocol).objects()
                            .any { it.optString("call_id") == id }
                    ) throw ProviderFailure("tool_replay")
                    val name = item.strictString("name", 40)
                    val namespace =
                        if (item.isNull("namespace")) "" else item.optString("namespace")
                    android.util.Log.d(
                        "ReasonedRuntime",
                        "validating tool: declared=${
                            tools.objects().any { it.getString("name") == name }
                        }; namespace=${if (namespace.isEmpty()) "none" else if (namespace == NAMESPACE) "expected" else "foreign"}"
                    )
                    if (namespace.isNotEmpty() && (session.connection == "api" || namespace != NAMESPACE)) throw ProviderFailure(
                        "tool_denied"
                    )
                    if (session.planPending && (name in questionNames - "ask_user_question" || name in setOf(
                            "visual",
                            "finish_lesson"
                        ))
                    )
                        throw ProviderFailure("plan_approval_required")
                    val schema = tools.objects().firstOrNull { it.getString("name") == name }
                        ?: throw ProviderFailure("tool_denied")
                    // Envelope, permissions and replay checks above are NEVER recoverable tool errors.
                    item.strictString("arguments", 24_000)
                    try {
                        val args = JSONObject(item.getString("arguments"))
                    val expected =
                        schema.getJSONObject("parameters").getJSONObject("properties").keys()
                            .asSequence().toSet()
                    android.util.Log.d(
                        "ReasonedRuntime",
                        "validating arguments: keys=${args.length()}; expected=${expected.size}"
                    )
                    val keys = args.keys().asSequence().toSet()
                        if (keys != expected) throw ProviderFailure("invalid_tool_arguments")
                    when (name) {
                        "list_propositions" -> args.strictString("session", 200)
                        "research" -> {
                            if (!session.researchAllowed || session.research.size >= 4) throw ProviderFailure(
                                "research_not_allowed"
                            )
                            args.strictString("question", 4000)
                        }

                        "problem" -> {
                            if (!session.practiceReady || session.activeProblem != null || session.problems.size >= 24) throw ProviderFailure(
                                "practice_problem_active_or_limit"
                            )
                            args.strictString("statement", 4000); args.strictString(
                                "modelSolution",
                                6000
                            )
                            if (args.strictString("mode", 30) !in setOf(
                                    "recall",
                                    "discrimination",
                                    "composition"
                                )
                            ) throw ProviderFailure("practice_mode")
                            val targets = args.getJSONArray("target").strings(8, 100)
                            val known = JSONArray(session.practiceMaterial).objects()
                                .map { it.getString("id") }.toSet()
                            if (targets.toSet().size != targets.size || targets.any { it !in known }) throw ProviderFailure(
                                "practice_target"
                            )
                        }

                        "conclude" -> {
                            val active = session.activeProblem
                                ?: throw ProviderFailure("practice_no_problem")
                            if (active.attempts.isEmpty()) throw ProviderFailure("practice_no_attempt")
                            if (args.strictString("outcome", 30) !in setOf(
                                    "solved",
                                    "solvedWithHints",
                                    "movedOn",
                                    "surrendered"
                                )
                            ) throw ProviderFailure("practice_outcome")
                            args.strictString("verdict", 3000)
                            if (args.strictString("rung", 2, allowEmpty = true) !in setOf(
                                    "",
                                    "R0",
                                    "R1",
                                    "R2",
                                    "R3",
                                    "R4",
                                    "R5"
                                ) ||
                                args.strictString("missClass", 30, allowEmpty = true) !in setOf(
                                    "",
                                    "cue",
                                    "discrimination",
                                    "execution",
                                    "careless",
                                    "structural"
                                ) || args.opt("retest") !is Boolean
                            )
                                throw ProviderFailure("practice_verdict")
                        }
                        "quiz", "probe", "explain", "recall", "ask_user_question" -> {
                            android.util.Log.d(
                                "ReasonedRuntime",
                                "question shape: textChars=${(args.opt("question") as? String)?.length ?: -1}; choices=${
                                    args.optJSONArray("choices")?.length() ?: -1
                                }"
                            )
                            val graded = name != "ask_user_question"
                            if (session.phase == "teacher" && graded) {
                                if (args.strictString("type", 10) !in setOf(
                                        "predict",
                                        "check"
                                    )
                                ) throw ProviderFailure("invalid_question_type")
                                val node = args.strictString("nodeId", 80)
                                if (session.plan?.nodes?.none { it.id == node } != false) throw ProviderFailure(
                                    "invalid_plan_reference"
                                )
                            }
                            QuestionContract.parse(id, args, session.phase, name)
                        }

                        "visual" -> {
                            args.strictString("brief", 4000)
                            if (session.phase != "teacher" || session.plan == null) throw ProviderFailure(
                                "invalid_plan_reference"
                            )
                        }
                        "plan_init" -> StudyPlan.parse(args)
                        "finish_probe" -> {
                            if (session.answers < 1 || session.question != null) throw ProviderFailure(
                                "invalid_probe_handoff"
                            )
                            ProbeAssessment.parse(args.getJSONObject("assessment"))
                        }

                        "finish_lesson" -> {
                            if (session.answers < 1) throw ProviderFailure("invalid_lesson_finish")
                            args.strictString("summary", 2000)
                        }
                    }
                    } catch (error: Exception) {
                        val code = when (error) {
                            is ProviderFailure -> error.reason
                            is org.json.JSONException -> "invalid_tool_arguments"
                            else -> throw error
                        }
                        val local = name == "plan_init" || name in questionNames
                        val repairable = code in setOf(
                            "invalid_output",
                            "invalid_tool_arguments",
                            "invalid_plan_nodes",
                            "invalid_plan_edges",
                            "invalid_graph_reference",
                            "graph_cycle",
                            "invalid_plan_reference",
                            "invalid_question_type",
                            "invalid_question_choices",
                            "invalid_answer_key"
                        )
                        if (!allowRepair || !local || !repairable) throw ProviderFailure(code)
                        toolError = code
                    }
                }

                else -> throw ProviderFailure("tool_denied")
            }
        }
        if (text.length > 24_000) throw ProviderFailure("output_limit")
        if (text.isBlank() && call == null) throw ProviderFailure("empty_output")
        val usage = response.optJSONObject("usage")
        if (usage != null && (usage.optLong("input_tokens") !in 0..1_000_000 || usage.optLong("output_tokens") !in 0..1_000_000)) throw ProviderFailure(
            "invalid_output"
        )
        return Validated(text.toString(), call, thinking.toString(), toolError)
    }
}
