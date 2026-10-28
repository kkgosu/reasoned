package com.kkgosu.reasoned.controllers

import android.app.Application
import com.kkgosu.reasoned.ReasonedApplication
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.chatGptPayload
import com.kkgosu.reasoned.model.RuntimeSession
import org.json.JSONArray
import org.json.JSONObject

internal fun sessionInstructions(application: Application, s: RuntimeSession) =
    AgentInstructions.compose(application.assets, s.phase) +
            (if (s.phase == "practice" && !s.practiceReady) "\nDo not pose a new problem: wait for the explicit Next problem button. Current problem may be discussed/concluded. Active problem: ${s.activeProblem?.id ?: "none"}." else "") +
            (if (s.planPending) "\n\nThe learner has NOT approved the dependency plan. Discuss or revise it with plan_init according to their latest message. Do not teach, ask learning questions, make figures or finish the lesson until explicit approval." else "")

internal fun sessionRequest(
    application: Application,
    s: RuntimeSession,
    input: JSONArray,
    instructions: String,
    tools: JSONArray,
    publicSummary: Boolean = true
): JSONObject {
    val request =
        JSONObject().put("model", s.model).put("input", input).put("instructions", instructions)
            .put("tools", tools).put("parallel_tool_calls", false).put("store", false)
            .put("stream", true)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
    val efforts = (application as ReasonedApplication).modelCatalog[s.model]?.efforts.orEmpty()
    if (s.effort.isNotEmpty() && s.effort !in efforts) throw ProviderFailure("reasoning_not_supported")
    if (efforts.isNotEmpty() || s.effort.isNotEmpty()) request.put(
        "reasoning",
        JSONObject().apply {
            if (publicSummary && s.phase != "practice" && s.effort !in setOf(
                    "none",
                    "off"
                )
            ) put("summary", "auto")
            if (s.effort.isNotEmpty()) put("effort", s.effort)
        })
    return if (s.connection == "api") request.put(
        "max_output_tokens",
        4096
    ) else chatGptPayload(request, s.model)
}
