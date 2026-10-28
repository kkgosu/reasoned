package com.kkgosu.learnharness.controllers

import org.json.JSONArray
import org.json.JSONObject

internal object ToolSchemas {
    fun questionTool(phase: String, mechanic: String) = when {
        mechanic == "recall" -> if (phase == "probe") "recall" else "explain"
        else -> if (phase == "probe") "probe" else "quiz"
    }

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

}
