package com.kkgosu.learnharness.controllers

import com.kkgosu.learnharness.api.ProviderFailure
import com.kkgosu.learnharness.api.toolResult
import com.kkgosu.learnharness.model.LessonDisplayItem.Role
import com.kkgosu.learnharness.model.LessonDisplayItem.TextDisplayItem
import com.kkgosu.learnharness.model.RuntimeSession
import com.kkgosu.learnharness.utils.objects
import org.json.JSONArray

internal fun handoff(s: RuntimeSession): RuntimeSession {
    val input = JSONArray(s.protocol)
    if (s.routeCall.isNotEmpty() && input.objects()
            .none { it.optString("type") == "function_call_output" && it.optString("call_id") == s.routeCall }
    )
        input.put(
            toolResult(
                s.routeCall,
                "Probe assessment recorded. Prepare and present the first dependency plan with plan_init; wait for learner approval before teaching."
            )
        )
    return s.copy(
        phase = "teacher", status = "idle", routeCall = "", answers = 0,
        protocol = input.toString(), contextEnd = 0, contextTokens = 0, contextOutputTokens = 0
    )
}

internal fun append(s: RuntimeSession, text: String, role: Role = Role.PROSE): RuntimeSession {
    // Archive safety bound, independent of the much smaller model-facing context projection.
    if (s.items.size >= 10_000 || s.items.sumOf { it.text.length.toLong() } + text.length > 16_000_000) throw ProviderFailure(
        "session_limit"
    )
    return s.copy(
        items = s.items + TextDisplayItem(
            (s.items.lastOrNull()?.id ?: 0) + 1,
            text,
            role
        )
    )
}
