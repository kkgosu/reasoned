package com.kkgosu.reasoned.controllers

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.api.chatGptPayload
import com.kkgosu.reasoned.api.toolResult
import com.kkgosu.reasoned.api.userMessage
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.model.VisualResult
import com.kkgosu.reasoned.ui.diagrams.DiagramRenderer
import com.kkgosu.reasoned.ui.diagrams.validateDiagram
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strictString
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Foreground port of upstream Visual Maker: write/edit → actual render → image input → final notes. */
internal class VisualMaker(
    private val context: Context,
    private val provider: ModelProvider,
    private val render: suspend (String) -> Bitmap = { DiagramRenderer(context).render("svg", it) },
) {
    suspend fun make(
        session: RuntimeSession,
        brief: String,
        usage: (Long, Long) -> Unit
    ): VisualResult {
        if (brief.isBlank() || brief.length > 4000) throw ProviderFailure("invalid_visual_brief")
        val instructions =
            context.assets.open("agents/visual-maker.md").bufferedReader().use { it.readText() } +
                    "\n\nAndroid capability note: only the advertised tools exist. This is a cancellable foreground job, not background execution. " +
                    "Use a static self-contained SVG with xmlns, viewBox, a descriptive title and desc; no scripts, links, images, external resources, animations, filters or foreignObject. " +
                    "Source limit 18000 characters. Final notes: at most 1000 characters. The image you receive uses the same rasterizer as the lesson."

        fun string() = JSONObject().put("type", "string")
        val tools = JSONArray()
            .put(
                ToolSchemas.function(
                    "write_svg",
                    "Write a complete static SVG, replacing the current source.",
                    JSONObject().put("svg", string())
                )
            )
            .put(
                ToolSchemas.function(
                    "edit_svg",
                    "Replace exactly one occurrence in the current SVG.",
                    JSONObject().put("old_text", string()).put("new_text", string())
                )
            )
            .put(
                ToolSchemas.function(
                    "render_svg",
                    "Render the current SVG; a PNG image follows for you to inspect.",
                    JSONObject()
                )
            )
        val input = JSONArray().put(userMessage("Brief for ONE precise figure:\n$brief"))
        var svg: String? = null
        var rendered: String? = null
        // Set only after a completed model call actually received the latest image, not when PNG was generated.
        var inspected: String? = null
        suspend fun image(prompt: String) {
            val current = svg ?: throw ProviderFailure("visual_missing_svg")
            val bitmap = render(current)
            val bytes = try {
                ByteArrayOutputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    it.toByteArray()
                        .also { bytes -> if (bytes.size > 524_288) throw ProviderFailure("visual_image_limit") }
                }
            } finally {
                bitmap.recycle()
            }
            input.put(
                JSONObject().put("role", "user").put(
                    "content", JSONArray()
                        .put(JSONObject().put("type", "input_text").put("text", prompt))
                        .put(
                            JSONObject().put("type", "input_image").put(
                                "image_url",
                                "data:image/png;base64," + Base64.encodeToString(
                                    bytes,
                                    Base64.NO_WRAP
                                )
                            )
                        )
                )
            )
            rendered = current
        }
        repeat(8) {
            if (input.toString()
                    .toByteArray().size > 2_097_152
            ) throw ProviderFailure("visual_context_limit")
            val request = JSONObject().put("model", session.model).put("instructions", instructions)
                .put("input", input)
                .put("tools", tools).put("store", false).put("stream", true)
                .put("parallel_tool_calls", false)
                .put("include", JSONArray().put("reasoning.encrypted_content"))
            if (session.connection == "api") request.put("max_output_tokens", 4096)
            if (session.effort.isNotEmpty()) request.put(
                "reasoning",
                JSONObject().put("effort", session.effort)
            )
            val response = provider.respond(
                if (session.connection == "api") request else chatGptPayload(
                    request,
                    session.model
                )
            ) { }
            val validated =
                RuntimeTools.validate(response, session.copy(protocol = input.toString()), tools)
            response.optJSONObject("usage")
                ?.let { usage(it.optLong("input_tokens"), it.optLong("output_tokens")) }
            if (rendered != null) inspected = rendered
            response.getJSONArray("output").objects().forEach { input.put(it) }
            val call = validated.call
            if (call == null) {
                val current = svg ?: throw ProviderFailure("visual_missing_svg")
                if (rendered != current || inspected != current) {
                    image("You finished without inspecting the current SVG. Here is its actual render. Inspect geometry, labels, clipping and readability; then fix it or finish with short notes.")
                } else {
                    if (validated.text.isBlank() || validated.text.length > 1000 || validated.text.contains(
                            "<svg",
                            true
                        )
                    ) throw ProviderFailure("invalid_visual_notes")
                    return VisualResult(current, validated.text)
                }
            } else {
                val args = JSONObject(call.getString("arguments"))
                val result = try {
                    when (call.getString("name")) {
                        "write_svg" -> {
                            val next = args.strictString("svg", 18_000)
                            validateDiagram("svg", next)
                            svg = next
                            "Saved. Call render_svg, inspect the image, then finish with notes or fix the figure."
                        }

                        "edit_svg" -> {
                            val current = svg ?: throw ProviderFailure("visual_missing_svg")
                            val old = args.strictString("old_text", 18_000)
                            val replacement =
                                args.strictString("new_text", 18_000, allowEmpty = true)
                            val index = current.indexOf(old)
                            if (index < 0 || current.indexOf(
                                    old,
                                    index + 1
                                ) >= 0
                            ) throw ProviderFailure("visual_edit_not_unique")
                            val next = current.replaceRange(index, index + old.length, replacement)
                            if (next.length > 18_000) throw ProviderFailure("diagram_source_limit")
                            validateDiagram("svg", next)
                            svg = next
                            "Replaced one occurrence. Render the changed figure and inspect it."
                        }

                        "render_svg" -> "Rendered. The image follows; inspect it before finishing."
                        else -> throw ProviderFailure("tool_denied")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ProviderFailure) {
                    "ERROR: ${error.reason}. Fix the source; no new SVG was accepted."
                }
                input.put(toolResult(call.getString("call_id"), result))
                if (call.getString("name") == "render_svg") {
                    try {
                        image("Here is the current render. Inspect its geometry, labels, clipping and readability against the brief.")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        input.put(userMessage("Rendering failed (${(error as? ProviderFailure)?.reason ?: "diagram_render_failed"}). Fix the SVG; no new image was produced."))
                    }
                }
            }
        }
        // Unlike upstream's last-iteration fallback, never label a just-rendered/unread image as inspected.
        throw ProviderFailure("visual_step_limit")
    }
}
