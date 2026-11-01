package com.kkgosu.reasoned.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun openAiHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

/**
 * ChatGPT plan transport. The plan speaks Responses under its own base URL and permits only
 * namespaced tools, so its payload projection stays here rather than in the shared builder.
 */
class OpenAiProvider(
    private val accessToken: suspend () -> String,
    private val client: OkHttpClient = openAiHttpClient(),
) : ModelProvider {
    override suspend fun respond(request: JSONObject, delta: suspend (String) -> Unit): JSONObject {
        val key = accessToken()
        val payload = chatGptPayload(request, request.getString("model"))
        val call = client.newCall(
            Request.Builder().url("https://api.openai.com/v1/responses")
                .header("Authorization", "Bearer $key")
                .header("Accept", "text/event-stream")
                .post(
                    payload.toString()
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                ).build()
        )
        // Cancellation must close a blocked body read as well as a request awaiting headers.
        return coroutineScope {
            val reader = async(Dispatchers.IO) {
                try {
                    call.execute().use {
                        if (!it.isSuccessful) {
                            val source = it.body.source()
                            val body = if (source.request(65_537)) "" else source.readUtf8()
                            throw httpFailure(it.code, body)
                        }
                        readResponseStream(it.body.source(), delta)
                    }
                } catch (error: IOException) {
                    // Closing a blocked socket can throw IOException after its coroutine was cancelled.
                    currentCoroutineContext().ensureActive()
                    throw error
                }
            }
            try {
                reader.await()
            } finally {
                call.cancel()
            }
        }
    }
}
