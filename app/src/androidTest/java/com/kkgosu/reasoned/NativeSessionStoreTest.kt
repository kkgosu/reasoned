package com.kkgosu.reasoned

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kkgosu.reasoned.api.ModelProvider
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
                val original = session().let { it.copy(nativeRecord = native(it.id)) }
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
