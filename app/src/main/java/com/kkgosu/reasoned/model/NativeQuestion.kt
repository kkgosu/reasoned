package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.controllers.QuestionContract
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strictString
import com.kkgosu.reasoned.utils.strings
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import kotlin.math.floor

/**
 * Native question/answer projection, not a new question engine or a history migration.
 * LearnHarness 3dd52110bbcbcba81645453ce0a8fdc230594c47 (AGPL-3.0):
 * core/question-core.ts and components/question-dock.tsx. Legacy question parsing stays separate:
 * native optional fields, stable shuffle and full answer details have different persistence laws.
 */
internal class NativeQuestion private constructor(
    val tool: String,
    val question: RuntimeQuestion,
    private val context: String?,
) {
    val kind get() = when (tool) { "probe", "quiz" -> "quiz"; "recall", "explain" -> "explain"; else -> "ask" }
    val mode get() = if (question.choices.isEmpty()) "text" else if (question.multiSelect) "multi-select" else "single-select"
    val needsReveal get() = tool in setOf("quiz", "explain") && question.phase == "teacher" && question.type != "predict"

    /** Same answer-free projection as toClientQuestion; never put commitments in a pending dock. */
    fun clientQuestion(): JSONObject = JSONObject().put("id", question.callId).put("kind", kind)
        .put("question", question.text).put("context", context).put("mode", mode)
        .put("options", JSONArray(question.values.indices.map { i ->
            JSONObject().put("label", question.choices[i]).put("value", question.values[i]).apply {
                question.descriptions.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
            }
        }))

    /** Local learner input only. Never accept imported graded details as a fresh learner action. */
    fun answer(request: JSONObject, now: Long): JSONObject {
        require(request.keys().asSequence().all { it in setOf("dontKnow", "values", "other", "text", "thinking", "readingThinking", "confidence") })
        val dontKnow = if (request.has("dontKnow")) request.get("dontKnow") as? Boolean
            ?: throw IllegalArgumentException("Invalid don't-know flag") else false
        val values = if (request.has("values")) request.getJSONArray("values").strings(QuestionContract.MAX_OPTIONS, 2000) else emptyList()
        fun text(key: String) = if (request.has(key)) request.strictString(key, 2000, true).nativeTrim() else ""
        val other = text("other")
        val text = text("text")
        val thinking = text("thinking")
        val reading = text("readingThinking")
        require(values.distinct().size == values.size && values.all { it in question.values }) { "Invalid native selection" }
        require(question.multiSelect || values.size <= 1) { "Multiple answers to a single-select question" }
        require(kind == "ask" || other.isEmpty()) { "Other is only an ask answer" }
        require(mode == "text" || text.isEmpty()) { "Text is only a text-mode answer" }
        require(!dontKnow || (kind != "ask" && values.isEmpty() && text.isEmpty() && other.isEmpty())) { "Invalid don't-know answer" }
        require(kind != "ask" || question.multiSelect || values.isEmpty() || other.isEmpty()) { "Other conflicts with a single selection" }
        require(question.canAnswer(if (mode == "text") text else other, values, dontKnow)) { "Empty native answer" }
        val indices = question.values.indices.filter { question.values[it] in values }
        return JSONObject().put("kind", kind).put("tool", tool).put("questionText", question.text)
            .put("options", JSONArray(question.choices.mapIndexed { i, label -> JSONObject().put("index", i + 1).put("label", label) }))
            .put("dontKnow", dontKnow)
            .put("answers", JSONArray(indices.map { i ->
                JSONObject().put("label", question.choices[i]).put("value", question.values[i]).put("index", i + 1)
            }))
            .put("answeredAt", nativeTime(now)).apply {
                // The native dock snapshots a role only for MC; explain still obeys the reveal law.
                if (kind == "quiz" && question.type.isNotEmpty()) put("questionType", question.type)
                if (other.isNotEmpty()) put("other", other)
                if (text.isNotEmpty()) put("text", text)
                if (thinking.isNotEmpty()) put("thinking", thinking)
                if (reading.isNotEmpty()) put("readingThinking", reading)
                if (kind == "quiz") {
                    put("correctIndices", JSONArray(question.values.indices.filter { question.values[it] in question.correctValues }.map { it + 1 }))
                    put("explanation", question.explanation)
                    put("correct", !dontKnow && values.toSet() == question.correctValues.toSet())
                    if (!dontKnow && request.has("confidence")) {
                        val confidence = (request.get("confidence") as? Number)?.toDouble()
                            ?: throw IllegalArgumentException("Invalid native confidence")
                        require(confidence.isFinite())
                        put("confidence", floor(confidence.coerceIn(0.0, 100.0) + 0.5).toInt())
                    }
                }
                if (kind == "explain") put("committed", question.correctAnswer)
            }
    }

    companion object {
        val CONFIDENCE_STOPS = listOf(0, 30, 50, 70, 90, 100)
        const val DEFAULT_CONFIDENCE = 50

        /** Bounded display seam for supported native calls; does not rewrite archived arguments. */
        fun parse(call: NativeChat.Call, phase: String): NativeQuestion {
            require(call.tool in NativeChat.QUESTION_TOOLS && call.id.isNotBlank() && call.id.length <= 512)
            val args = call.input ?: throw IllegalArgumentException("Missing native question arguments")
            val ask = call.tool == "ask_user_question"
            val recall = call.tool in setOf("recall", "explain")
            val rawOptions = args.opt("options")
            val options = when {
                recall || (ask && rawOptions == null) -> emptyList()
                rawOptions is JSONArray -> rawOptions.objects()
                !ask && rawOptions is String -> JSONArray(rawOptions).objects()
                else -> throw IllegalArgumentException("Invalid native options")
            }
            require(options.size <= QuestionContract.MAX_OPTIONS && (recall || (ask && !args.has("options")) || options.size >= 2))
            val normalized = options.map { option ->
                val label = scalar(option.get("label"), 4000).nativeTrim()
                require(label.isNotEmpty())
                val value = if (option.has("value")) scalar(option.get("value"), 2000).nativeTrim().ifEmpty { label } else label
                val description = if (ask && option.has("description")) scalar(option.get("description"), 4000).nativeTrim() else ""
                Triple(label, value, description)
            }
            require(normalized.map { it.second }.distinct().size == normalized.size) { "Duplicate native option values" }
            val multi = if (recall || !args.has("multiSelect")) false else when (val raw = args.get("multiSelect")) {
                is Boolean -> raw
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Invalid native multi-select flag")
            }
            val ordered = normalized.toMutableList()
            if (!ask && !recall) {
                var seed = -2128831035 // FNV-1a over UTF-16 code units, matching JavaScript charCodeAt.
                call.id.forEach { seed = (seed xor it.code) * 16777619 }
                for (i in ordered.lastIndex downTo 1) {
                    seed += 0x6d2b79f5
                    var t = (seed xor (seed ushr 15)) * (1 or seed)
                    t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
                    val random = (t xor (t ushr 14)).toLong() and 0xffffffffL
                    val j = (random * (i + 1) / 4294967296L).toInt()
                    val old = ordered[i]; ordered[i] = ordered[j]; ordered[j] = old
                }
            }
            val values = ordered.map { it.second }
            val correct = if (ask || recall) emptyList() else correctKeys(args.get("correctAnswer"), values)
            require(ask || recall || (correct.isNotEmpty() && correct.all { it in values } && (multi || correct.distinct().size == 1))) { "Invalid native answer key" }
            val correctValues = values.filter { it in correct }
            val reference = if (recall) scalar(args.get("correctAnswer"), 8000).also { require(it.nativeTrim().isNotEmpty()) } else correctValues.joinToString("\n")
            val context = if (args.has("details")) scalar(args.get("details"), 4000) else null
            val type = (args.opt("type") as? String)?.nativeDebris()
                ?.takeIf { it in setOf("predict", "check") }.orEmpty()
            return NativeQuestion(call.tool, RuntimeQuestion(
                call.id, scalar(args.get("question"), 6000), ordered.map { it.first }, reference,
                if (!ask && !recall) scalar(args.get("explanation"), 8000) else "",
                if (phase == "teach") "teacher" else phase, if (ask) "ask" else type,
                requiresChoice = !ask && !recall, values = values, correctValues = correctValues,
                multiSelect = multi, details = context.orEmpty(),
                descriptions = if (ask) ordered.map { it.third } else emptyList()
            ), context)
        }

        private fun scalar(raw: Any, max: Int): String = when (raw) {
            is String -> raw
            is Number, is Boolean -> raw.toString()
            else -> throw IllegalArgumentException("Invalid native string")
        }.also { require(it.length <= max) }

        private fun correctKeys(raw: Any, values: List<String>): List<String> {
            fun clean(raw: Any): String = scalar(raw, 4000).nativeTrim().let { if (it in values) it else it.nativeDebris() }
            if (raw is JSONArray) return (0 until raw.length()).map { clean(raw.get(it)) }
            val text = scalar(raw, 4000).nativeTrim()
            if (text in values) return listOf(text)
            if (text.startsWith("[") && text.endsWith("]")) {
                val array = try { JSONArray(text) } catch (_: org.json.JSONException) { null }
                if (array != null) return (0 until array.length()).map { clean(array.get(it)) }
            }
            return listOf(clean(raw))
        }
    }
}

// ECMAScript whitespace, including BOM, not Java/Kotlin's different Unicode whitespace set.
private fun Char.nativeSpace() = this in '\u0009'..'\u000d' || this in '\u2000'..'\u200a' || this in " \u00a0\u1680\u2028\u2029\u202f\u205f\u3000\ufeff"
internal fun String.nativeTrim() = trim { it.nativeSpace() }
private fun String.nativeDebris() = trim { it.nativeSpace() || it in ":.\"'`" }
internal fun nativeTime(now: Long): String = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(now))
