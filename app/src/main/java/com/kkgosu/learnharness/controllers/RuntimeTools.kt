package com.kkgosu.learnharness.controllers

import com.kkgosu.learnharness.api.ProviderFailure
import com.kkgosu.learnharness.api.openAiErrorCode
import com.kkgosu.learnharness.model.ProbeAssessment
import com.kkgosu.learnharness.model.RuntimeSession
import com.kkgosu.learnharness.model.StudyPlan
import com.kkgosu.learnharness.utils.objects
import com.kkgosu.learnharness.utils.strictString
import com.kkgosu.learnharness.utils.strings
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

    val questionNames = setOf("quiz", "probe", "explain", "recall", "ask_user_question")

    fun validate(
        response: JSONObject,
        session: RuntimeSession,
        tools: JSONArray = ToolSchemas.schemas(
            session.phase,
            session.researchAllowed,
            session.mechanic
        ),
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
