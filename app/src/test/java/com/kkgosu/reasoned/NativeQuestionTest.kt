package com.kkgosu.reasoned

import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeQuestion
import com.kkgosu.reasoned.utils.objects
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeQuestionTest {
    private val fixtures = sequenceOf(File("../fixtures/learnharness/v1"), File("fixtures/learnharness/v1")).first { it.isDirectory }
    private val vectors get() = JSONObject(File(fixtures, "questions.json").readText())
    private fun call(value: JSONObject) = NativeChat.Call(value.getString("id"), value.getString("name"), value.getJSONObject("arguments"))
    private fun question(test: JSONObject) = NativeQuestion.parse(call(test.getJSONObject("call")), test.getString("phase"))
    private fun normalized(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { normalized(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }
    private fun same(expected: Any?, actual: Any?) = assertEquals(normalized(expected), normalized(actual))
    private fun pending(test: JSONObject): NativeChat = NativeChat.parse(JSONObject()
        .put("id", "authored-question-test").put("opaque", JSONObject().put("signature", "OPAQUE_TEST_VALUE"))
        .put("messages", JSONArray().put(JSONObject().put("id", "authored-pose").put("role", "assistant")
            .put("stopReason", "toolUse").put("timestamp", 1)
            .put("content", JSONArray().put(JSONObject(test.getJSONObject("call").toString()).put("type", "toolCall")))))
        .toString())

    @Test
    fun all16QuestionsAnd29AnswersMatchHashPinnedNativeFunctions() {
        val vectors = vectors
        val cases = vectors.getJSONArray("cases").objects()
        assertEquals(16, cases.size)
        assertEquals(29, cases.sumOf { it.getJSONArray("answers").length() })
        for (test in cases) {
            val before = test.toString()
            val q = question(test)
            val expected = test.getJSONObject("expect")
            same(expected.getJSONObject("client"), q.clientQuestion())
            assertEquals(test.getString("name"), expected.getBoolean("needsReveal"), q.needsReveal)
            same(expected.getJSONArray("correctIndices"), JSONArray(q.question.values.indices.filter { q.question.values[it] in q.question.correctValues }.map { it + 1 }))
            for (sample in test.getJSONArray("answers").objects()) {
                val answer = q.answer(sample.getJSONObject("request"), vectors.getLong("now"))
                same(sample.getJSONObject("answer"), answer)
                same(sample.getJSONObject("compact"), NativeChat.compactAnswer(answer))
            }
            assertEquals(before, test.toString())
        }
        assertEquals(listOf(0, 30, 50, 70, 90, 100), NativeQuestion.CONFIDENCE_STOPS)
        assertEquals(50, NativeQuestion.DEFAULT_CONFIDENCE)
    }

    @Test
    fun invalidAndOverLimitCallsAreRefusedNotRepairedIntoLearnerEvidence() {
        val invalid = vectors.getJSONArray("invalid").objects()
        assertEquals(8, invalid.size)
        for (test in invalid) assertThrows(Exception::class.java) {
            NativeQuestion.parse(NativeChat.Call("invalid-test", test.getString("tool"), test.getJSONObject("input")), "teach")
        }
        val large = JSONObject(vectors.getJSONArray("cases").getJSONObject(0).getJSONObject("call").toString())
        val args = large.getJSONObject("arguments")
        args.put("options", JSONArray((0..32).map { JSONObject().put("label", "Option $it").put("value", "$it") }))
        assertThrows(IllegalArgumentException::class.java) { NativeQuestion.parse(call(large), "probe") }
        assertThrows(IllegalArgumentException::class.java) { NativeQuestion.parse(NativeChat.Call("bad", "problem", JSONObject()), "practice") }
    }

    @Test
    fun nativeSubmitAppendsExactlyOneFullAnswerAndLearnerOnlyModelResultWithoutMutatingTheArchive() {
        val vectors = vectors
        for (test in vectors.getJSONArray("cases").objects()) {
            val source = pending(test)
            val original = source.raw
            val sample = test.getJSONArray("answers").getJSONObject(0)
            val id = test.getJSONObject("call").getString("id")
            val next = source.answerPending(id, sample.getJSONObject("request"), vectors.getLong("now"))
            val messages = next.snapshot().getJSONArray("messages")
            assertEquals(2, messages.length())
            same(source.snapshot().getJSONArray("messages").getJSONObject(0), messages.getJSONObject(0))
            val result = messages.getJSONObject(1)
            same(sample.getJSONObject("answer"), result.getJSONObject("details"))
            same(sample.getJSONObject("compact"), JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text")))
            assertEquals(id, result.getString("toolCallId"))
            assertEquals(test.getJSONObject("call").getString("name"), result.getString("toolName"))
            assertEquals("toolResult", result.getString("role"))
            assertFalse(result.getBoolean("isError"))
            assertEquals(vectors.getLong("now"), result.getLong("timestamp"))
            assertTrue(result.getString("id").startsWith("msg_"))
            assertNull(next.state().pending)
            assertTrue(next.state().continuation)
            assertEquals(1, next.snapshot().getInt("version"))
            assertEquals("2026-10-01T00:00:02.000Z", next.snapshot().getString("updatedAt"))
            same(source.snapshot().getJSONObject("opaque"), next.snapshot().getJSONObject("opaque"))
            assertEquals(original, source.raw)
            assertFalse(source.snapshot().has("version"))
            assertThrows(IllegalArgumentException::class.java) { next.answerPending(id, sample.getJSONObject("request")) }
            assertThrows(IllegalArgumentException::class.java) { source.answerPending("wrong-call", sample.getJSONObject("request")) }
        }
    }

    @Test
    fun dismissalIsANativePauseNotAWrongAnswerAndCannotBeAppliedTwice() {
        val test = vectors.getJSONArray("cases").getJSONObject(0)
        val source = pending(test)
        val id = test.getJSONObject("call").getString("id")
        val dismissed = source.dismissQuestion(id, vectors.getLong("now"))
        val result = dismissed.snapshot().getJSONArray("messages").getJSONObject(1)
        same(JSONObject().put("kind", "quiz").put("dismissed", true),
            JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text")))
        assertTrue(result.getJSONObject("details").getBoolean("dismissed"))
        assertFalse(result.getJSONObject("details").has("correct"))
        assertFalse(result.getJSONObject("details").has("confidence"))
        assertFalse(dismissed.state().continuation)
        assertNull(dismissed.pendingQuestion())
        assertNotNull(source.pendingQuestion())
        assertThrows(IllegalArgumentException::class.java) { dismissed.dismissQuestion(id) }
    }

    @Test
    fun forgedAndEmptySubmissionsAndFailedGenerationsCannotBecomeAnswers() {
        val test = vectors.getJSONArray("cases").getJSONObject(0)
        val source = pending(test)
        val original = source.raw
        val id = test.getJSONObject("call").getString("id")
        for (request in listOf(
            JSONObject(), JSONObject().put("values", JSONArray().put("not-an-option")),
            JSONObject().put("values", JSONArray().put("two").put("two")),
            JSONObject().put("dontKnow", "true"),
            JSONObject().put("dontKnow", true).put("values", JSONArray().put("two")),
            JSONObject().put("text", "No text mode on a quiz"),
            JSONObject().put("values", JSONArray().put("two")).put("correct", true),
            JSONObject().put("values", JSONArray().put("two")).put("confidence", "90"),
            JSONObject().put("values", JSONArray().put("two")).put("thinking", "x".repeat(2001)),
        )) assertThrows(Exception::class.java) { source.answerPending(id, request) }
        assertEquals(original, source.raw)
        for (stop in listOf("error", "aborted")) {
            val record = source.snapshot()
            record.getJSONArray("messages").getJSONObject(0).put("stopReason", stop)
            val failed = NativeChat.parse(record.toString())
            assertThrows(IllegalArgumentException::class.java) { failed.pendingQuestion() }
            assertThrows(IllegalArgumentException::class.java) { failed.dismissQuestion(id) }
        }
        val ask = question(vectors.getJSONArray("cases").objects().first { it.getString("name") == "ask-single" })
        assertThrows(IllegalArgumentException::class.java) { ask.answer(JSONObject().put("dontKnow", true), 0) }
        assertThrows(IllegalArgumentException::class.java) {
            ask.answer(JSONObject().put("values", JSONArray().put("First")).put("other", "Other too"), 0)
        }
    }

    @Test
    fun refusingAnAppendAtTheArchiveLimitLeavesTheOriginalPendingQuestionUntouched() {
        val test = vectors.getJSONArray("cases").getJSONObject(0)
        val record = pending(test).snapshot()
        val pose = record.getJSONArray("messages").getJSONObject(0)
        record.put("messages", JSONArray((0 until 9999).map { i ->
            JSONObject().put("id", "authored-limit-$i").put("role", "user").put("content", "Authored test").put("timestamp", 0)
        }).put(pose))
        val source = NativeChat.parse(record.toString())
        val raw = source.raw
        assertThrows(IllegalArgumentException::class.java) {
            source.answerPending(test.getJSONObject("call").getString("id"), test.getJSONArray("answers").getJSONObject(0).getJSONObject("request"))
        }
        assertEquals(raw, source.raw)
        assertNotNull(source.pendingQuestion())
        assertEquals(10000, source.snapshot().getJSONArray("messages").length())
    }

    @Test
    fun pendingProjectionContainsNeitherTeacherCommitmentsNorGrades() {
        for (test in vectors.getJSONArray("cases").objects()) {
            val client = question(test).clientQuestion()
            assertEquals(setOf("id", "kind", "question", "mode", "options") +
                if (client.has("context")) setOf("context") else emptySet(), client.keys().asSequence().toSet())
            for (option in client.getJSONArray("options").objects())
                assertTrue(option.keys().asSequence().all { it in setOf("label", "value", "description") })
        }
    }
}
