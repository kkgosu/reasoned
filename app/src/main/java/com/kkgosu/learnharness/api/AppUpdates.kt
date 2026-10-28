package com.kkgosu.learnharness.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object AppUpdates {
    const val RELEASES_URL = "https://github.com/kkgosu/learnharness/releases"
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

    suspend fun latestVersion(): String? = coroutineScope {
        val call = client.newCall(
            Request.Builder()
                .url("https://api.github.com/repos/kkgosu/learnharness/releases/latest")
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
                val release = JSONObject(source.readUtf8())
                check(!release.getBoolean("draft") && !release.getBoolean("prerelease"))
                release.getString("tag_name").also { parts(it) }
            }
        }
        try {
            reader.await()
        } finally {
            call.cancel()
        }
    }
}
