package com.kkgosu.learnharness.model

import org.json.JSONObject

internal data class ResearchSource(
    val url: String,
    val title: String,
    val text: String,
    val retrieved: Long
) {
    fun json() = JSONObject().put("url", url).put("title", title).put("text", text)
        .put("retrieved", retrieved).put("sha256", fingerprint(text))

    companion object {
        fun parse(json: JSONObject) = ResearchSource(
            json.getString("url"),
            json.getString("title"),
            json.getString("text"),
            json.getLong("retrieved")
        )
    }
}
