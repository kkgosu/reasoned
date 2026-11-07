package com.kkgosu.reasoned.api

import com.kkgosu.reasoned.utils.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.HashingSource
import okio.blackholeSink
import okio.buffer
import okio.source
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

internal object AppUpdates {
    private const val RELEASES_URL = "https://github.com/kkgosu/reasoned/releases"
    private const val MAX_APK_BYTES = 64L * 1024 * 1024

    data class Release(val version: String, val url: String, val size: Long, val sha256: String)

    fun parseRelease(release: JSONObject): Release {
        check(!release.getBoolean("draft") && !release.getBoolean("prerelease"))
        val version = release.getString("tag_name").also { parts(it) }
        val asset = release.getJSONArray("assets").objects()
            .single { it.getString("name") == "app-release.apk" }
        val url = asset.getString("browser_download_url")
        check(url == "$RELEASES_URL/download/$version/app-release.apk")
        val size = asset.getLong("size")
        check(size in 1..MAX_APK_BYTES)
        val digest = asset.getString("digest")
        check(digest.matches(Regex("sha256:[0-9a-f]{64}")))
        return Release(version, url, size, digest.removePrefix("sha256:"))
    }
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    // Release tags must be vMAJOR.MINOR.PATCH (the v and trailing components are optional).
    private fun parts(version: String): List<Long> {
        require(version.matches(Regex("v?\\d+(?:\\.\\d+){0,2}"))) { "Unsupported release version" }
        val values = version.removePrefix("v").split('.').map { it.toLong() }
        return List(3) { values.getOrElse(it) { 0L } }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        return a.indices.firstOrNull { a[it] != b[it] }?.let { a[it] > b[it] } ?: false
    }

    suspend fun latestRelease(http: OkHttpClient = client): Release? = coroutineScope {
        val call = http.newCall(
            Request.Builder()
                .url("https://api.github.com/repos/kkgosu/reasoned/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Reasoned-Android").build()
        )
        val reader = async(Dispatchers.IO) {
            call.execute().use { response ->
                if (response.code == 404) return@use null
                check(response.isSuccessful) { "GitHub HTTP ${response.code}" }
                val source = response.body.source()
                check(!source.request(262_145)) { "Release response too large" }
                parseRelease(JSONObject(source.readUtf8()))
            }
        }
        try {
            reader.await()
        } finally {
            call.cancel()
        }
    }

    suspend fun download(
        release: Release,
        directory: File,
        http: OkHttpClient = client,
        onProgress: suspend (Int) -> Unit = {},
    ): File = coroutineScope {
        // GitHub redirects release assets to its HTTPS CDN; never follow a downgrade to HTTP.
        val call = http.newBuilder().followRedirects(true).followSslRedirects(false)
            .callTimeout(2, TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url(release.url).header("User-Agent", "Reasoned-Android").build())
        val reader = async(Dispatchers.IO) {
            check(directory.mkdirs() || directory.isDirectory)
            val destination = File(directory, "${release.sha256}.apk")
            if (destination.isFile && destination.length() == release.size &&
                sha256(destination) == release.sha256
            ) return@async destination
            check(!destination.exists() || destination.delete())
            val partial = File.createTempFile("update-", ".part", directory)
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "GitHub HTTP ${response.code}" }
                    val body = response.body
                    check(body.contentLength() == -1L || body.contentLength() == release.size)
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            var lastPercent = -1
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                total += count
                                check(total <= release.size) { "APK too large" }
                                output.write(buffer, 0, count)
                                val percent = (total * 100 / release.size).toInt().coerceAtMost(99)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                            check(total == release.size) { "Incomplete APK" }
                        }
                    }
                }
                check(sha256(partial) == release.sha256) { "APK checksum mismatch" }
                currentCoroutineContext().ensureActive()
                check(partial.renameTo(destination))
                onProgress(100)
                destination
            } finally {
                partial.delete()
            }
        }
        try {
            reader.await()
        } finally {
            call.cancel()
        }
    }

    private fun sha256(file: File): String {
        val hash = HashingSource.sha256(file.source())
        hash.buffer().use { it.readAll(blackholeSink()) }
        return hash.hash.hex()
    }
}
