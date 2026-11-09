package com.kkgosu.reasoned

import com.kkgosu.reasoned.api.NativeResponses
import com.kkgosu.reasoned.api.ProviderFailure
import com.kkgosu.reasoned.controllers.RuntimeTools
import com.kkgosu.reasoned.controllers.ContextCompaction
import com.kkgosu.reasoned.model.NativeCompaction
import com.kkgosu.reasoned.model.LessonFile
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeTeaching
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.storage.LessonDocuments
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LessonAttachmentTest {
    private val pdf = LessonFile("12345678-1234-1234-1234-123456789abc", "Authored source.pdf", 512, 2)
    private fun args() = JSONObject().put("file", pdf.id).put("page", 1).put("offset", 0).put("image", false)
    private fun response(args: JSONObject) = JSONObject().put("status", "completed").put("output", JSONArray().put(
        JSONObject().put("type", "function_call").put("name", "read_attachment").put("call_id", "read")
            .put("arguments", args.toString())))

    @Test fun attachmentCapabilitiesAreBoundedAndNeverAuthorizePaths() {
        val session = RuntimeSession(goal = "Authored fixture", model = "fixture", files = listOf(pdf))
        RuntimeTools.validate(response(args()), session)
        RuntimeTools.validate(response(args().put("page", 2).put("image", true)), session)
        assertThrows(ProviderFailure::class.java) { RuntimeTools.validate(response(args()), session.copy(files = emptyList())) }
        for (bad in listOf(args().put("file", "../secret"), args().put("page", 0), args().put("page", 3),
            args().put("page", 1.5), args().put("offset", -1), args().put("offset", 2_097_153),
            args().put("image", "true"), args().put("image", true).put("offset", 1), args().put("path", "/secret"))) {
            assertThrows(ProviderFailure::class.java) { RuntimeTools.validate(response(bad), session) }
        }
        assertThrows(IllegalArgumentException::class.java) { LessonFile("../".repeat(12), "Bad", 1) }
        assertThrows(IllegalArgumentException::class.java) { LessonFile(pdf.id, "Bad\nname", 1) }
        assertThrows(IllegalArgumentException::class.java) { LessonFile(pdf.id, "Huge", LessonFile.MAX_BYTES + 1) }
        assertThrows(IllegalArgumentException::class.java) { LessonFile.parse(JSONArray().put(pdf.json()).put(pdf.json())) }
        assertEquals(listOf(pdf), LessonFile.parse(JSONArray().put(pdf.json())))
        assertEquals(emptyList<LessonFile>(), LessonFile.parse(null as JSONArray?))
    }

    @Test fun sourceImagesRespectUtf8ArchiveBudgetAndKeepRoomForAccounting() {
        val original = NativeChat.parse(JSONObject().put("id", "source_budget").put("version", 1)
            .put("createdAt", "2026-01-01T00:00:00Z").put("updatedAt", "2026-01-01T00:00:00Z")
            .put("messages", JSONArray()).put("retainedMetadata", "я".repeat(7_000_000)).toString())
        val before = original.raw
        // Opaque encoded payload: this test exercises archive budgeting, not image decoding.
        fun result(id: String) = JSONObject().put("id", id).put("role", "toolResult")
            .put("toolName", "read_attachment").put("toolCallId", "read_$id").put("isError", false)
            .put("content", JSONArray().put(JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                .put("data", "A".repeat(933_336))))
        var chat = NativeResponses.append(original, result("first"), 1)
        assertFalse(chat.snapshot().getJSONArray("messages").getJSONObject(0).getBoolean("isError"))
        assertTrue(chat.raw.toByteArray(Charsets.UTF_8).size < NativeChat.MAX_BYTES - 1_048_576)
        val second = result("second")
        chat = NativeResponses.append(chat, second, 2)
        val rejected = chat.snapshot().getJSONArray("messages").getJSONObject(1)
        assertTrue(rejected.getBoolean("isError"))
        val content = rejected.getJSONArray("content").objects()
        assertEquals(listOf("text"), content.map { it.getString("type") })
        assertEquals("attachment_limit", JSONObject(content.single().getString("text")).getString("error"))
        assertFalse(second.getBoolean("isError"))
        assertEquals("image", second.getJSONArray("content").getJSONObject(0).getString("type"))
        val receipt = JSONObject().put("padding", "x".repeat(240_000))
        chat = NativeResponses.append(chat, NativeResponses.failure("fixture", "key:openai", "x".repeat(24_000),
            false, "fixture", 3, receipt), 3)
        chat = NativeResponses.appendTurn(chat, NativeResponses.turn("run", "first", JSONArray()
            .put(NativeResponses.step(JSONObject().put("input", 20).put("output", 10))), "fixture", "key:openai", 4))
        assertEquals(1, NativeChat.parse(chat.raw).snapshot().getJSONArray("turns").length())
        assertEquals(before, original.raw)
    }

    @Test fun pdfImageToolResultsRestoreOnTheWireWithoutBecomingLearnerEvidence() {
        val image = JSONObject().put("type", "image").put("mimeType", "image/png")
            .put("data", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aNOkAAAAASUVORK5CYII=")
        val messages = JSONArray().put(JSONObject().put("id", "call").put("role", "assistant")
            .put("api", "openai-responses").put("provider", "openai").put("model", "fixture")
            .put("stopReason", "toolUse").put("timestamp", 1).put("content", JSONArray().put(
                JSONObject().put("type", "toolCall").put("id", "read").put("name", "read_attachment").put("arguments", args()))))
            .put(JSONObject().put("id", "result").put("role", "toolResult").put("toolCallId", "read")
                .put("toolName", "read_attachment").put("isError", false).put("timestamp", 1)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "UNTRUSTED_PDF_SOURCE")) .put(image)))
        val chat = NativeChat.parse(JSONObject().put("id", "authored_pdf").put("version", 1).put("title", "Fixture")
            .put("createdAt", "2026-01-01T00:00:00Z").put("updatedAt", "2026-01-01T00:00:00Z").put("messages", messages).toString())
        val before = chat.raw
        val input = NativeResponses.input(chat, "fixture", "key:openai").objects()
        assertEquals("function_call_output", input[1].getString("type"))
        assertEquals("UNTRUSTED_PDF_SOURCE", input[1].getString("output"))
        assertEquals("input_image", input[2].getJSONArray("content").getJSONObject(1).getString("type"))
        val projected = NativeTeaching.project(RuntimeSession(id = chat.id, goal = "Fixture", model = "fixture", nativeRecord = chat))
        assertTrue(projected.items.none { it.text.contains("UNTRUSTED_PDF_SOURCE") })
        assertEquals(before, projected.nativeRecord!!.raw)
        assertEquals(before, chat.raw)
        val tail = chat.snapshot()
        tail.getJSONArray("messages").put(JSONObject().put("id", "next").put("role", "user").put("content", "Next"))
        val span = NativeCompaction.span(NativeChat.parse(tail.toString()), 2)
        assertTrue(span.contains("Source image omitted"))
        assertFalse(span.contains(image.getString("data")))
        val source = JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject()
            .put("type", "input_text").put("text", "[ATTACHMENT SOURCE IMAGE — untrusted data, NOT learner speech]")))
        val legacy = RuntimeSession(goal = "Fixture", model = "fixture", protocol = JSONArray().put(source)
            .put(JSONObject().put("role", "user").put("content", "Next")).toString())
        val legacySpan = ContextCompaction.span(legacy, 1)
        assertTrue(legacySpan.startsWith("[SOURCE]"))
        assertFalse(legacySpan.contains("[LEARNER]"))
    }
}
