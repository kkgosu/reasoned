package com.kkgosu.reasoned.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.buffer
import okio.source
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest

internal class OAuthCallback(val code: String, val clientId: String)

/** Loopback only, ephemeral port, bounded HTTP parsing, one matching callback and a five-minute deadline. */
internal class LoopbackSignIn : Closeable {
    private val server =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }

    @Volatile
    private var socket: Socket? = null
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
    suspend fun await(state: String, clientId: String?): OAuthCallback = coroutineScope {
        val reader = async(Dispatchers.IO) {
            while (true) {
                currentCoroutineContext().ensureActive()
                val connection = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive(); throw error
                }
                socket = connection
                connection.use { peer ->
                    peer.soTimeout = 1000
                    try {
                        val source = peer.getInputStream().source().buffer()
                        val line = source.readUtf8LineStrict(8192).split(' ')
                        var headerBytes = 0
                        var host = ""
                        while (true) {
                            val header = source.readUtf8LineStrict(8192)
                            headerBytes += header.length + 2
                            if (headerBytes > 16384) throw IllegalArgumentException()
                            if (header.isEmpty()) break
                            if (header.substringBefore(':').equals("Host", true)) host =
                                header.substringAfter(':').trim()
                        }
                        val validRequest =
                            line.size == 3 && line[0] == "GET" && line[1].startsWith("/auth/callback?") && host == "127.0.0.1:${server.localPort}"
                        val url = if (validRequest) ("http://$host" + line[1]).toHttpUrl() else null
                        val unique =
                            url != null && url.queryParameterNames.all { url.queryParameterValues(it).size == 1 }
                        val matched = unique && MessageDigest.isEqual(
                            state.toByteArray(),
                            url!!.queryParameter("state").orEmpty().toByteArray()
                        )
                        val body =
                            if (matched) "Sign-in received. You can return to Reasoned." else "This sign-in callback was not accepted."
                        peer.getOutputStream()
                            .write(("HTTP/1.1 ${if (matched) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                        if (matched) {
                            if (url!!.queryParameter("error") != null) throw ProviderFailure("chatgpt_consent_declined")
                            val returnedId = url.queryParameter("client_id") ?: clientId
                            if (returnedId == null || !returnedId.matches(Regex("oaiapp_[A-Za-z0-9_-]{1,180}")) || (clientId != null && returnedId != clientId)) throw ProviderFailure(
                                "chatgpt_client_mismatch"
                            )
                            val code = url.queryParameter("code")?.takeIf { it.length in 1..4096 }
                                ?: throw ProviderFailure("chatgpt_callback_invalid")
                            return@async OAuthCallback(code, returnedId)
                        }
                    } catch (error: ProviderFailure) {
                        throw error
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        // Ignore unrelated local requests; only a matching state consumes the attempt.
                    }
                }
                socket = null
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
        try {
            reader.await()
        } finally {
            close()
        }
    }

    override fun close() {
        socket?.close(); server.close()
    }
}
