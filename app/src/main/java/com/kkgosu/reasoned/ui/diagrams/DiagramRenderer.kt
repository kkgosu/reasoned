package com.kkgosu.reasoned.ui.diagrams

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Xml
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.kkgosu.reasoned.api.ProviderFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Static diagrams only. Authored SVG never enters a live DOM; no model-authored JS or Java bridge. */
internal class DiagramRenderer(private val context: Context) {
    // ponytail: one WebView across inline/fullscreen/maker jobs; consider a bounded pool only if queue latency warrants the memory.
    private companion object {
        val mutex = Mutex()
        const val PAGE_URL = "https://diagrams.reasoned.invalid/renderer.html"
        const val ENGINE_URL = "https://diagrams.reasoned.invalid/mermaid.js"
    }

    @SuppressLint("SetJavaScriptEnabled") // Only the pinned offline Mermaid engine and app-owned runner execute.
    suspend fun render(kind: String, source: String, targetPixels: Int? = null): Bitmap {
        val id = UUID.randomUUID().toString().take(8)
        val began = SystemClock.elapsedRealtime()
        var stage = "queued"
        fun trace(event: String) = Log.d(
            "ReasonedDiagrams",
            "render=$id elapsed_ms=${SystemClock.elapsedRealtime() - began} $event"
        )
        trace(
            "begin kind=${
                if (kind in setOf(
                        "mermaid",
                        "svg"
                    )
                ) kind else "unknown"
            } chars=${source.length} target_px=${targetPixels ?: 0}"
        )
        val limit = targetPixels ?: 1024
        try {
            if (limit !in setOf(1024, 2048)) throw ProviderFailure("diagram_render_limit")
            validateDiagram(kind, source)
        } catch (error: ProviderFailure) {
            trace("validation_failed code=${error.reason}"); throw error
        }
        return withContext(Dispatchers.Main.immediate) {
            mutex.withLock {
                stage = "page_load"
                trace("webview_create version=${WebView.getCurrentWebViewPackage()?.versionName ?: "unknown"}")
                val web = WebView(context)
                try {
                    web.settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false; allowContentAccess = false
                        allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs =
                        false
                        blockNetworkLoads = true
                        domStorageEnabled = false; databaseEnabled = false; saveFormData = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        safeBrowsingEnabled = true
                    }
                    web.setDownloadListener { _, _, _, _, _ -> }
                    web.layout(0, 0, 1024, 1024)
                    withTimeoutOrNull(15_000) {
                        suspendCancellableCoroutine<Bitmap> { continuation ->
                            fun fail(code: String = "diagram_render_failed") {
                                if (continuation.isActive) {
                                    trace("failed stage=$stage code=$code")
                                    continuation.resumeWithException(ProviderFailure(code))
                                }
                            }

                            var started = false
                            val stages = setOf(
                                "engine",
                                "mermaid",
                                "svg_validate",
                                "image_load",
                                "canvas_draw",
                                "png_export"
                            )
                            web.webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                    // Never log message(), sourceId(), URLs, exception messages or stacks: these can contain lesson text.
                                    if (message?.message() == "diagram_policy_violation") trace("policy_violation stage=$stage")
                                    trace("console level=${message?.messageLevel()?.name} line=${message?.lineNumber()} stage=$stage")
                                    return true
                                }

                                override fun onPermissionRequest(request: PermissionRequest) =
                                    request.deny()

                                override fun onJsAlert(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    result: JsResult
                                ): Boolean {
                                    result.cancel(); return true
                                }

                                override fun onJsConfirm(
                                    view: WebView?,
                                    url: String?,
                                    message: String?,
                                    result: JsResult
                                ): Boolean {
                                    result.cancel(); return true
                                }

                                override fun onReceivedTitle(view: WebView, title: String?) {
                                    if (!continuation.isActive) return
                                    val reportedStage = title?.removePrefix("stage-")
                                    if (title?.startsWith("stage-") == true && reportedStage in stages) {
                                        stage = reportedStage!!; trace("stage=$stage")
                                    }
                                    when (title) {
                                        "failed-script" -> fail("diagram_script_error")
                                        "ready" -> if (!started) {
                                            started = true
                                            stage = "runner_ready"; trace("stage=$stage")
                                            val quoted = JSONObject.quote(source)
                                                .replace("\u2028", "\\u2028")
                                                .replace("\u2029", "\\u2029")
                                            view.evaluateJavascript(
                                                "renderDiagram(${
                                                    JSONObject.quote(
                                                        kind
                                                    )
                                                },$quoted,${targetPixels ?: 0})", null
                                            )
                                        }

                                        "failed" -> view.evaluateJavascript("JSON.stringify({stage:window.diagramStage,error:window.diagramErrorType})") { result ->
                                            if (!continuation.isActive) return@evaluateJavascript
                                            try {
                                                val state =
                                                    JSONObject(JSONTokener(result).nextValue() as String)
                                                stage =
                                                    state.optString("stage").takeIf { it in stages }
                                                        ?: "unknown"
                                                val type = state.optString("error").takeIf {
                                                    it in setOf(
                                                        "Error",
                                                        "SyntaxError",
                                                        "TypeError",
                                                        "SecurityError",
                                                        "ReferenceError",
                                                        "RangeError",
                                                        "InvalidStateError"
                                                    )
                                                } ?: "other"
                                                trace("javascript_failed stage=$stage type=$type")
                                            } catch (_: Exception) {
                                                trace("javascript_state_unavailable")
                                            }
                                            fail()
                                        }

                                        "complete" -> view.evaluateJavascript("window.diagramResult") { result ->
                                            if (!continuation.isActive) return@evaluateJavascript
                                            try {
                                                if (result.length > if (limit == 2048) 8_000_000 else 2_000_000) throw ProviderFailure(
                                                    "diagram_render_limit"
                                                )
                                                val data =
                                                    JSONTokener(result).nextValue() as? String
                                                        ?: throw ProviderFailure("diagram_render_failed")
                                                if (!data.startsWith("data:image/png;base64,")) throw ProviderFailure(
                                                    "diagram_render_failed"
                                                )
                                                val bytes = Base64.decode(
                                                    data.substringAfter(','),
                                                    Base64.DEFAULT
                                                )
                                                val options = BitmapFactory.Options()
                                                    .apply { inJustDecodeBounds = true }
                                                BitmapFactory.decodeByteArray(
                                                    bytes,
                                                    0,
                                                    bytes.size,
                                                    options
                                                )
                                                if (options.outWidth !in 1..limit || options.outHeight !in 1..limit) throw ProviderFailure(
                                                    "diagram_render_limit"
                                                )
                                                val bitmap = BitmapFactory.decodeByteArray(
                                                    bytes,
                                                    0,
                                                    bytes.size
                                                ) ?: throw ProviderFailure("diagram_render_failed")
                                                trace("complete width=${bitmap.width} height=${bitmap.height} png_bytes=${bytes.size}")
                                                continuation.resume(bitmap)
                                            } catch (error: Exception) {
                                                trace("png_decode_failed")
                                                fail(
                                                    (error as? ProviderFailure)?.reason
                                                        ?: "diagram_render_failed"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            val html =
                                context.assets.open("diagrams/renderer.html").bufferedReader()
                                    .use { it.readText() }
                                    .replace("{{nonce}}", UUID.randomUUID().toString())
                            web.webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ) = true

                                override fun onPageFinished(view: WebView, url: String?) {
                                    if (!continuation.isActive) return
                                    view.evaluateJavascript("JSON.stringify({engine:typeof mermaid!=='undefined',runner:typeof renderDiagram==='function'})") { result ->
                                        if (!continuation.isActive) return@evaluateJavascript
                                        try {
                                            val state =
                                                JSONObject(JSONTokener(result).nextValue() as String)
                                            trace(
                                                "page_finished engine=${state.optBoolean("engine")} runner=${
                                                    state.optBoolean(
                                                        "runner"
                                                    )
                                                }"
                                            )
                                        } catch (_: Exception) {
                                            trace("page_state_unavailable")
                                        }
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    error: WebResourceError
                                ) {
                                    val engine = request.url.toString() == ENGINE_URL
                                    trace("resource_error engine=$engine main=${request.isForMainFrame} code=${error.errorCode}")
                                    if (request.isForMainFrame || engine) fail(if (engine) "diagram_engine_load" else "diagram_page_load")
                                }

                                override fun onReceivedHttpError(
                                    view: WebView,
                                    request: WebResourceRequest,
                                    response: WebResourceResponse
                                ) {
                                    trace("resource_http_error status=${response.statusCode} main=${request.isForMainFrame}")
                                    if (request.isForMainFrame || request.url.toString() == ENGINE_URL)
                                        fail(if (request.isForMainFrame) "diagram_page_load" else "diagram_engine_load")
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): WebResourceResponse? {
                                    // The app-owned runner decodes this inert image locally; data: is not network access.
                                    if (request.method == "GET" && !request.isForMainFrame &&
                                        request.url.toString()
                                            .startsWith("data:image/svg+xml;charset=utf-8,")
                                    ) {
                                        trace("svg_image_decode_allowed")
                                        return null
                                    }
                                    if (request.method == "GET" && request.isForMainFrame && request.url.toString() == PAGE_URL) {
                                        trace("page_asset_served")
                                        return WebResourceResponse(
                                            "text/html",
                                            "UTF-8",
                                            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8))
                                        )
                                    }
                                    if (request.method == "GET" && !request.isForMainFrame && request.url.toString() == ENGINE_URL) {
                                        trace("engine_asset_served")
                                        return WebResourceResponse(
                                            "application/javascript",
                                            "UTF-8",
                                            context.assets.open("diagrams/mermaid.min.js")
                                        )
                                    }
                                    trace("resource_blocked main=${request.isForMainFrame}")
                                    return WebResourceResponse(
                                        "text/plain",
                                        "UTF-8",
                                        403,
                                        "Blocked",
                                        emptyMap(),
                                        ByteArrayInputStream(byteArrayOf())
                                    )
                                }

                                override fun onRenderProcessGone(
                                    view: WebView,
                                    detail: android.webkit.RenderProcessGoneDetail
                                ): Boolean {
                                    trace("renderer_gone crashed=${detail.didCrash()}"); fail("diagram_renderer_gone"); return true
                                }
                            }
                            web.loadUrl(PAGE_URL)
                        }
                    }
                        ?: run { trace("timeout stage=$stage"); throw ProviderFailure("diagram_render_timeout") }
                } catch (cancelled: CancellationException) {
                    trace("cancelled stage=$stage"); throw cancelled
                } finally {
                    web.stopLoading(); web.destroy()
                    trace("webview_destroy stage=$stage")
                }
            }
        }
    }
}

/** Reject active/resource-bearing XML instead of silently changing what a spatial figure asserts. */
internal fun validateDiagram(kind: String, source: String) {
    if (kind !in setOf(
            "mermaid",
            "svg"
        ) || source.isBlank() || source.length > 24_000 || source.toByteArray().size > 65_536
    )
        throw ProviderFailure("diagram_source_limit")
    if (kind == "mermaid") {
        if (source.contains("%%{") || source.trimStart()
                .startsWith("---")
        ) throw ProviderFailure("diagram_config_denied")
        return
    }
    if (Regex(
            "<!\\s*(DOCTYPE|ENTITY)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(source)
    ) throw ProviderFailure("svg_active_content")
    val allowed = setOf(
        "svg",
        "g",
        "defs",
        "title",
        "desc",
        "path",
        "rect",
        "line",
        "polyline",
        "polygon",
        "circle",
        "ellipse",
        "text",
        "tspan",
        "marker",
        "clipPath",
        "linearGradient",
        "radialGradient",
        "stop",
        "style"
    )

    fun checkStyle(value: String) {
        if (value.contains('\\') || value.contains('@') || Regex(
                "url\\s*\\(([^)]*)\\)",
                RegexOption.IGNORE_CASE
            ).findAll(value).any {
                !it.groupValues[1].trim().trim('\'', '"')
                    .matches(Regex("#[A-Za-z_][A-Za-z0-9_.:-]*"))
            }
        )
            throw ProviderFailure("svg_external_resource")
    }
    try {
        val parser = Xml.newPullParser().apply {
            setFeature(
                XmlPullParser.FEATURE_PROCESS_NAMESPACES,
                true
            ); setInput(StringReader(source))
        }
        var elements = 0
        var root = false
        var inStyle = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.DOCDECL, XmlPullParser.PROCESSING_INSTRUCTION -> throw ProviderFailure(
                    "svg_active_content"
                )

                XmlPullParser.START_TAG -> {
                    if (++elements > 1000 || parser.depth > 32 || parser.name !in allowed || parser.namespace !in setOf(
                            "",
                            "http://www.w3.org/2000/svg"
                        )
                    )
                        throw ProviderFailure("svg_element_denied")
                    if (parser.depth == 1) {
                        if (root || parser.name != "svg" || parser.namespace != "http://www.w3.org/2000/svg") throw ProviderFailure(
                            "svg_invalid"
                        )
                        root = true
                        val box = parser.getAttributeValue(null, "viewBox")?.trim()
                            ?.split(Regex("[\\s,]+"))?.map { it.toFloatOrNull() }
                        if (box == null || box.size != 4 || box.any {
                                it == null || !it.isFinite() || kotlin.math.abs(
                                    it
                                ) > 8192
                            } ||
                            box[2]!! <= 0 || box[3]!! <= 0 || box[2]!! / box[3]!! !in (1f / 16)..16f) throw ProviderFailure(
                            "svg_dimensions"
                        )
                    }
                    if (parser.name == "style") inStyle = true
                    for (index in 0 until parser.attributeCount) {
                        val name = parser.getAttributeName(index)
                        if (name.startsWith("on", true) || name.lowercase() in setOf(
                                "href",
                                "src",
                                "base"
                            )
                        ) throw ProviderFailure("svg_active_content")
                        val value = parser.getAttributeValue(index)
                        if (name == "style" || value.contains('\\') || Regex(
                                "url\\s*\\(",
                                RegexOption.IGNORE_CASE
                            ).containsMatchIn(value)
                        ) checkStyle(value)
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "style") inStyle = false
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inStyle) checkStyle(parser.text)
            }
            parser.nextToken()
        }
        if (!root) throw ProviderFailure("svg_invalid")
    } catch (error: ProviderFailure) {
        throw error
    } catch (_: Exception) {
        throw ProviderFailure("svg_invalid")
    }
}
