package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.utils.strings
import org.json.JSONArray
import org.json.JSONObject

/** Immutable precommitment and append-only learner evidence; never a mastery score. */
internal data class PracticeProblem(
    val id: String,
    val statement: String,
    val solution: String,
    val mode: String,
    val targets: List<String>,
    val attempts: List<String> = emptyList(),
    val outcome: String = "",
    val verdict: String = "",
    val rung: String = "",
    val missClass: String = "",
    val retest: Boolean = false,
) {
    fun json() =
        JSONObject().put("id", id).put("statement", statement).put("modelSolution", solution)
            .put("mode", mode).put("target", JSONArray(targets))
            .put("attempts", JSONArray(attempts))
            .put("outcome", outcome).put("verdict", verdict).put("rung", rung)
            .put("missClass", missClass).put("retest", retest)

    companion object {
        fun parse(json: JSONObject) = PracticeProblem(
            json.getString("id"),
            json.getString("statement"),
            json.getString("modelSolution"),
            json.getString("mode"),
            json.getJSONArray("target").strings(8, 100),
            json.getJSONArray("attempts").strings(40, 2000),
            json.optString("outcome"),
            json.optString("verdict"),
            json.optString("rung"),
            json.optString("missClass"),
            json.optBoolean("retest")
        )
    }
}
