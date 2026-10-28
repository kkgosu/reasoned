package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.controllers.QuestionContract
import com.kkgosu.reasoned.controllers.RuntimeTools
import com.kkgosu.reasoned.model.RuntimeQuestion
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuestionContractTest {
    private fun options(count: Int = 6) = JSONArray((1..count).map {
        JSONObject().put("label", if (it < 3) "Same visible label" else "Option $it")
            .put("value", "id-$it")
    })

    private fun question() =
        JSONObject().put("question", "Select both.").put("details", "A longer question body.")
            .put("options", options()).put("multiSelect", true)
            .put("correctAnswer", JSONArray(listOf("id-6", "id-1")))
            .put("explanation", "COMMITTED_HIDDEN_EXPLANATION")

    private fun response(name: String, args: JSONObject, id: String) =
        JSONObject().put("status", "completed")
            .put(
                "output", JSONArray().put(
                    JSONObject().put("type", "function_call").put("status", "completed")
                        .put("name", name).put("call_id", id).put("arguments", args.toString())
                )
            )

    private fun clarification() = JSONObject().put("question", "Next topic?").put("details", "")
        .put("multiSelect", false).put("options", JSONArray())

    private fun rejected(args: JSONObject) {
        try {
            RuntimeTools.validate(
                response("probe", args, "bad"),
                RuntimeSession(goal = "Fixture", model = "fixture")
            ); fail("Invalid question accepted")
        } catch (_: ProviderFailure) {
        }
    }

    @Test
    fun stableIdentitySetGradingAndLegacyFallbackDoNotDependOnLabels() {
        val args = question()
        RuntimeTools.validate(
            response("probe", args, "probe"),
            RuntimeSession(goal = "Fixture", model = "fixture")
        )
        val q = QuestionContract.parse("probe", args, "probe", "probe")
        assertEquals(q, RuntimeQuestion.parse(q.json()))
        val answer = q.answer("My reasoning", -1, 80, "", false, listOf("id-1", "id-6"))
        assertTrue(answer.getBoolean("correct"))
        assertEquals(
            listOf("id-1", "id-6"),
            answer.getJSONArray("selectedValues").strings(32, 2000)
        )
        assertEquals(6, answer.getJSONArray("selections").getJSONObject(1).getInt("index"))
        assertFalse(q.answer("", -1, 80, "", false, listOf("id-2", "id-6")).getBoolean("correct"))
        assertFalse(q.canAnswer("", listOf("id-1", "unknown"), false))
        assertFalse(q.canAnswer("", listOf("id-1", "id-1"), false))
        assertFalse(q.canAnswer("Only prose", emptyList(), false))
        assertFalse(q.transcript().contains("COMMITTED_HIDDEN_EXPLANATION"))
        rejected(question().put("correctAnswer", "Same visible label"))
        rejected(question().put("multiSelect", false))
        rejected(question().put("correctAnswer", JSONArray(listOf("id-1", "id-1"))))
        rejected(question().put("options", options(33)))
        rejected(question().apply { getJSONArray("options").getJSONObject(1).put("value", "id-1") })
        val fallback =
            question().apply { getJSONArray("options").getJSONObject(5).put("value", "") }
                .put("correctAnswer", "Option 6").put("multiSelect", false)
        assertEquals(
            listOf("Option 6"),
            QuestionContract.parse("fallback", fallback, "probe", "probe").correctValues
        )
        val legacy = RuntimeQuestion("old", "Old question", listOf("Left", "Right"), "Left")
        val oldJson = legacy.json().apply {
            listOf(
                "values",
                "correctValues",
                "multiSelect",
                "details",
                "descriptions"
            ).forEach(::remove)
        }
        assertEquals(legacy, RuntimeQuestion.parse(oldJson))
        assertTrue(
            RuntimeQuestion.parse(oldJson).answer("", 0, 60, "", false).getBoolean("correct")
        )
        val askArgs = clarification().put(
            "options",
            options().apply { objects().forEach { it.put("description", "More detail") } })
        RuntimeTools.validate(
            response("ask_user_question", askArgs, "ask"),
            RuntimeSession(goal = "Fixture", model = "fixture")
        )
        val ask = QuestionContract.parse("ask", askArgs, "probe", "ask_user_question")
        assertEquals("More detail", ask.descriptions.first())
        val other = ask.answer("Written Other", -1, 80, "", false, emptyList())
        assertEquals("Written Other", other.getString("answer"))
        assertFalse(other.has("correct")); assertFalse(other.has("confidence_self_report"))
    }
}
