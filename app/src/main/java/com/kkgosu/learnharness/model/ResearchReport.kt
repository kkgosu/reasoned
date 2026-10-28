package com.kkgosu.learnharness.model

import com.kkgosu.learnharness.utils.objects
import com.kkgosu.learnharness.utils.strings
import org.json.JSONArray
import org.json.JSONObject

internal data class ResearchReport(
    val question: String,
    val brief: String,
    val sources: List<ResearchSource>,
    val limitations: List<String>
) {
    fun json() = JSONObject().put("question", question).put("brief", brief)
        .put("sources", JSONArray(sources.map { it.json() }))
        .put("limitations", JSONArray(limitations))

    fun transcript() = brief + "\n\nSources actually read (not independent verification):\n" +
            sources.joinToString("\n") {
                "- [${
                    it.title.replace(
                        Regex("[\\[\\]\\n\\r]"),
                        " "
                    )
                }](${it.url})"
            } +
            if (limitations.isEmpty()) "" else "\n\nLimitations: " + limitations.joinToString(", ")

    companion object {
        fun parse(json: JSONObject) = ResearchReport(
            json.getString("question"),
            json.getString("brief"),
            json.getJSONArray("sources").objects().map(ResearchSource::parse),
            json.getJSONArray("limitations").strings(20, 100)
        )
    }
}
