package com.kkgosu.learnharness.model

import com.kkgosu.learnharness.api.ProviderFailure
import com.kkgosu.learnharness.utils.exactKeys
import com.kkgosu.learnharness.utils.objects
import com.kkgosu.learnharness.utils.strictString
import org.json.JSONArray
import org.json.JSONObject

/** Pinned LearnHarness probe contract: one evidence-based assessment per dependency strand. */
internal data class ProbeAssessment(val strands: List<Strand>, val summary: String = "") {
    data class Strand(val strand: String, val assessment: String)

    fun json() = JSONObject().put("summary", summary).put("strands", JSONArray(strands.map {
        JSONObject().put("strand", it.strand).put("assessment", it.assessment)
    }))

    fun transcript() = (listOf(summary) + strands.map { "### ${it.strand}\n\n${it.assessment}" })
        .filter { it.isNotBlank() }.joinToString("\n\n")

    companion object {
        fun parse(value: JSONObject): ProbeAssessment {
            val keys = value.keys().asSequence().toSet()
            if (keys != setOf("strands", "summary") && keys != setOf("strands"))
                throw ProviderFailure("invalid_probe_assessment")
            val strands = value.getJSONArray("strands").objects().map {
                it.exactKeys(setOf("strand", "assessment"))
                Strand(it.strictString("strand", 160), it.strictString("assessment", 2000))
            }
            if (strands.size !in 1..24 || value.toString().length > 20_000)
                throw ProviderFailure("invalid_probe_assessment")
            return ProbeAssessment(
                strands,
                if (value.has("summary")) value.strictString(
                    "summary",
                    2000,
                    allowEmpty = true
                ) else ""
            )
        }
    }
}
