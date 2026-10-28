package com.kkgosu.learnharness.api

import org.json.JSONObject
import java.io.IOException

/** The fake provider exercises the same runner without credentials or network. */
fun interface ModelProvider {
    suspend fun respond(request: JSONObject, delta: suspend (String) -> Unit): JSONObject
}

class ProviderFailure(val reason: String) : IOException(reason)
