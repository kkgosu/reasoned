package com.kkgosu.learnharness.api

import org.json.JSONObject
import java.io.IOException

fun interface ModelProvider {
    suspend fun respond(request: JSONObject, delta: suspend (String) -> Unit): JSONObject
}

class ProviderFailure(val reason: String) : IOException(reason)
