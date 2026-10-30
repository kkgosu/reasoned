package com.kkgosu.reasoned.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kkgosu.reasoned.api.NativeResponses
import com.kkgosu.reasoned.controllers.QuestionContract
import com.kkgosu.reasoned.model.ContextSummary
import com.kkgosu.reasoned.model.LessonDisplayItem.Role
import com.kkgosu.reasoned.model.LessonDisplayItem.TextDisplayItem
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.PracticeProblem
import com.kkgosu.reasoned.model.ProbeAssessment
import com.kkgosu.reasoned.model.ResearchReport
import com.kkgosu.reasoned.model.RuntimeQuestion
import com.kkgosu.reasoned.model.RuntimeSession
import com.kkgosu.reasoned.model.StudyPlan
import com.kkgosu.reasoned.model.answerText
import com.kkgosu.reasoned.utils.objects
import com.kkgosu.reasoned.utils.strings
import org.json.JSONArray
import org.json.JSONObject

/** One concrete SQLite owner. Transactions couple rendered messages, tool state and learner evidence. */
internal class SessionStore(context: Context, name: String? = "sessions.db") :
    SQLiteOpenHelper(context, name, null, 5) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY, goal TEXT NOT NULL, model TEXT NOT NULL, checkpoint TEXT NOT NULL, native_record TEXT, updated INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE messages(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, id INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY(session,id))")
        db.execSQL("CREATE TABLE questions(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, call_id TEXT NOT NULL, question TEXT NOT NULL, PRIMARY KEY(session,call_id))")
        db.execSQL("CREATE TABLE answers(session TEXT NOT NULL, call_id TEXT NOT NULL, message_id INTEGER NOT NULL, answer TEXT NOT NULL, PRIMARY KEY(session,call_id), FOREIGN KEY(session,call_id) REFERENCES questions(session,call_id))")
        db.execSQL("CREATE TABLE runs(id TEXT PRIMARY KEY, session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, phase TEXT NOT NULL, prompt_version TEXT NOT NULL, status TEXT NOT NULL, input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL)")
        createKnowledgeTables(db)
        createNativeTable(db)
        createTransferTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..4 && newVersion == 5) { "Unsupported database migration" }
        if (oldVersion == 1) createKnowledgeTables(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE sessions ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
        }
        // No fabricated native events for legacy sessions, and no checkpoint/answer rewrites.
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN native_record TEXT")
            createNativeTable(db)
        }
        createTransferTables(db)
    }

    private fun createNativeTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE native_display_ids(session TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, message TEXT NOT NULL, part INTEGER NOT NULL, display_id INTEGER NOT NULL, PRIMARY KEY(session,message,part), UNIQUE(session,display_id))")
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
                "UPDATE sessions SET title=coalesce(nullif(title,''),goal),goal='',model='',checkpoint='',native_record=NULL,deleted=1 WHERE id=? AND deleted=0",
                arrayOf(id)
            )
            for (table in listOf(
                "native_display_ids",
                "knowledge_sources",
                "knowledge_coverage",
                "knowledge_runs",
                "answers",
                "questions",
                "messages",
                "runs"
            ))
                db.delete(table, "session=?", arrayOf(id))
            db.delete("native_transfer_versions", "chat=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun load(id: String): RuntimeSession? = readableDatabase.rawQuery(
        "SELECT goal,model FROM sessions WHERE id=? AND deleted=0",
        arrayOf(id)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        val json = JSONObject(readText(readableDatabase, id, "checkpoint")!!)
        val native = readText(readableDatabase, id, "native_record")?.let(NativeChat::parse)
        check(native == null || native.id == id) { "Native session identity mismatch" }
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
            if (native != null) json.getInt("confidence").coerceIn(0, 100)
            else ((json.getInt("confidence").coerceIn(0, 100) + 10) / 20) * 20,
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
                ?: listOfNotNull(question?.values?.getOrNull(json.getInt("selection"))),
            nativeRecord = native,
            nativeDisplayIds = nativeIds(readableDatabase, id),
            nativeTurn = json.optJSONObject("nativeTurn")?.toString().orEmpty(),
            nativeSteers = json.optJSONArray("nativeSteers")?.toString() ?: "[]"
        )
    }

    // SQLite substr/length use character offsets, not UTF-8 byte offsets (including for emoji).
    private fun readText(db: SQLiteDatabase, id: String, column: String): String? = db.rawQuery(
        "SELECT length($column),substr($column,1,131072) FROM sessions WHERE id=?", arrayOf(id)
    ).use { row ->
        check(row.moveToFirst())
        if (row.isNull(0)) return null
        buildString {
            append(row.getString(1))
            var offset = 131073
            while (offset <= row.getInt(0)) {
                db.rawQuery("SELECT substr($column,?,131072) FROM sessions WHERE id=?",
                    arrayOf(offset.toString(), id)).use { slice ->
                    check(slice.moveToFirst()); append(slice.getString(0))
                }
                offset += 131072
            }
        }
    }

    private fun nativeIds(db: SQLiteDatabase, id: String): Map<NativeChat.Part, Long> = db.rawQuery(
        "SELECT message,part,display_id FROM native_display_ids WHERE session=?", arrayOf(id)
    ).use { rows ->
        buildMap { while (rows.moveToNext()) put(NativeChat.Part(rows.getString(0), rows.getInt(1)), rows.getLong(2)) }
    }

    @Synchronized
    fun save(state: RuntimeSession, answer: String? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val native = state.nativeRecord
            require(native == null || native.id == state.id) { "Native session identity mismatch" }
            if (native == null) db.rawQuery(
                "SELECT native_record IS NOT NULL FROM sessions WHERE id=?", arrayOf(state.id)
            ).use { row ->
                check(!row.moveToFirst() || row.getInt(0) == 0) { "Cannot discard a native archive" }
            }
            // An in-flight command holds an older snapshot. Never overwrite a concurrently queued steer.
            val checkpoint = JSONObject(state.checkpoint())
            if (native != null) {
                val pending = (pendingNativeSteers(db, state.id) + JSONArray(state.nativeSteers).objects())
                    .distinctBy { it.getString("id") }
                if (pending.isNotEmpty()) {
                    val delivered = native.snapshot().getJSONArray("messages").objects().map { it.getString("id") }.toSet()
                    checkpoint.put("nativeSteers", JSONArray(pending.filter { it.getString("id") !in delivered }))
                }
            }
            val values = ContentValues().apply {
                put("id", state.id); put("goal", state.goal); put("model", state.model)
                put("checkpoint", checkpoint.toString()); put("updated", System.currentTimeMillis())
                put("native_record", native?.raw)
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
            if (native != null) {
                val ids = nativeIds(db, state.id)
                var next = maxOf(ids.values.maxOrNull() ?: 0, existing.keys.maxOrNull() ?: 0,
                    state.items.maxOfOrNull { it.id } ?: 0)
                native.parts().filter { it !in ids }.forEach { part ->
                    db.insertOrThrow("native_display_ids", null, ContentValues().apply {
                        put("session", state.id); put("message", part.message); put("part", part.index)
                        put("display_id", ++next)
                    })
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

    private fun pendingNativeSteers(db: SQLiteDatabase, id: String): List<JSONObject> = db.rawQuery(
        "SELECT checkpoint FROM sessions WHERE id=? AND deleted=0", arrayOf(id)
    ).use { row ->
        if (row.moveToFirst()) JSONObject(row.getString(0)).optJSONArray("nativeSteers")?.objects().orEmpty()
        else emptyList()
    }

    /** Separate from the foreground command mutex: queue only while that conversation is live. */
    @Synchronized
    fun queueNativeSteer(id: String, run: String, text: String): String {
        require(text.isNotBlank() && text.length <= 2000)
        val s = load(id) ?: error("Session missing")
        check(s.nativeRecord != null && s.status == "running" && s.nativeTurn.isNotEmpty())
        val entry = JSONObject(s.nativeTurn).getJSONObject("entry")
        check(entry.getString("id") == run && entry.optString("reasonedPurpose") == "conversation")
        val pending = JSONArray(s.nativeSteers)
        require(pending.length() < 8)
        val message = NativeResponses.user(text, System.currentTimeMillis())
            .put("id", "steer_${java.util.UUID.randomUUID()}")
        save(s.copy(nativeSteers = pending.put(message).toString()))
        return pending.toString()
    }

    /** Allocate native identities and commit archive + derived UI together, including rollback.
     * Drain only at an explicit live-run boundary, never on restore or at a question/plan stopper. */
    @Synchronized
    fun saveNative(state: RuntimeSession, consumeSteers: Boolean = false): RuntimeSession {
        require(state.nativeRecord != null)
        val db = writableDatabase
        db.beginTransaction()
        try {
            save(state)
            val pending = pendingNativeSteers(db, state.id)
            var ready = state.copy(nativeSteers = JSONArray(pending).toString())
            if (consumeSteers && pending.isNotEmpty()) {
                var chat = state.nativeRecord
                pending.forEach { chat = NativeResponses.append(chat!!, it, System.currentTimeMillis()) }
                ready = ready.copy(nativeRecord = chat, nativeSteers = "[]", status = "running")
                save(ready) // Archive append and queue removal share this transaction.
            }
            val projected = com.kkgosu.reasoned.model.NativeTeaching.project(
                ready.copy(nativeDisplayIds = nativeIds(db, state.id)))
            save(projected)
            db.setTransactionSuccessful()
            return projected
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
        db.beginTransaction()
        try {
            val ids = db.rawQuery("SELECT id FROM sessions WHERE deleted=0", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            ids.forEach { id ->
                var s = load(id) ?: return@forEach
                if (s.nativeTurn.isNotEmpty()) {
                    val pending = JSONObject(s.nativeTurn)
                    val entry = pending.getJSONObject("entry").put("reasonedStatus", "interrupted")
                    val steps = entry.getJSONArray("steps")
                    if (pending.getBoolean("inFlight")) steps.put(JSONObject()) // Unreported spend is unknown.
                    var chat = requireNotNull(s.nativeRecord)
                    if (entry.optString("reasonedPurpose") != "compaction" &&
                        (pending.getBoolean("inFlight") || pending.getBoolean("responsePending"))) {
                        val now = System.currentTimeMillis()
                        // Unexpected death is retryable; native 'aborted' is an intentional learner stop.
                        val failed = NativeResponses.failure(s.model, s.connection, "", false, "interrupted", now,
                            entry.optJSONObject("reasonedResponses"))
                        chat = NativeResponses.append(chat, failed, now)
                        entry.put("messageId", failed.getString("id"))
                        s = s.copy(error = "interrupted")
                    }
                    if (steps.length() > 0) chat = NativeResponses.appendTurn(chat, entry)
                    s = saveNative(s.copy(nativeRecord = chat, nativeTurn = "", status = "interrupted"))
                } else if (s.status == "running") {
                    s = s.copy(status = "interrupted")
                    save(s)
                }
                // A final native commit may precede the local run-status update at process death.
                val running = db.rawQuery("SELECT id FROM runs WHERE session=? AND status='running'", arrayOf(id)).use { rows ->
                    buildList { while (rows.moveToNext()) add(rows.getString(0)) }
                }
                if (running.isEmpty()) return@forEach
                val turns = s.nativeRecord?.snapshot()?.optJSONArray("turns")?.objects().orEmpty().associateBy { it.getString("id") }
                running.forEach runLoop@{ runId ->
                    val entry = turns[runId] ?: return@runLoop
                    val steps = entry.getJSONArray("steps").objects()
                    run(runId, s, entry.optString("reasonedStatus", "completed"),
                        steps.sumOf { step -> listOf("inputTokens", "cacheReadTokens", "cacheWriteTokens").sumOf { step.optLong(it) } },
                        steps.sumOf { it.optLong("outputTokens") })
                }
            }
            db.execSQL("UPDATE runs SET status='interrupted' WHERE status='running'")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
