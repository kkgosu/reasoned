package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.AppUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdatesTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val apk = ByteArray(18_001) { (it % 127).toByte() }
    private val apkUrl = "https://github.com/kkgosu/reasoned/releases/download/v2.0.0/app-release.apk"

    private fun releaseJson(): JSONObject = JSONObject(
        """{
        "tag_name":"v2.0.0", "draft":false, "prerelease":false,
        "assets":[{"name":"app-release.apk", "browser_download_url":"$apkUrl",
            "size":${apk.size}, "digest":"sha256:${apk.toByteString().sha256().hex()}"}]
    }"""
    )

    private fun http(bytes: ByteArray, code: Int = 200, requested: () -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            requested()
            assertEquals("GET", chain.request().method)
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(Buffer().write(bytes).asResponseBody()).build()
        }.build()

    @Test
    fun releaseTagsCompareNumericallyAndRejectUnsupportedFormats() {
        assertTrue(AppUpdates.isNewer("v1.10.0", "1.9"))
        assertTrue(AppUpdates.isNewer("v2.0.0", "1.99.99"))
        assertTrue(AppUpdates.isNewer("1.0.1", "1.0"))
        assertFalse(AppUpdates.isNewer("v1.0.0", "1.0"))
        assertFalse(AppUpdates.isNewer("v1.0", "1.0.1"))
        for (invalid in listOf(
            "",
            "latest",
            "v1.1.0-beta",
            "1.0.0.1",
            "-1",
            "99999999999999999999"
        )) {
            assertTrue(invalid, runCatching { AppUpdates.isNewer(invalid, "1.0") }.isFailure)
        }
    }

    @Test
    fun onlyStableOwnedApksWithBoundedSizesAndChecksumsAreAccepted() {
        val valid = AppUpdates.parseRelease(releaseJson())
        assertEquals(apkUrl, valid.url)
        assertEquals(apk.size.toLong(), valid.size)
        for (field in listOf("draft", "prerelease")) {
            assertTrue(runCatching { AppUpdates.parseRelease(releaseJson().put(field, true)) }.isFailure)
        }
        val invalidAssets = listOf(
            "browser_download_url" to "http://github.com/kkgosu/reasoned/releases/download/v2.0.0/app-release.apk",
            "browser_download_url" to apkUrl.replace("kkgosu", "someone-else"),
            "browser_download_url" to "$apkUrl?redirect=https://evil.example",
            "browser_download_url" to apkUrl.replace("v2.0.0", "v1.0.0"),
            "name" to "other.apk", "size" to 0, "size" to 64L * 1024 * 1024 + 1,
            "digest" to JSONObject.NULL, "digest" to "sha256:abc", "digest" to "md5:abc",
        )
        for ((field, value) in invalidAssets) {
            val json = releaseJson()
            json.getJSONArray("assets").getJSONObject(0).put(field, value)
            assertTrue("$field=$value", runCatching { AppUpdates.parseRelease(json) }.isFailure)
        }
        val duplicate = releaseJson()
        duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0))
        assertTrue(runCatching { AppUpdates.parseRelease(duplicate) }.isFailure)
    }

    @Test
    fun releaseDiscoveryUsesBoundedUnauthenticatedGithubMetadata() = runBlocking {
        assertEquals("v2.0.0", AppUpdates.latestRelease(http(releaseJson().toString().toByteArray()))?.version)
        assertNull(AppUpdates.latestRelease(http(ByteArray(0), 404)))
        assertTrue(runCatching { AppUpdates.latestRelease(http(ByteArray(262_145))) }.isFailure)
        assertTrue(runCatching { AppUpdates.latestRelease(http(ByteArray(0), 503)) }.isFailure)
    }

    @Test
    fun verifiedDownloadIsReusedAndCorruptCacheIsReplaced() = runBlocking {
        val release = AppUpdates.parseRelease(releaseJson())
        val directory = temporary.newFolder("updates")
        var requests = 0
        val client = http(apk) { requests++ }
        val progress = mutableListOf<Int>()
        val file = AppUpdates.download(release, directory, client) { progress += it }
        assertArrayEquals(apk, file.readBytes())
        assertEquals(100, progress.last())
        assertEquals(listOf(file), directory.listFiles()!!.toList())
        assertEquals(file, AppUpdates.download(release, directory, client))
        assertEquals(1, requests)
        file.writeBytes(ByteArray(apk.size))
        assertEquals(file, AppUpdates.download(release, directory, client))
        assertArrayEquals(apk, file.readBytes())
        assertEquals(2, requests)
    }

    @Test
    fun shortOversizedTamperedAndFailedDownloadsLeaveNoInstallableFile() = runBlocking {
        val release = AppUpdates.parseRelease(releaseJson())
        val directory = temporary.newFolder("updates")
        for (bytes in listOf(apk.copyOf(apk.size - 1), apk + byteArrayOf(1), ByteArray(apk.size))) {
            assertTrue(runCatching { AppUpdates.download(release, directory, http(bytes)) }.isFailure)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
        assertTrue(runCatching { AppUpdates.download(release, directory, http(apk, 503)) }.isFailure)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun cancellationCleansUpAndAnExplicitRetryCanFinish() = runBlocking {
        val release = AppUpdates.parseRelease(releaseJson())
        val directory = temporary.newFolder("updates")
        val error = runCatching {
            AppUpdates.download(release, directory, http(apk)) { throw CancellationException("Cancelled fixture") }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(directory.listFiles()!!.isEmpty())
        assertArrayEquals(apk, AppUpdates.download(release, directory, http(apk)).readBytes())
    }
}
