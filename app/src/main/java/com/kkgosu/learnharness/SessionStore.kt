package com.kkgosu.learnharness

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kkgosu.learnharness.LessonDisplayItem.Role
import com.kkgosu.learnharness.LessonDisplayItem.TextDisplayItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class RuntimeQuestion(val callId: String, val text: String, val choices: List<String>) {
    fun json() = JSONObject().put("call", callId).put("text", text).put("choices", JSONArray(choices))
    fun transcript() = text + if (choices.isEmpty()) "" else choices.joinToString("\n", "\n\n") { "- $it" }
}

internal fun answerText(answer: JSONObject): String = listOf(answer.getString("answer"), answer.optString("reasoning"))
    .filter { it.isNotBlank() }.distinct().joinToString("\n\n") + "\n\nConfidence (self-report): ${answer.getInt("confidence_self_report")}%"

internal data class RuntimeSession(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val model: String,
    val phase: String = "probe",
    val status: String = "idle",
    val protocol: String = "[]",
    val items: List<TextDisplayItem> = emptyList(),
    val question: RuntimeQuestion? = null,
    val draft: String = "",
    val selection: Int = -1,
    val confidence: Int = 60,
    val expanded: Boolean = true,
    val route: String = "",
    val routeCall: String = "",
    val answers: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val error: String = "",
    val promptVersion: String = AgentInstructions.VERSION,
    val connection: String = "api",
    val effort: String = "",
    val contextTokens: Long = 0,
    // Old checkpoints kept questions only in evidence tables, not in the visible transcript.
    val pastQuestions: Map<Long, RuntimeQuestion> = emptyMap(),
) {
    val displayItems: List<TextDisplayItem> get() = items.flatMap { item ->
        val question = pastQuestions[item.id]
        if (question == null) listOf(item) else listOf(TextDisplayItem(-item.id - 1, question.transcript(), Role.QUESTION), item)
    }
    fun checkpoint() = JSONObject().put("phase", phase).put("status", status).put("protocol", protocol)
        .put("question", question?.json()).put("draft", draft).put("selection", selection)
        .put("confidence", confidence).put("expanded", expanded).put("route", route).put("routeCall", routeCall)
        .put("answers", answers).put("inputTokens", inputTokens).put("outputTokens", outputTokens)
        .put("error", error).put("promptVersion", promptVersion).put("connection", connection)
        .put("effort", effort).put("contextTokens", contextTokens).toString()
}

/** One concrete SQLite owner. Transactions couple rendered messages, tool state and learner evidence. */
internal class SessionStore(context: Context, name: String = "sessions.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, goal TEXT NOT NULL, model TEXT NOT NULL, checkpoint TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, id INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY(session,id))")
        db.execSQL("CREATE TABLE questions(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, call_id TEXT NOT NULL, question TEXT NOT NULL, PRIMARY KEY(session,call_id))")
        db.execSQL("CREATE TABLE answers(session TEXT NOT NULL, call_id TEXT NOT NULL, message_id INTEGER NOT NULL, answer TEXT NOT NULL, PRIMARY KEY(session,call_id), FOREIGN KEY(session,call_id) REFERENCES questions(session,call_id))")
        db.execSQL("CREATE TABLE runs(id TEXT PRIMARY KEY, session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, phase TEXT NOT NULL, prompt_version TEXT NOT NULL, status TEXT NOT NULL, input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported database migration") }

    @Synchronized fun list(): List<Pair<String, String>> = readableDatabase.rawQuery("SELECT id,goal FROM sessions ORDER BY updated DESC LIMIT 30", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
    }

    @Synchronized fun load(id: String): RuntimeSession? = readableDatabase.rawQuery("SELECT goal,model,checkpoint FROM sessions WHERE id=?", arrayOf(id)).use { cursor ->
        if (!cursor.moveToFirst()) return null
        val json = JSONObject(cursor.getString(2))
        val question = json.optJSONObject("question")?.let { q ->
            val choices = q.getJSONArray("choices")
            RuntimeQuestion(q.getString("call"), q.getString("text"), (0 until choices.length()).map { choices.getString(it) })
        }
        val answers = readableDatabase.rawQuery("SELECT a.message_id,a.answer,q.question FROM answers a JOIN questions q ON q.session=a.session AND q.call_id=a.call_id WHERE a.session=?", arrayOf(id)).use { rows ->
            buildMap { while (rows.moveToNext()) put(rows.getLong(0), JSONObject(rows.getString(1)) to JSONObject(rows.getString(2))) }
        }
        val items = readableDatabase.rawQuery("SELECT id,role,text FROM messages WHERE session=? ORDER BY id", arrayOf(id)).use { rows ->
            buildList { while (rows.moveToNext()) {
                val message = rows.getLong(0)
                add(TextDisplayItem(message, answers[message]?.first?.let(::answerText) ?: rows.getString(2), Role.valueOf(rows.getString(1))))
            } }
        }
        val past = buildMap {
            items.forEachIndexed { index, item ->
                val q = answers[item.id]?.second
                if (q != null && items.getOrNull(index - 1)?.role != Role.QUESTION) {
                    val choices = q.getJSONArray("choices")
                    put(item.id, RuntimeQuestion(q.getString("call"), q.getString("text"), (0 until choices.length()).map { choices.getString(it) }))
                }
            }
        }
        RuntimeSession(id, cursor.getString(0), cursor.getString(1), json.getString("phase"), json.getString("status"),
            json.getString("protocol"), items, question, json.getString("draft"), json.getInt("selection"),
            ((json.getInt("confidence").coerceIn(0, 100) + 10) / 20) * 20, json.getBoolean("expanded"), json.getString("route"), json.getString("routeCall"),
            json.getInt("answers"), json.getLong("inputTokens"), json.getLong("outputTokens"), json.getString("error"), json.getString("promptVersion"), json.optString("connection", "api"),
            json.optString("effort"), json.optLong("contextTokens"), past)
    }

    @Synchronized fun save(state: RuntimeSession, answer: String? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", state.id); put("goal", state.goal); put("model", state.model)
                put("checkpoint", state.checkpoint()); put("updated", System.currentTimeMillis())
            }
            // UPDATE, not REPLACE: replacing the parent would cascade-delete its evidence.
            if (db.update("sessions", values, "id=?", arrayOf(state.id)) == 0) db.insertOrThrow("sessions", null, values)
            // ponytail: bounded sessions (160 blocks) use simple upserts; batch only dirty rows for longer lessons.
            state.items.forEach { item ->
                check(db.insertWithOnConflict("messages", null, ContentValues().apply {
                    put("session", state.id); put("id", item.id); put("role", item.role.name); put("text", item.text)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
            }
            state.question?.let { q -> db.insertWithOnConflict("questions", null, ContentValues().apply {
                put("session", state.id); put("call_id", q.callId); put("question", q.json().toString())
            }, SQLiteDatabase.CONFLICT_IGNORE) }
            if (answer != null) {
                val q = JSONObject(answer)
                db.insertOrThrow("answers", null, ContentValues().apply {
                    put("session", state.id); put("call_id", q.getString("call")); put("message_id", state.items.last().id); put("answer", answer)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun run(id: String, session: RuntimeSession, status: String, input: Long = 0, output: Long = 0) {
        check(writableDatabase.insertWithOnConflict("runs", null, ContentValues().apply {
            put("id", id); put("session", session.id); put("phase", session.phase); put("prompt_version", session.promptVersion)
            put("status", status); put("input_tokens", input); put("output_tokens", output)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    @Synchronized fun recover() {
        val db = writableDatabase
        db.execSQL("UPDATE runs SET status='interrupted' WHERE status='running'")
        val ids = db.rawQuery("SELECT id FROM sessions", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        ids.forEach { id -> load(id)?.takeIf { it.status == "running" }?.let { save(it.copy(status = "interrupted")) } }
    }
}
