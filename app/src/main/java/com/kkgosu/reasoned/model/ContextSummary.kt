package com.kkgosu.reasoned.model

import com.kkgosu.reasoned.utils.strictString
import org.json.JSONObject

internal data class ContextSummary(
    val cut: Int,
    val text: String,
    val model: String,
    val at: Long,
    val tokensBefore: Long
) {
    fun json() = JSONObject().put("cut", cut).put("text", text).put("model", model).put("at", at)
        .put("tokensBefore", tokensBefore)

    companion object {
        fun parse(value: JSONObject) = ContextSummary(
            value.getInt("cut"), value.strictString("text", 24_000),
            value.getString("model"), value.getLong("at"), value.getLong("tokensBefore")
        ).also {
            require(it.cut > 0 && it.tokensBefore >= 0)
        }
    }
}
