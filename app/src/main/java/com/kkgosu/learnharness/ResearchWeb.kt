package com.kkgosu.learnharness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal fun publicResearchUrl(raw: String): HttpUrl {
    val url = raw.toHttpUrlOrNull() ?: throw ProviderFailure("web_url_denied")
    if (raw.length > 2000 || !url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.host == "localhost" || url.host.endsWith(".local") || url.host.endsWith(".localhost") || (!url.host.contains(
            '.'
        ) && !url.host.contains(':'))
    )
        throw ProviderFailure("web_url_denied")
    // OkHttp bypasses Dns for IP literals: validate those here, not only in the socket DNS hook.
    if ((url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) && !publicResearchAddress(
            InetAddress.getByName(url.host)
        )
    )
        throw ProviderFailure("web_address_denied")
    return url.newBuilder().fragment(null).build()
}

/** DNS result is checked AND used by OkHttp for the socket, including every redirect. No check-then-resolve gap. */
internal fun publicResearchAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false
    val b = address.address.map { it.toInt() and 255 }
    if (b.size == 16) return (b[0] and 224) == 32 && !(b[0] == 32 && b[1] == 1 && b[2] == 13 && b[3] == 184) &&
            !(b[0] == 32 && b[1] == 1 && b[2] < 2) && !(b[0] == 32 && b[1] == 2) && !(b[0] == 63 && b[1] == 255)
    // Global unicast only; deny special-use 2001::/23, 6to4 and documentation ranges.
    return b.size == 4 && b[0] !in setOf(0, 10, 127) && b[0] < 224 &&
            !(b[0] == 100 && b[1] in 64..127) && !(b[0] == 169 && b[1] == 254) &&
            !(b[0] == 172 && b[1] in 16..31) && !(b[0] == 192 && (b[1] in setOf(
        0,
        168
    ) || b[1] == 88 && b[2] == 99)) &&
            !(b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100)) && !(b[0] == 203 && b[1] == 0 && b[2] == 113)
}

internal data class ResearchSource(
    val url: String,
    val title: String,
    val text: String,
    val retrieved: Long
) {
    fun json() = JSONObject().put("url", url).put("title", title).put("text", text)
        .put("retrieved", retrieved).put("sha256", fingerprint(text))

    companion object {
        fun parse(json: JSONObject) = ResearchSource(
            json.getString("url"),
            json.getString("title"),
            json.getString("text"),
            json.getLong("retrieved")
        )
    }
}

/** Public HTTPS only. No browser cookies, proxies, credentials, reader-service fallback or remote script execution. */
internal class ResearchWeb(private val credentials: CredentialStore) {
    private val client = OkHttpClient.Builder().dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !publicResearchAddress(it) }) throw UnknownHostException(
                "web_address_denied"
            )
            return addresses
        }
    }).proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()

    private data class Page(
        val url: HttpUrl,
        val type: String,
        val body: String,
        val redirect: String?
    )

    private suspend fun read(url: HttpUrl, key: String? = null): Page = coroutineScope {
        val request = Request.Builder().url(url).header(
            "Accept",
            if (key == null) "text/html,text/plain,text/markdown" else "application/json"
        )
            .header("User-Agent", "Reasoned/1.0 (public-source research)")
        // Only the fixed Brave endpoint receives its key. Fetch and redirect requests never inherit it.
        if (key != null) {
            if (url.host != "api.search.brave.com" || url.encodedPath != "/res/v1/web/search") throw ProviderFailure(
                "web_url_denied"
            )
            request.header("X-Subscription-Token", key)
        }
        val call = client.newCall(request.build())
        val work = async(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (response.code in 300..399) return@use Page(
                        url,
                        "",
                        "",
                        response.header("Location") ?: throw ProviderFailure("web_redirect")
                    )
                    if (!response.isSuccessful) throw ProviderFailure("web_http_${response.code}")
                    val type =
                        response.body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
                    val allowed = if (key == null) setOf(
                        "text/html",
                        "application/xhtml+xml",
                        "text/plain",
                        "text/markdown"
                    ) else setOf("application/json")
                    if (type !in allowed)
                        throw ProviderFailure(if (type == "application/pdf") "web_pdf_unavailable" else "web_content_type")
                    if (response.body.source()
                            .request(1_048_577)
                    ) throw ProviderFailure("web_response_limit")
                    Page(url, type, response.body.string(), null)
                }
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive(); throw error
            }
        }
        try {
            work.await()
        } finally {
            call.cancel()
        }
    }

    suspend fun search(query: String): JSONArray {
        if (query.isBlank() || query.length > 500) throw ProviderFailure("web_query_limit")
        val key = credentials.read() ?: throw ProviderFailure("search_unconfigured")
        val url = publicResearchUrl("https://api.search.brave.com/res/v1/web/search").newBuilder()
            .addQueryParameter("q", query).addQueryParameter("count", "5").build()
        val page = read(url, key)
        if (page.redirect != null) throw ProviderFailure("web_redirect")
        val hits =
            JSONObject(page.body).optJSONObject("web")?.optJSONArray("results") ?: JSONArray()
        val result = JSONArray()
        hits.objects().take(5).forEach { hit ->
            val raw = hit.optString("url")
            val safe = try {
                publicResearchUrl(raw).toString()
            } catch (_: ProviderFailure) {
                null
            }
            if (safe != null) result.put(
                JSONObject().put("url", safe).put("title", hit.optString("title").take(300))
                    .put("snippet", Jsoup.parse(hit.optString("description")).text().take(1000))
            )
        }
        return result
    }

    suspend fun fetch(raw: String): ResearchSource {
        var url = publicResearchUrl(raw)
        repeat(4) {
            val page = read(url)
            if (page.redirect != null) {
                url = publicResearchUrl(
                    url.resolve(page.redirect)?.toString() ?: throw ProviderFailure("web_redirect")
                )
            } else {
                val html = page.type in setOf("text/html", "application/xhtml+xml")
                val document = if (html) Jsoup.parse(page.body) else null
                val title = document?.title()?.take(300).orEmpty().ifBlank { url.host }
                document?.select("script,style,noscript,nav,header,footer,form,iframe,svg")
                    ?.remove()
                val text = (document?.selectFirst("article,main")?.wholeText() ?: document?.body()
                    ?.wholeText() ?: page.body).trim()
                if (text.length < 100) throw ProviderFailure("web_no_readable_text")
                return ResearchSource(
                    url.toString(),
                    title,
                    text.take(8000) + if (text.length > 8000) "\n[Truncated]" else "",
                    System.currentTimeMillis()
                )
            }
        }
        throw ProviderFailure("web_redirect_limit")
    }
}
