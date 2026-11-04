package com.kkgosu.reasoned.controllers

import android.app.Application
import com.kkgosu.reasoned.ReasonedApplication
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.model.RuntimeSession
import org.json.JSONArray
import org.json.JSONObject

internal fun sessionInstructions(application: Application, s: RuntimeSession) =
    AgentInstructions.compose(application.assets, s.phase) +
            (if (s.phase == "practice" && !s.practiceReady) "\nDo not pose a new problem: wait for the explicit Next problem button. Current problem may be discussed/concluded. Active problem: ${s.activeProblem?.id ?: "none"}." else "") +
            (if (s.planPending) "\n\nThe learner has NOT approved the dependency plan. Discuss or revise it with plan_init according to their latest message. Do not teach, ask learning questions, make figures or finish the lesson until explicit approval." else "")

/**
 * The vendor-neutral request every transport and sub-agent shares. Provider-specific fields
 * (token caps, payload shape, auth) are added by the wire adapter, never here.
 */
internal fun canonicalRequest(
    model: String,
    instructions: String,
    input: JSONArray,
    tools: JSONArray,
    effort: String = "",
    reasoning: Boolean = false,
    publicSummary: Boolean = false,
    practice: Boolean = false,
    maxOutput: Int = 4096
): JSONObject {
    val request = JSONObject().put("model", model).put("input", input).put("instructions", instructions)
        .put("tools", tools).put("parallel_tool_calls", false).put("store", false)
        .put("stream", true).put("max_output_tokens", maxOutput)
        .put("include", JSONArray().put("reasoning.encrypted_content"))
    if (effort.isNotEmpty() || reasoning) request.put(
        "reasoning",
        JSONObject().apply {
            if (publicSummary && !practice && effort !in setOf("none", "off")) put("summary", "auto")
            if (effort.isNotEmpty()) put("effort", effort)
        }
    )
    return request
}

internal fun sessionRequest(
    application: Application,
    s: RuntimeSession,
    input: JSONArray,
    instructions: String,
    tools: JSONArray,
    publicSummary: Boolean = true
): JSONObject {
    val efforts = (application as ReasonedApplication).modelCatalog.model(s.connection, s.model)?.efforts.orEmpty()
    if (s.effort.isNotEmpty() && s.effort !in efforts) throw ProviderFailure("reasoning_not_supported")
    return canonicalRequest(
        s.model, instructions, input, tools, s.effort,
        reasoning = efforts.isNotEmpty(), publicSummary = publicSummary, practice = s.phase == "practice"
    )
}
