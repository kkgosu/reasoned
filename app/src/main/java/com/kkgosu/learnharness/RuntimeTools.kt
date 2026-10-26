package com.kkgosu.learnharness

import org.json.JSONArray
import org.json.JSONObject

internal object RuntimeTools {
    const val NAMESPACE = "learnharness"

    data class Validated(val text: String, val call: JSONObject?, val thinking: String = "")

    fun objectSchema(properties: JSONObject) =
        JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(properties.keys().asSequence().toList()))
            .put("additionalProperties", false)

    fun function(name: String, description: String, properties: JSONObject) =
        JSONObject().put("type", "function")
            .put("name", name).put("description", description).put("strict", true)
            .put("parameters", objectSchema(properties))

    fun schemas(phase: String): JSONArray {
        val tools = JSONArray()
        fun tool(name: String, description: String, properties: JSONObject) {
            tools.put(function(name, description, properties))
        }

        fun string() = JSONObject().put("type", "string")
        fun strings() = JSONObject().put("type", "array").put("items", string())
        tool(
            "learning_context",
            "Read the goal, session plan and bounded persistent propositions with source IDs. These are not mastery assessments.",
            JSONObject()
        )
        tool(
            "ask_question",
            "Pause for a learner answer. Ask one question. choices must be empty for free response, or 2–4 distinct short options. Never put the answer in the question.",
            JSONObject().put("question", string()).put("choices", strings())
                .put(
                    "explanation",
                    string().put(
                        "description",
                        "For a knowledge check, reason out the answer here before committing correctAnswer. Hidden until the learner submits. Empty for ungraded questions."
                    )
                )
                .put(
                    "correctAnswer",
                    string().put(
                        "description",
                        "For a knowledge check, exact text of the ONE correct choice, or a committed reference answer for free response. Hidden until submission. Empty for ungraded preferences or exploratory questions."
                    )
                )
                .apply { if (phase == "teacher") put("nodeId", string()) })
        if (phase == "probe") {
            tool(
                "finish_probe",
                "After at least one learner answer, propose a short route for explicit learner approval. Not a knowledge or mastery claim.",
                JSONObject().put("summary", string()).put("steps", strings())
            )
        } else {
            tool(
                "plan_init",
                "Write or rewrite the session dependency plan, not the knowledge graph. Stable lowercase slug IDs; edges from prerequisite to dependent. Wait for learner approval before teaching.",
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
        tools: JSONArray = schemas(session.phase)
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
                    val schema = tools.objects().firstOrNull { it.getString("name") == name }
                        ?: throw ProviderFailure("tool_denied")
                    val args = JSONObject(item.strictString("arguments", 24_000))
                    val expected =
                        schema.getJSONObject("parameters").getJSONObject("properties").keys()
                            .asSequence().toSet()
                    android.util.Log.d(
                        "ReasonedRuntime",
                        "validating arguments: keys=${args.length()}; expected=${expected.size}"
                    )
                    val keys = args.keys().asSequence().toSet()
                    // Older saved/fixture calls have no committed answer; they remain explicitly ungraded.
                    if (keys != expected && !(name == "ask_question" && keys == expected - setOf(
                            "correctAnswer",
                            "explanation"
                        ))
                    )
                        throw ProviderFailure("invalid_tool_arguments")
                    when (name) {
                        "ask_question" -> {
                            android.util.Log.d(
                                "ReasonedRuntime",
                                "question shape: textChars=${(args.opt("question") as? String)?.length ?: -1}; choices=${
                                    args.optJSONArray("choices")?.length() ?: -1
                                }"
                            )
                            args.strictString("question", 2000)
                            if (session.phase == "teacher" && session.plan?.nodes?.none {
                                    it.id == args.strictString(
                                        "nodeId",
                                        80
                                    )
                                } != false)
                                throw ProviderFailure("invalid_plan_reference")
                            val choices = args.optJSONArray("choices")
                                ?: throw ProviderFailure("invalid_question_choices")
                            if (choices.length() != 0 && choices.length() !in 2..4) throw ProviderFailure(
                                "invalid_question_choices"
                            )
                            val labels = (0 until choices.length()).map {
                                (choices.get(it) as? String)?.takeIf { label -> label.isNotBlank() && label.length <= 200 }
                                    ?: throw ProviderFailure("invalid_question_choices")
                            }
                            if (labels.toSet().size != labels.size) throw ProviderFailure("invalid_question_choices")
                            if (args.has("correctAnswer")) {
                                val key =
                                    args.strictString("correctAnswer", 2000, allowEmpty = true)
                                val explanation =
                                    args.strictString("explanation", 4000, allowEmpty = true)
                                if (key.isNotEmpty() && (key.isBlank() || explanation.isBlank() || (labels.isNotEmpty() && key !in labels)))
                                    throw ProviderFailure("invalid_answer_key")
                            }
                        }

                        "plan_init" -> StudyPlan.parse(args)
                        "finish_probe" -> {
                            if (session.answers < 1) throw ProviderFailure("invalid_probe_handoff")
                            args.strictString("summary", 2000)
                            val steps = args.getJSONArray("steps")
                            if (steps.length() !in 1..8) throw ProviderFailure("invalid_output")
                            (0 until steps.length()).forEach {
                                val step = steps.get(it) as? String
                                    ?: throw ProviderFailure("invalid_output")
                                if (step.isBlank() || step.length > 400) throw ProviderFailure("invalid_output")
                            }
                        }

                        "finish_lesson" -> {
                            if (session.answers < 1) throw ProviderFailure("invalid_lesson_finish")
                            args.strictString("summary", 2000)
                        }
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
        return Validated(text.toString(), call, thinking.toString())
    }
}
