package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.controllers.AgentInstructions
import com.kkgosu.reasoned.model.LessonDisplayItem.Role
import com.kkgosu.reasoned.model.LessonDisplayItem.TextDisplayItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class RuntimeSession(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val model: String,
    val phase: String = "probe",
    val status: String = "idle",
    val protocol: String = "[]",
    val items: List<TextDisplayItem> = emptyList(),
    val question: RuntimeQuestion? = null,
    val draft: String = "",
    val selection: Int = -1,
    val confidence: Int = 60,
    val expanded: Boolean = true,
    val route: String = "",
    val routeCall: String = "",
    val answers: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val error: String = "",
    val promptVersion: String = AgentInstructions.VERSION,
    val connection: String = "api",
    val effort: String = "",
    val contextTokens: Long = 0,
    val plan: StudyPlan? = null,
    val proseIds: Set<Long>? = null, // Null only for legacy checkpoints without canonical passage IDs.
    // Reconstructed from question/answer evidence, including old checkpoints without question rows.
    val answerCards: Map<Long, LessonDisplayItem.AnsweredDisplayItem> = emptyMap(),
    val planPending: Boolean = status == "plan",
    val practiceMaterial: String = "[]",
    val problems: List<PracticeProblem> = emptyList(),
    val research: List<ResearchReport> = emptyList(),
    val researchAllowed: Boolean = false,
    val practiceReady: Boolean = true,
    val readingThinking: String = "",
    val dontKnow: Boolean = false,
    val assessment: ProbeAssessment? = null,
    val planApproved: Boolean = plan != null && !planPending,
    val mechanic: String = "choice",
    val compaction: ContextSummary? = null,
    val contextEnd: Int = 0,
    val contextOutputTokens: Long = 0,
    val compacting: Boolean = false, // Transient: reopening never restarts a summary request.
    val selectedValues: List<String> = listOfNotNull(question?.values?.getOrNull(selection)),
    // Separate SQLite columns/tables: never fold opaque native history into the local checkpoint.
    val nativeRecord: NativeChat? = null,
    val nativeDisplayIds: Map<NativeChat.Part, Long> = emptyMap(),
) {
    val activeProblem get() = problems.lastOrNull()?.takeIf { it.outcome.isEmpty() }
    val displayItems: List<LessonDisplayItem>
        get() = items.mapIndexedNotNull { index, item ->
            answerCards[item.id] ?: item.takeUnless {
                it.role == Role.QUESTION && items.getOrNull(index + 1)?.id in answerCards
            }
        }

    fun checkpoint() =
        JSONObject().put("phase", phase).put("status", status).put("protocol", protocol)
            .put("question", question?.json()).put("draft", draft).put("selection", selection)
            .put("confidence", confidence).put("expanded", expanded).put("route", route)
            .put("routeCall", routeCall)
            .put("answers", answers).put("inputTokens", inputTokens)
            .put("outputTokens", outputTokens)
            .put("error", error).put("promptVersion", promptVersion).put("connection", connection)
            .put("effort", effort).put("contextTokens", contextTokens).put("plan", plan?.json())
            .put("planPending", planPending).put("practiceMaterial", practiceMaterial)
            .put("problems", JSONArray(problems.map { it.json() }))
            .put("research", JSONArray(research.map { it.json() }))
            .put("researchAllowed", researchAllowed).put("practiceReady", practiceReady)
            .put("readingThinking", readingThinking).put("dontKnow", dontKnow)
            .put("assessment", assessment?.json()).put("planApproved", planApproved)
            .put("mechanic", mechanic).put("compaction", compaction?.json())
            .put("contextEnd", contextEnd).put("contextOutputTokens", contextOutputTokens)
            .put("selectedValues", JSONArray(selectedValues))
            .put("proseIds", proseIds?.let { JSONArray(it.toList()) }).toString()
}
