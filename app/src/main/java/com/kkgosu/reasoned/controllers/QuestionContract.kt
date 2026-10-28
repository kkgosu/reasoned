package com.kkgosu.reasoned.controllers

import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.model.RuntimeQuestion
import com.kkgosu.reasoned.utils.exactKeys
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strictString
import com.kkgosu.reasoned.utils.strings
import org.json.JSONArray
import org.json.JSONObject

/** Source identities and commitments. Display recovery must never run at this boundary. */
internal object QuestionContract {
    const val MAX_OPTIONS = 32

    fun properties(name: String): JSONObject {
        fun string() = JSONObject().put("type", "string")
        val ask = name == "ask_user_question"
        return JSONObject().put("question", string()).put("details", string()).apply {
            if (name !in setOf("explain", "recall")) {
                val option = JSONObject().put("label", string()).put("value", string())
                if (ask) option.put("description", string())
                put(
                    "options", JSONObject().put("type", "array").put("maxItems", MAX_OPTIONS)
                        .put("items", ToolSchemas.objectSchema(option))
                )
                put("multiSelect", JSONObject().put("type", "boolean"))
                if (!ask) put("explanation", string())
            }
            if (!ask) put(
                "correctAnswer", if (name in setOf("explain", "recall")) string() else
                    JSONObject().put(
                        "anyOf", JSONArray().put(string()).put(
                            JSONObject().put("type", "array")
                                .put("items", string()).put("minItems", 1)
                                .put("maxItems", MAX_OPTIONS)
                        )
                    )
            )
        }
    }

    fun parse(call: String, args: JSONObject, phase: String, name: String): RuntimeQuestion {
        val ask = name == "ask_user_question"
        val recall = name in setOf("explain", "recall")
        val options = if (recall) emptyList() else args.getJSONArray("options").objects()
        if (!recall && (options.size > MAX_OPTIONS || (options.size < 2 && !(ask && options.isEmpty()))))
            throw ProviderFailure("invalid_question_choices")
        val labels = options.map { option ->
            option.exactKeys(
                if (ask) setOf("label", "value", "description") else setOf(
                    "label",
                    "value"
                )
            )
            option.strictString("label", 4000)
        }
        val values = options.mapIndexed { index, option ->
            option.strictString("value", 2000, allowEmpty = true)
                .trim().ifEmpty { labels[index].trim() }
        }
        if (values.distinct().size != values.size) throw ProviderFailure("invalid_question_choices")
        val multi = if (recall) false else args.get("multiSelect") as? Boolean
            ?: throw ProviderFailure("invalid_tool_arguments")
        val reference =
            if (ask) "" else if (recall) args.strictString("correctAnswer", 8000) else ""
        val correct =
            if (ask || recall) emptyList() else when (val key = args.get("correctAnswer")) {
                is String -> listOf(key)
                is JSONArray -> key.strings(MAX_OPTIONS, 4000)
                else -> throw ProviderFailure("invalid_answer_key")
            }
        if (!ask && !recall && (correct.isEmpty() || correct.distinct().size != correct.size ||
                    correct.any { it !in values } || (!multi && correct.size != 1))
        )
            throw ProviderFailure("invalid_answer_key")
        return RuntimeQuestion(
            call, args.strictString("question", 6000), labels,
            if (recall) reference else correct.joinToString("\n"),
            if (ask || recall) "" else args.strictString("explanation", 8000), phase,
            if (ask) "ask" else args.optString("type"), requiresChoice = !ask && !recall,
            values = values, correctValues = correct, multiSelect = multi,
            details = args.strictString("details", 4000, allowEmpty = true),
            descriptions = if (ask) options.map {
                it.strictString(
                    "description",
                    4000,
                    allowEmpty = true
                )
            } else emptyList())
    }
}
