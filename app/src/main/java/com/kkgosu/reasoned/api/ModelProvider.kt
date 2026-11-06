package com.kkgosu.reasoned.api

import org.json.JSONObject
import java.io.IOException

fun interface ModelProvider {
    suspend fun respond(request: JSONObject, delta: suspend (String) -> Unit): JSONObject

    /**
     * Session id for endpoints that route a conversation by header. The plan transport ignores it.
     * The two-argument form stays the SAM target, so a mock can still implement one method.
     */
    suspend fun respond(
        request: JSONObject,
        delta: suspend (String) -> Unit,
        sessionId: String?,
    ): JSONObject = respond(request, delta)
}

class ProviderFailure(val reason: String, internal val response: JSONObject? = null) : IOException(reason)
