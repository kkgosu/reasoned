package com.kkgosu.reasoned

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.reasoned.api.ModelProvider
import com.kkgosu.reasoned.api.NativeResponses
import com.kkgosu.reasoned.controllers.SessionRuntime
import com.kkgosu.reasoned.model.LessonDisplayItem.TextDisplayItem
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.RuntimeQuestion
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.storage.SessionStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic disposable databases only; no installed desktop data and no providers. */
@RunWith(AndroidJUnit4::class)
class NativeSessionStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<ReasonedApplication>()
    private fun message(id: String, text: String) = JSONObject().put("id", id).put("role", "user")
        .put("content", text).put("timestamp", 1)
    private fun native(id: String, text: String = "Authored fixture") = NativeChat.parse(
        JSONObject().put("id", id).put("messages", JSONArray().put(message("first", text)))
            .put("unknown", JSONObject().put("opaque", "DO_NOT_REWRITE")).toString()
    )
    private fun session() = RuntimeSession(goal = "Authored fixture", model = "fixture",
        items = listOf(TextDisplayItem(1, "Authored fixture")))

    @Test
    fun pendingNativeAccountingRecoversAtomicallyWithoutDoubleCountingOrInventingReports() {
        for (boundary in listOf("inFlight", "receipt", "accepted", "compaction", "finalized")) {
            val name = "native-recovery-${UUID.randomUUID()}.db"
            try {
                val base = session()
                val response = JSONObject("""{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Authored recovery response"}]}],"usage":{"input_tokens":40,"input_tokens_details":{"cached_tokens":12},"output_tokens":10}}""")
                val known = boundary !in setOf("inFlight", "compaction")
                val steps = JSONArray().apply { if (known) put(NativeResponses.step(NativeResponses.usage(response))) }
                var chat = native(base.id)
                if (boundary in setOf("accepted", "finalized"))
                    chat = NativeResponses.append(chat, NativeResponses.assistant(response, base.model, base.connection, 2), 2)
                val owner = chat.snapshot().getJSONArray("messages").getJSONObject(if (boundary in setOf("accepted", "finalized")) 1 else 0).getString("id")
                val entry = NativeResponses.turn("recovery_run", owner, steps, base.model, base.connection, 2)
                    .put("reasonedPurpose", if (boundary == "compaction") "compaction" else "conversation")
                if (boundary == "finalized") chat = NativeResponses.appendTurn(chat, entry.put("reasonedStatus", "completed"))
                val pending = JSONObject().put("entry", NativeResponses.capture(entry, response.takeIf { boundary == "receipt" }))
                    .put("inFlight", boundary in setOf("inFlight", "compaction")).put("responsePending", boundary == "receipt")
                SessionStore(app, name).use { store ->
                    store.save(base.copy(nativeRecord = chat, status = "running", inputTokens = if (known) 40 else 0,
                        outputTokens = if (known) 10 else 0, nativeTurn = if (boundary == "finalized") "" else pending.toString()))
                    store.run("recovery_run", base, "running")
                }
                SessionStore(app, name).use { store ->
                    if (boundary == "receipt") {
                        val before = store.load(base.id)!!
                        store.writableDatabase.execSQL("CREATE TRIGGER reject_recovery BEFORE UPDATE ON sessions BEGIN SELECT RAISE(ABORT,'authored rollback'); END")
                        try { store.recover(); fail("Recovery did not roll back") } catch (_: SQLiteConstraintException) { }
                        assertEquals(before.nativeRecord, store.load(base.id)!!.nativeRecord)
                        assertEquals(before.nativeTurn, store.load(base.id)!!.nativeTurn)
                        store.readableDatabase.rawQuery("SELECT status FROM runs", null).use { row ->
                            assertTrue(row.moveToFirst()); assertEquals("running", row.getString(0))
                        }
                        store.writableDatabase.execSQL("DROP TRIGGER reject_recovery")
                    }
                    store.recover()
                    val restored = store.load(base.id)!!
                    assertEquals("", restored.nativeTurn)
                    assertEquals(if (known) 40L else 0L, restored.inputTokens)
                    assertEquals(if (known) 10L else 0L, restored.outputTokens)
                    val root = restored.nativeRecord!!.snapshot()
                    val turn = root.getJSONArray("turns").getJSONObject(0)
                    assertEquals(1, root.getJSONArray("turns").length())
                    assertEquals(if (boundary == "finalized") "completed" else "interrupted", turn.getString("reasonedStatus"))
                    assertEquals(1, turn.getJSONArray("steps").length())
                    assertEquals(known, turn.getJSONArray("steps").getJSONObject(0).has("inputTokens"))
                    assertEquals(if (boundary == "compaction") 1 else 2, root.getJSONArray("messages").length())
                    if (boundary == "compaction") assertEquals(chat.snapshot().getJSONArray("messages").toString(), root.getJSONArray("messages").toString())
                    store.readableDatabase.rawQuery("SELECT status,input_tokens,output_tokens FROM runs", null).use { row ->
                        assertTrue(row.moveToFirst()); assertEquals(turn.getString("reasonedStatus"), row.getString(0))
                        assertEquals(restored.inputTokens, row.getLong(1)); assertEquals(restored.outputTokens, row.getLong(2))
                    }
                    val saved = restored.nativeRecord.raw
                    store.recover()
                    assertEquals(saved, store.load(base.id)!!.nativeRecord!!.raw)
                }
            } finally { app.deleteDatabase(name) }
        }
    }

    @Test
    fun nativeArchiveAndMultipartIdentitiesSurviveReopenDraftSavesAndAtomicFailure() {
        val name = "native-store-${UUID.randomUUID()}.db"
        try {
            val legacy = session().copy(question = RuntimeQuestion("answer", "Fixture question", emptyList()))
            val saved = legacy.copy(nativeRecord = native(legacy.id))
            val answer = saved.question!!.answer("Authored learner answer", -1, 60, "", false).toString()
            var ids: Map<NativeChat.Part, Long>
            SessionStore(app, name).use { store ->
                store.save(saved, answer)
                val restored = store.load(saved.id)!!
                assertEquals(saved.nativeRecord!!.raw, restored.nativeRecord!!.raw)
                ids = restored.nativeDisplayIds
                assertEquals(setOf(NativeChat.Part("first")), ids.keys)
                assertTrue(ids.values.all { it > 1 })
                assertFalse(restored.checkpoint().contains("DO_NOT_REWRITE"))
                store.save(restored.copy(draft = "Unsubmitted local draft"))
                val record = restored.nativeRecord.snapshot()
                record.getJSONArray("messages").put(JSONObject().put("id", "second").put("role", "assistant")
                    .put("timestamp", 2).put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "Authored tutor prose"))
                        .put(JSONObject().put("type", "thinking").put("thinking", "Opaque test scratch"))))
                val changed = restored.copy(nativeRecord = NativeChat.parse(record.toString()), draft = "Must roll back")
                try {
                    store.save(changed, answer); fail("Duplicate answer did not abort the transaction")
                } catch (_: SQLiteConstraintException) {
                }
                assertEquals(saved.nativeRecord.raw, store.load(saved.id)!!.nativeRecord!!.raw)
                assertEquals(ids, store.load(saved.id)!!.nativeDisplayIds)
                assertEquals("Unsubmitted local draft", store.load(saved.id)!!.draft)
                store.save(changed.copy(draft = "Unsubmitted local draft"))
                val extended = store.load(saved.id)!!
                assertEquals(ids, extended.nativeDisplayIds.filterKeys { it.message == "first" })
                assertEquals(setOf(NativeChat.Part("second"), NativeChat.Part("second", 0), NativeChat.Part("second", 1)),
                    extended.nativeDisplayIds.keys.filter { it.message == "second" }.toSet())
                assertEquals(extended.nativeDisplayIds.size, extended.nativeDisplayIds.values.toSet().size)
                try {
                    store.save(legacy); fail("A legacy writer discarded the native archive")
                } catch (_: IllegalStateException) {
                }
                assertEquals(changed.nativeRecord!!.raw, store.load(saved.id)!!.nativeRecord!!.raw)
                ids = extended.nativeDisplayIds
            }
            SessionStore(app, name).use { store ->
                val restored = store.load(saved.id)!!
                assertEquals(ids, restored.nativeDisplayIds)
                assertEquals("Unsubmitted local draft", restored.draft)
                store.rename(saved.id, "Local title")
                store.recover()
                assertEquals(restored.nativeRecord!!.raw, store.load(saved.id)!!.nativeRecord!!.raw)
                store.delete(saved.id)
                assertNull(store.load(saved.id))
                store.readableDatabase.rawQuery("SELECT native_record FROM sessions WHERE id=?", arrayOf(saved.id)).use {
                    assertTrue(it.moveToFirst()); assertTrue(it.isNull(0))
                }
                store.readableDatabase.rawQuery("SELECT count(*) FROM native_display_ids WHERE session=?", arrayOf(saved.id)).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test
    fun submittedNativeAnswerSurvivesReopenWithItsOriginalCommitmentAndDisplayIds() {
        val name = "native-answer-${UUID.randomUUID()}.db"
        try {
            val original = session()
            val record = native(original.id).snapshot()
            val args = JSONObject().put("question", "[Authored test] Which numbers are even?")
                .put("options", JSONArray().put(JSONObject().put("label", "Two").put("value", "two"))
                    .put(JSONObject().put("label", "Three").put("value", "three")))
                .put("correctAnswer", "two").put("explanation", "Teacher-only authored explanation.")
            record.getJSONArray("messages").put(JSONObject().put("id", "pose").put("role", "assistant")
                .put("stopReason", "toolUse").put("timestamp", 2).put("content", JSONArray()
                    .put(JSONObject().put("type", "thinking").put("thinking", "Opaque authored scratch")
                        .put("thinkingSignature", "OPAQUE_TEST_SIGNATURE"))
                    .put(JSONObject().put("type", "toolCall").put("id", "authored-call")
                        .put("name", "probe").put("arguments", args))))
            val pending = NativeChat.parse(record.toString())
            val answered = pending.answerPending("authored-call", JSONObject()
                .put("values", JSONArray().put("two")).put("confidence", 90)
                .put("thinking", "Learner-only authored reasoning."), 1_790_812_802_000)
            var ids: Map<NativeChat.Part, Long>
            SessionStore(app, name).use { store ->
                store.save(original.copy(nativeRecord = pending))
                ids = store.load(original.id)!!.nativeDisplayIds
                store.save(store.load(original.id)!!.copy(nativeRecord = answered))
            }
            SessionStore(app, name).use { store ->
                val loaded = store.load(original.id)!!
                val native = loaded.nativeRecord!!
                assertEquals(answered.raw, native.raw)
                assertEquals(ids, loaded.nativeDisplayIds.filterKeys { it in ids })
                assertNull(native.pendingQuestion())
                assertTrue(native.state().continuation) // A read does not exercise that continuation.
                val messages = native.snapshot().getJSONArray("messages")
                assertEquals(pending.snapshot().getJSONArray("messages").getJSONObject(1).toString(), messages.getJSONObject(1).toString())
                val result = messages.getJSONObject(2)
                val details = result.getJSONObject("details")
                assertEquals(90, details.getInt("confidence")) // Not the legacy 20-point grid.
                assertTrue(details.getBoolean("correct"))
                assertEquals("Teacher-only authored explanation.", details.getString("explanation"))
                val compact = JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"))
                assertEquals("Learner-only authored reasoning.", compact.getString("thinking"))
                assertFalse(compact.has("explanation"))
                assertFalse(compact.has("questionText"))
                assertTrue(loaded.nativeDisplayIds.containsKey(NativeChat.Part(result.getString("id"))))
            }
        } finally { app.deleteDatabase(name) }
    }

    @Test
    fun largeUnicodeNativeRecordIsReadInCursorWindowSafeSlices() {
        val name = "native-large-${UUID.randomUUID()}.db"
        try {
            val legacy = session()
            val original = native(legacy.id, "🙂".repeat(600_000))
            SessionStore(app, name).use { it.save(legacy.copy(nativeRecord = original)) }
            SessionStore(app, name).use { assertEquals(original.raw, it.load(legacy.id)!!.nativeRecord!!.raw) }
        } finally { app.deleteDatabase(name) }
    }

    @Test
    fun legacyMigrationsDoNotInventNativeEventsOrChangeCheckpointEvidence() {
        for (version in 1..3) {
            val name = "native-v$version-${UUID.randomUUID()}.db"
            try {
                val original = session().copy(draft = "Unsubmitted fixture draft")
                SessionStore(app, name).use { it.save(original) }
                SQLiteDatabase.openDatabase(app.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    db.execSQL("DROP TABLE native_display_ids")
                    if (version == 1) for (table in listOf("knowledge_runs", "knowledge_coverage", "knowledge_sources", "knowledge_chains", "knowledge_nodes"))
                        db.execSQL("DROP TABLE $table")
                    val extra = if (version == 3) ",title TEXT NOT NULL DEFAULT '',deleted INTEGER NOT NULL DEFAULT 0" else ""
                    db.execSQL("CREATE TABLE old_sessions(id TEXT PRIMARY KEY,goal TEXT NOT NULL,model TEXT NOT NULL,checkpoint TEXT NOT NULL,updated INTEGER NOT NULL$extra)")
                    val columns = "id,goal,model,checkpoint,updated" + if (version == 3) ",title,deleted" else ""
                    db.execSQL("INSERT INTO old_sessions SELECT $columns FROM sessions")
                    db.execSQL("DROP TABLE sessions")
                    db.execSQL("ALTER TABLE old_sessions RENAME TO sessions")
                    db.version = version
                }
                SessionStore(app, name).use { store ->
                    val restored = store.load(original.id)!!
                    assertEquals(original, restored)
                    assertNull(restored.nativeRecord)
                    assertTrue(restored.nativeDisplayIds.isEmpty())
                    assertEquals(4, store.readableDatabase.version)
                    assertNotNull(store.list().single())
                    store.readableDatabase.rawQuery("SELECT checkpoint FROM sessions WHERE id=?", arrayOf(original.id)).use {
                        assertTrue(it.moveToFirst()); assertEquals(original.checkpoint(), it.getString(0))
                    }
                    store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                }
            } finally { app.deleteDatabase(name) }
        }
    }

    @Test
    fun openingNativeHistoryCannotEnterTheLegacyRunnerOrCallAProvider() = runBlocking {
        val name = "native-open-${UUID.randomUUID()}.db"
        try {
            SessionStore(app, name).use { store ->
                val original = session().let { it.copy(nativeRecord = NativeChat.parse(native(it.id).snapshot().put("kind", "unsupported-fixture-kind").toString())) }
                store.save(original)
                var calls = 0
                val runtime = SessionRuntime(app, store, ModelProvider { _, _ ->
                    calls++; error("Native restoration invoked a provider")
                })
                try {
                    runtime.open(original.id).join()
                    assertEquals("native_runtime_unavailable", runtime.failure.value)
                    assertNull(runtime.state.value)
                    runtime.retry().join()
                    assertEquals(0, calls)
                    assertEquals(original.nativeRecord!!.raw, store.load(original.id)!!.nativeRecord!!.raw)
                } finally { runtime.stop().join() }
            }
        } finally { app.deleteDatabase(name) }
    }
}
