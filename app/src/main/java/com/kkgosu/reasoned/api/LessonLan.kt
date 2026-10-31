package com.kkgosu.reasoned.api

import android.content.Context
import com.kkgosu.reasoned.model.NativeTransferPackage
import com.kkgosu.reasoned.storage.CredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun lanDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun lanJson(bytes: ByteArray) = JSONObject(NativeTransferPackage.boundedJson(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()))
internal fun privateLanAddress(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() > 255 }) return false
    val a = parts[0].toInt(); val b = parts[1].toInt()
    return a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168
}

internal class LanPairing(raw: String) {
    val host: String
    val port: Int
    val fingerprint: String
    val key: String
    init {
        require(raw.length in 1..2048)
        val uri = URI(raw)
        require(uri.scheme == "learnharness-lan" && uri.rawAuthority == "pair" && uri.rawPath.isNullOrEmpty() && uri.rawFragment == null)
        val values = uri.rawQuery.orEmpty().split('&').map { entry ->
            val parts = entry.split('=', limit = 2); require(parts.size == 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        require(values.size == 4 && values.map { it.first }.toSet() == setOf("host", "port", "sha256", "key"))
        val fields = values.toMap()
        host = fields.getValue("host"); require(privateLanAddress(host))
        port = fields.getValue("port").toInt(); require(port in 1024..65535)
        fingerprint = fields.getValue("sha256"); require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        key = fields.getValue("key"); require(key.matches(Regex("[A-Za-z0-9_-]{43}")))
    }
    override fun toString() = "LAN pairing [redacted]"
}

internal class LanConnection(val host: String, val port: Int, val fingerprint: String, val token: String, val id: String = "", val name: String = "") {
    init {
        require(privateLanAddress(host) && port in 1024..65535 && fingerprint.matches(Regex("[a-f0-9]{64}")) && token.matches(Regex("[A-Za-z0-9_-]{43}")))
        require(id.isEmpty() || id.matches(Regex("[a-f0-9-]{36}")))
        require(name.length <= 200 && name.none { it.isISOControl() })
    }
    fun json() = JSONObject().put("host", host).put("port", port).put("fingerprint", fingerprint).put("token", token).put("id", id).put("name", name)
    override fun toString() = "LAN connection [redacted]"
    companion object {
        fun parse(value: JSONObject) = LanConnection(value.getString("host"), value.getInt("port"), value.getString("fingerprint"), value.getString("token"), value.optString("id"), value.optString("name"))
    }
}
internal class LanHttpFailure(val status: Int) : IOException("LAN request refused")

/** Dedicated, QR-pinned LAN client. Never shares public-web/provider trust, cookies or credentials. */
internal class LessonLan(context: Context, name: String = "lesson-lan") {
    private val credentials = CredentialStore(context, name, 4096)
    private val pendingPair = CredentialStore(context, "$name-pairing", 4096)
    private val presenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val onlineState = MutableStateFlow(false)
    val online = onlineState.asStateFlow()
    private var presenceWork: Job? = null
    private var foreground = false

    @Synchronized fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        restartPresence()
    }

    @Synchronized private fun restartPresence() {
        presenceWork?.cancel(); presenceWork = null; onlineState.value = false
        if (!foreground) return
        presenceWork = presenceScope.launch {
            val peer = withContext(Dispatchers.IO) { try { connection() } catch (_: Exception) { null } } ?: return@launch
            var misses = 0
            while (isActive) {
                val started = android.os.SystemClock.elapsedRealtime()
                val reached = try {
                    withTimeoutOrNull(900) { withContext(Dispatchers.IO) { status(peer) }; true } ?: false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (reached) { misses = 0; onlineState.value = true }
                else if (++misses >= 3) onlineState.value = false
                delay((1000L - (android.os.SystemClock.elapsedRealtime() - started)).coerceAtLeast(1))
            }
        }
    }

    fun connection(): LanConnection? = credentials.read()?.let { LanConnection.parse(lanJson(it.toByteArray(Charsets.UTF_8))) }
    fun forget() { credentials.forget(); pendingPair.forget(); restartPresence() }
    suspend fun status(connection: LanConnection) {
        val result = lanJson(request(connection, "GET", "/v1/status", limit = 1024))
        check(result.getString("id") == connection.id)
    }
    suspend fun pair(pairing: LanPairing, deviceName: String): LanConnection {
        val previous = pendingPair.read()?.let { LanConnection.parse(lanJson(it.toByteArray(Charsets.UTF_8))) }
        val token = previous?.takeIf { it.host == pairing.host && it.port == pairing.port && it.fingerprint == pairing.fingerprint }?.token
            ?: Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val candidate = LanConnection(pairing.host, pairing.port, pairing.fingerprint, token)
        pendingPair.save(candidate.json().toString()) // Retry can reconcile a consumed QR after a lost response.
        val result = lanJson(request(candidate, "POST", "/v1/pair", JSONObject().put("name", deviceName.take(100)).toString().toByteArray(), "application/json", mapOf("X-Pairing-Key" to pairing.key)))
        val connected = LanConnection(candidate.host, candidate.port, candidate.fingerprint, candidate.token, result.getString("id"), result.getString("name"))
        credentials.save(connected.json().toString()); pendingPair.forget(); restartPresence(); return connected
    }
    suspend fun lessons(connection: LanConnection) = lanJson(request(connection, "GET", "/v1/lessons"))
    suspend fun export(connection: LanConnection, ids: List<String>): ByteArray = request(connection, "POST", "/v1/export", JSONObject().put("ids", org.json.JSONArray(ids)).toString().toByteArray(), "application/json", limit = NativeTransferPackage.MAX_BYTES)
    suspend fun preview(connection: LanConnection, bytes: ByteArray) = lanJson(request(connection, "POST", "/v1/preview", bytes, "application/zip"))
    suspend fun install(connection: LanConnection, bytes: ByteArray, operation: String, revision: String, choices: JSONObject): JSONObject {
        require(operation.matches(Regex("[a-f0-9-]{36}")))
        return lanJson(request(connection, "POST", "/v1/import/$operation", bytes, "application/zip", mapOf("X-Transfer-Revision" to revision, "X-Transfer-Choices" to choices.toString(), "X-Transfer-Sha" to lanDigest(bytes))))
    }
    suspend fun receipt(connection: LanConnection, operation: String): JSONObject? {
        require(operation.matches(Regex("[a-f0-9-]{36}")))
        return try { lanJson(request(connection, "GET", "/v1/receipts/$operation")) } catch (e: LanHttpFailure) { if (e.status == 404) null else throw e }
    }
    private suspend fun request(connection: LanConnection, method: String, path: String, bytes: ByteArray? = null, type: String = "application/json", headers: Map<String, String> = emptyMap(), limit: Int = 1024 * 1024): ByteArray {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || lanDigest(chain[0].encoded) != connection.fingerprint) throw java.security.cert.CertificateException("LAN certificate mismatch")
                chain[0].checkValidity()
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val client = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            // The QR fingerprint authenticates this exact identity, not the certificate's DNS SAN.
            .hostnameVerifier { host, session -> host == connection.host && (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { lanDigest(it.encoded) == connection.fingerprint } == true }
            .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("https://${connection.host}:${connection.port}$path").header("Authorization", "Bearer ${connection.token}")
        headers.forEach { (key, value) -> request.header(key, value) }
        request.method(method, bytes?.toRequestBody(type.toMediaType()))
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                    if (continuation.isActive) continuation.resumeWithException(IOException("LAN connection failed", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { value ->
                            if (!value.isSuccessful) throw LanHttpFailure(value.code)
                            require(value.body.contentLength() <= limit)
                            val output = ByteArrayOutputStream()
                            value.body.byteStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size().toLong() + count <= limit); output.write(buffer, 0, count) }
                            }
                            output.toByteArray()
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(if (e is LanHttpFailure) e else IOException("LAN response rejected")) }
                    finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
                }
            })
        }
    }
}
