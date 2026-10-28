package com.kkgosu.learnharness.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kkgosu.learnharness.controllers.QuestionContract
import com.kkgosu.learnharness.model.ContextSummary
import com.kkgosu.learnharness.model.LessonDisplayItem.Role
import com.kkgosu.learnharness.model.LessonDisplayItem.TextDisplayItem
import com.kkgosu.learnharness.model.PracticeProblem
import com.kkgosu.learnharness.model.ProbeAssessment
import com.kkgosu.learnharness.model.ResearchReport
import com.kkgosu.learnharness.model.RuntimeQuestion
import com.kkgosu.learnharness.model.RuntimeSession
import com.kkgosu.learnharness.model.StudyPlan
import com.kkgosu.learnharness.model.answerText
import com.kkgosu.learnharness.utils.objects
import com.kkgosu.learnharness.utils.strings
import org.json.JSONObject

/** One concrete SQLite owner. Transactions couple rendered messages, tool state and learner evidence. */
internal class SessionStore(context: Context, name: String = "sessions.db") :
    SQLiteOpenHelper(context, name, null, 3) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, goal TEXT NOT NULL, model TEXT NOT NULL, checkpoint TEXT NOT NULL, updated INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, id INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY(session,id))")
        db.execSQL("CREATE TABLE questions(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, call_id TEXT NOT NULL, question TEXT NOT NULL, PRIMARY KEY(session,call_id))")
        db.execSQL("CREATE TABLE answers(session TEXT NOT NULL, call_id TEXT NOT NULL, message_id INTEGER NOT NULL, answer TEXT NOT NULL, PRIMARY KEY(session,call_id), FOREIGN KEY(session,call_id) REFERENCES questions(session,call_id))")
        db.execSQL("CREATE TABLE runs(id TEXT PRIMARY KEY, session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, phase TEXT NOT NULL, prompt_version TEXT NOT NULL, status TEXT NOT NULL, input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL)")
        createKnowledgeTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion == 3) { "Unsupported database migration" }
        if (oldVersion == 1) createKnowledgeTables(db)
        db.execSQL("ALTER TABLE sessions ADD COLUMN title TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE sessions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
    }

    @Synchronized
    fun list(): List<Pair<String, String>> = readableDatabase.rawQuery(
        "SELECT id,coalesce(nullif(title,''),goal) FROM sessions WHERE deleted=0 ORDER BY updated DESC LIMIT 30",
        null
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
    }

    @Synchronized
    fun rename(id: String, title: String) {
        val name = title.trim()
        require(name.length in 1..2000 && name.none { it.isISOControl() })
        check(
            writableDatabase.update(
                "sessions", ContentValues().apply { put("title", name) },
                "id=? AND deleted=0", arrayOf(id)
            ) == 1
        ) { "Session missing" }
    }

    /** Keep only origin metadata for graph grouping; erase chat content and its source links atomically. */
    @Synchronized
    fun delete(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE sessions SET title=coalesce(nullif(title,''),goal),goal='',model='',checkpoint='',deleted=1 WHERE id=? AND deleted=0",
                arrayOf(id)
            )
            for (table in listOf(
                "knowledge_sources",
                "knowledge_coverage",
                "knowledge_runs",
                "answers",
                "questions",
                "messages",
                "runs"
            ))
                db.delete(table, "session=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun load(id: String): RuntimeSession? = readableDatabase.rawQuery(
        "SELECT goal,model,length(checkpoint),substr(checkpoint,1,131072) FROM sessions WHERE id=? AND deleted=0",
        arrayOf(id)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        // Android's CursorWindow cannot hold a multi-megabyte row. Read the archive in bounded SQL slices.
        val checkpoint = buildString {
            append(cursor.getString(3))
            var offset = 131073
            while (offset <= cursor.getInt(2)) {
                readableDatabase.rawQuery(
                    "SELECT substr(checkpoint,?,131072) FROM sessions WHERE id=?",
                    arrayOf(offset.toString(), id)
                ).use { slice ->
                    check(slice.moveToFirst()); append(slice.getString(0))
                }
                offset += 131072
            }
        }
        val json = JSONObject(checkpoint)
        val question = json.optJSONObject("question")?.let(RuntimeQuestion::parse)
        val answers = readableDatabase.rawQuery(
            "SELECT a.message_id,a.answer,q.question FROM answers a JOIN questions q ON q.session=a.session AND q.call_id=a.call_id WHERE a.session=?",
            arrayOf(id)
        ).use { rows ->
            buildMap {
                while (rows.moveToNext()) put(
                    rows.getLong(0),
                    JSONObject(rows.getString(1)) to JSONObject(rows.getString(2))
                )
            }
        }
        val items = readableDatabase.rawQuery(
            "SELECT id,role,text FROM messages WHERE session=? ORDER BY id",
            arrayOf(id)
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val message = rows.getLong(0)
                    add(
                        TextDisplayItem(
                            message,
                            answers[message]?.first?.let(::answerText) ?: rows.getString(2),
                            Role.valueOf(rows.getString(1))
                        )
                    )
                }
            }
        }
        RuntimeSession(
            id,
            cursor.getString(0),
            cursor.getString(1),
            json.getString("phase"),
            json.getString("status"),
            json.getString("protocol"),
            items,
            question,
            json.getString("draft"),
            json.getInt("selection"),
            ((json.getInt("confidence").coerceIn(0, 100) + 10) / 20) * 20,
            json.getBoolean("expanded"),
            json.getString("route"),
            json.getString("routeCall"),
            json.getInt("answers"),
            json.getLong("inputTokens"),
            json.getLong("outputTokens"),
            json.getString("error"),
            json.getString("promptVersion"),
            json.optString("connection", "api"),
            json.optString("effort"),
            json.optLong("contextTokens"),
            json.optJSONObject("plan")?.let { StudyPlan.parse(it) },
            json.optJSONArray("proseIds")
                ?.let { ids -> (0 until ids.length()).map { ids.getLong(it) }.toSet() },
            answers.mapValues { (message, data) ->
                RuntimeQuestion.parse(data.second).answered(message, data.first)
            },
            planPending = json.optBoolean("planPending", json.optString("status") == "plan"),
            practiceMaterial = json.optString("practiceMaterial", "[]"),
            problems = json.optJSONArray("problems")?.objects()?.map(PracticeProblem::parse)
                .orEmpty(),
            research = json.optJSONArray("research")?.objects()?.map(ResearchReport::parse)
                .orEmpty(),
            researchAllowed = json.optBoolean("researchAllowed"),
            practiceReady = json.optBoolean("practiceReady", true),
            readingThinking = json.optString("readingThinking"),
            dontKnow = json.optBoolean("dontKnow"),
            assessment = json.optJSONObject("assessment")?.let(ProbeAssessment::parse),
            planApproved = json.optBoolean(
                "planApproved", json.has("plan") && !json.isNull("plan") &&
                        !json.optBoolean("planPending", json.optString("status") == "plan")
            ),
            mechanic = json.optString("mechanic", "choice")
                .also { require(it in setOf("choice", "recall")) },
            compaction = json.optJSONObject("compaction")?.let(ContextSummary::parse),
            contextEnd = json.optInt("contextEnd"),
            contextOutputTokens = json.optLong("contextOutputTokens"),
            selectedValues = json.optJSONArray("selectedValues")
                ?.strings(QuestionContract.MAX_OPTIONS, 4000)
                ?: listOfNotNull(question?.values?.getOrNull(json.getInt("selection")))
        )
    }

    @Synchronized
    fun save(state: RuntimeSession, answer: String? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", state.id); put("goal", state.goal); put("model", state.model)
                put("checkpoint", state.checkpoint()); put("updated", System.currentTimeMillis())
            }
            // UPDATE, not REPLACE: replacing the parent would cascade-delete its evidence.
            if (db.update(
                    "sessions",
                    values,
                    "id=? AND deleted=0",
                    arrayOf(state.id)
                ) == 0
            ) db.insertOrThrow("sessions", null, values)
            // A rejected completed tool can retract a provisional streamed tail, never committed history.
            db.delete(
                "messages",
                "session=? AND id>?",
                arrayOf(state.id, (state.items.lastOrNull()?.id ?: 0).toString())
            )
            // Compare in one read: streaming a long lesson must not rewrite every unchanged source row.
            val existing =
                db.rawQuery("SELECT id,role,text FROM messages WHERE session=?", arrayOf(state.id))
                    .use { rows ->
                        buildMap {
                            while (rows.moveToNext()) put(
                                rows.getLong(0),
                                rows.getString(1) to rows.getString(2)
                            )
                        }
                    }
            state.items.filter { existing[it.id] != (it.role.name to it.text) }.forEach { item ->
                val message = ContentValues().apply {
                    put("session", state.id); put("id", item.id); put(
                    "role",
                    item.role.name
                ); put("text", item.text)
                }
                // REPLACE deletes the old row and would destroy/reject persistent source anchors.
                if (db.update(
                        "messages",
                        message,
                        "session=? AND id=?",
                        arrayOf(state.id, item.id.toString())
                    ) == 0
                )
                    db.insertOrThrow("messages", null, message)
            }
            state.question?.let { q ->
                db.insertWithOnConflict("questions", null, ContentValues().apply {
                    put("session", state.id); put("call_id", q.callId); put(
                    "question",
                    q.json().toString()
                )
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            if (answer != null) {
                val q = JSONObject(answer)
                db.insertOrThrow("answers", null, ContentValues().apply {
                    put("session", state.id); put("call_id", q.getString("call")); put(
                    "message_id",
                    state.items.last().id
                ); put("answer", answer)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun run(
        id: String,
        session: RuntimeSession,
        status: String,
        input: Long = 0,
        output: Long = 0
    ) {
        check(writableDatabase.insertWithOnConflict("runs", null, ContentValues().apply {
            put("id", id); put("session", session.id); put(
            "phase",
            session.phase
        ); put("prompt_version", session.promptVersion)
            put("status", status); put("input_tokens", input); put("output_tokens", output)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    @Synchronized
    fun recover() {
        val db = writableDatabase
        db.execSQL("UPDATE runs SET status='interrupted' WHERE status='running'")
        val ids = db.rawQuery("SELECT id FROM sessions", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        ids.forEach { id ->
            load(id)?.takeIf { it.status == "running" }
                ?.let { save(it.copy(status = "interrupted")) }
        }
    }
}
