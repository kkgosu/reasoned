package com.kkgosu.learnharness.utils

import com.kkgosu.learnharness.api.ProviderFailure
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.strictString(name: String, max: Int, allowEmpty: Boolean = false): String {
    val value = opt(name) as? String ?: throw ProviderFailure("invalid_output")
    if (value.length > max || (!allowEmpty && value.isBlank())) throw ProviderFailure("invalid_output")
    return value
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun JSONObject.exactKeys(expected: Set<String>) {
    if (keys().asSequence().toSet() != expected) throw ProviderFailure("invalid_tool_arguments")
}

internal fun JSONArray.strings(maxCount: Int, maxLength: Int): List<String> {
    if (length() > maxCount) throw ProviderFailure("knowledge_limit")
    return (0 until length()).map { index ->
        (get(index) as? String)?.takeIf { it.isNotBlank() && it.length <= maxLength }
            ?: throw ProviderFailure("invalid_findings")
    }
}
