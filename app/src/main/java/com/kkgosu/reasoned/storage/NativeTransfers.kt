package com.kkgosu.reasoned.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.kkgosu.reasoned.model.NativeChat
import com.kkgosu.reasoned.model.NativeGraphSelection
import com.kkgosu.reasoned.model.NativeTransferPackage
import com.kkgosu.reasoned.model.RuntimeSession
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun createTransferTables(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE native_transfer_packages(sha TEXT PRIMARY KEY)")
    db.execSQL("CREATE TABLE native_transfer_versions(chat TEXT NOT NULL, sha TEXT NOT NULL, record TEXT NOT NULL, checkpoint TEXT NOT NULL, PRIMARY KEY(chat,sha))")
    db.execSQL("CREATE TABLE native_transfer_graphs(package TEXT PRIMARY KEY REFERENCES native_transfer_packages(sha), nodes TEXT NOT NULL, chains TEXT NOT NULL, format TEXT)")
}

internal data class TransferLesson(val id: String, val title: String, val kind: String)
internal data class TransferPreview(
    val packageSha: String, val revision: String, val lessons: List<TransferLesson>,
    val nodes: Int, val chains: Int, val warnings: List<String>, val blockers: List<String>,
)

private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
private fun lines(raw: String) = raw.splitToSequence('\n').filter { it.isNotBlank() }.toList()
private fun joined(lines: List<String>) = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")

// Avoid CursorWindow's per-row limit for native records and graph snapshots.
private fun SessionStore.text(table: String, column: String, key: String, value: String): String = buildString {
    var start = 1
    while (true) {
        val slice = readableDatabase.rawQuery("SELECT substr($column,?,65536) FROM $table WHERE $key=?",
            arrayOf(start.toString(), value)).use { row -> check(row.moveToFirst()); row.getString(0).orEmpty() }
        append(slice)
        if (slice.codePointCount(0, slice.length) < 65536) break
        start += 65536
    }
}

private data class Graph(val sha: String, val nodes: String, val chains: String, val format: String? = null)
private fun SessionStore.graphs(): List<Graph> = readableDatabase.rawQuery(
    "SELECT package,format IS NOT NULL FROM native_transfer_graphs ORDER BY rowid", null
).use { rows -> buildList {
    while (rows.moveToNext()) {
        val sha = rows.getString(0)
        add(Graph(sha, text("native_transfer_graphs", "nodes", "package", sha),
            text("native_transfer_graphs", "chains", "package", sha),
            if (rows.getInt(1) != 0) text("native_transfer_graphs", "format", "package", sha) else null))
    }
} }

// Compare defining records, not JSON property order; provenance appends never redefine a node.
private fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + canonical(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
private fun graphConflicts(graphs: List<Graph>): List<String> {
    val nodes = linkedMapOf<String, String>()
    val chains = linkedMapOf<String, String>()
    val arguments = linkedMapOf<String, String>()
    val conflicts = linkedSetOf<String>()
    for (graph in graphs) {
        val firstNodes = lines(graph.nodes).map(::JSONObject).distinctBy { it.getString("id") }
        for (node in firstNodes) {
            val id = node.getString("id")
            node.remove("provenance")
            val definition = canonical(node)
            if (id in chains || nodes.putIfAbsent(id, definition).let { it != null && it != definition })
                conflicts += "Shared node conflict: $id"
        }
        for (chain in lines(graph.chains).map(::JSONObject).distinctBy { it.getString("id") }) {
            val id = chain.getString("id")
            val premises = chain.getJSONArray("premises")
            val key = canonical(JSONArray((0 until premises.length()).map { premises.getString(it) }.sorted())) +
                ":" + JSONObject.quote(chain.getString("conclusion"))
            if (id in nodes || chains.putIfAbsent(id, key).let { it != null && it != key } ||
                arguments.putIfAbsent(key, id).let { it != null && it != id })
                conflicts += "Shared chain conflict: $id"
        }
    }
    return conflicts.toList()
}

/** Read-only preview. Sender completeness claims are not installation authority. */
internal fun SessionStore.previewTransfer(bytes: ByteArray): TransferPreview = synchronized(this) {
    val contents = NativeTransferPackage.read(bytes)
    val graph = graphs()
    val blockers = mutableListOf<String>()
    val revision = JSONArray()
    val lessons = contents.chats.map { chat ->
        val row = readableDatabase.rawQuery("SELECT deleted,title,goal,model FROM sessions WHERE id=?", arrayOf(chat.id)).use { r ->
            if (r.moveToFirst()) listOf(r.getString(0), r.getString(1), r.getString(2), r.getString(3)) else null
        }
        val raw = row?.let { text("sessions", "native_record", "id", chat.id) }
        val checkpoint = row?.let { text("sessions", "checkpoint", "id", chat.id) }
        revision.put(JSONArray(listOf(chat.id, row, raw, checkpoint)))
        val kind = when {
            row == null -> "new"
            row[0] != "0" -> "deleted"
            raw.isNullOrEmpty() -> "legacy"
            raw == chat.raw -> "same"
            else -> "conflict"
        }
        if (kind in setOf("deleted", "legacy")) blockers += "Existing lesson cannot be replaced: ${chat.id}"
        if (kind == "conflict" && listOf("knowledge_sources", "knowledge_coverage").any { table ->
                readableDatabase.rawQuery("SELECT 1 FROM $table WHERE session=? LIMIT 1", arrayOf(chat.id)).use { it.moveToFirst() }
            }) blockers += "Lesson has saved graph evidence; replacement is unsupported: ${chat.id}"
        if (checkpoint?.takeIf { it.isNotEmpty() }?.let(::JSONObject)?.let {
                it.optString("status") == "running" || it.has("nativeTurn") } == true)
            blockers += "Lesson has an active or unrecovered turn: ${chat.id}"
        TransferLesson(chat.id, chat.snapshot().optString("title").ifBlank { chat.id }.take(2000), kind)
    }
    graph.forEach { revision.put(JSONArray(listOf(it.sha, it.nodes, it.chains, it.format))) }
    if (graph.sumOf { it.nodes.length.toLong() + it.chains.length } + contents.nodes.length + contents.chains.length > 16 * 1024 * 1024)
        blockers += "Installed native graph size limit"
    blockers += graphConflicts(graph + Graph("incoming", contents.nodes, contents.chains))
    val warnings = contents.report.issues.map { "${it.code}: ${it.chatId} ${it.messageId} ${it.reference}" }
    blockers += contents.report.issues.filter { it.code != "external-provenance" }.map { "Missing dependency or unsupported capability: ${it.code} (${it.chatId})" }
    TransferPreview(digest(bytes), digest(revision.toString().toByteArray(Charsets.UTF_8)), lessons,
        lines(contents.nodes).map { JSONObject(it).getString("id") }.distinct().size,
        lines(contents.chains).map { JSONObject(it).getString("id") }.distinct().size, warnings, blockers.distinct())
}

/** Atomically preserve both versions, install selected native archives and their graph; never infer. */
internal fun SessionStore.installTransfer(bytes: ByteArray, approved: TransferPreview, incoming: Set<String>): List<String> = synchronized(this) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        val now = previewTransfer(bytes)
        check(now == approved && now.blockers.isEmpty()) { "Library changed or transfer blocked; preview again" }
        val conflicts = now.lessons.filter { it.kind == "conflict" }.map { it.id }.toSet()
        require(incoming.all { it in conflicts }) { "Invalid conflict choices" }
        val contents = NativeTransferPackage.read(bytes)
        if (now.lessons.all { it.kind == "same" } && graphs().any {
                it.sha == now.packageSha && it.nodes == joined(lines(contents.nodes).distinct()) &&
                    it.chains == joined(lines(contents.chains).distinct()) }) {
            db.setTransactionSuccessful()
            return@synchronized emptyList()
        }
        db.insertWithOnConflict("native_transfer_packages", null, ContentValues().apply {
            put("sha", now.packageSha)
        }, SQLiteDatabase.CONFLICT_IGNORE).also { check(it != -1L || db.rawQuery(
            "SELECT 1 FROM native_transfer_packages WHERE sha=?", arrayOf(now.packageSha)).use { r -> r.moveToFirst() }) }
        fun archive(chat: NativeChat, checkpoint: String = "") {
            db.insertWithOnConflict("native_transfer_versions", null, ContentValues().apply {
                put("chat", chat.id); put("sha", digest(chat.raw.toByteArray(Charsets.UTF_8)))
                put("record", chat.raw); put("checkpoint", checkpoint)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (checkpoint.isNotEmpty()) db.update("native_transfer_versions", ContentValues().apply {
                put("checkpoint", checkpoint)
            }, "chat=? AND sha=?", arrayOf(chat.id, digest(chat.raw.toByteArray(Charsets.UTF_8))))
        }
        val installed = mutableListOf<String>()
        val selected = contents.chats.filter { chat ->
            now.lessons.first { it.id == chat.id }.kind != "conflict" || chat.id in incoming
        }
        val selection = selected.takeIf { it.isNotEmpty() }?.let {
            NativeGraphSelection.select(it, contents.nodes, contents.chains, contents.graphFormat)
        }
        val retained = conflicts - incoming
        check(selection == null || (selection.nodes + selection.chains).none { raw ->
            val points = JSONObject(raw).getJSONArray("provenance")
            (0 until points.length()).any { points.getJSONObject(it).getString("chatId") in retained }
        }) { "Incoming graph depends on a skipped conflicting lesson; choose incoming or import separately" }
        contents.chats.forEach { chat ->
            val current = load(chat.id)
            current?.nativeRecord?.let { archive(it, current.checkpoint()) }
            archive(chat)
            if (chat in selected && current?.nativeRecord?.raw != chat.raw) {
                // Native history, not legacy evidence. Old local answers/checkpoints survive in the version archive.
                for (table in listOf("answers", "questions", "messages")) db.delete(table, "session=?", arrayOf(chat.id))
                val title = now.lessons.first { it.id == chat.id }.title
                saveNative(RuntimeSession(id = chat.id, goal = title, model = "", connection = "imported", nativeRecord = chat))
                installed += chat.id
            }
        }
        if (selection != null) {
            val prior = graphs().firstOrNull { it.sha == now.packageSha }
            val nodes = joined((lines(prior?.nodes.orEmpty()) + selection.nodes).distinct())
            val chains = joined((lines(prior?.chains.orEmpty()) + selection.chains).distinct())
            val values = ContentValues().apply {
                put("package", now.packageSha); put("nodes", nodes); put("chains", chains); put("format", contents.graphFormat)
            }
            if (db.update("native_transfer_graphs", values, "package=?", arrayOf(now.packageSha)) == 0)
                db.insertOrThrow("native_transfer_graphs", null, values)
        }
        db.setTransactionSuccessful()
        installed
    } finally { db.endTransaction() }
}

/** Native archives only: the recent-session menu is limited to 30 and also includes legacy lessons. */
internal fun SessionStore.transferExportLessons(): List<Pair<String, String>> = synchronized(this) {
    readableDatabase.rawQuery("SELECT id,coalesce(nullif(title,''),goal) FROM sessions WHERE deleted=0 AND native_record IS NOT NULL ORDER BY updated DESC", null).use { rows ->
        buildList { while (rows.moveToNext()) add(rows.getString(0) to rows.getString(1)) }
    }
}

/** Capture a coherent, read-only selection; local checkpoints and account settings never enter ZIP. */
internal fun SessionStore.exportTransfer(ids: List<String>): ByteArray = synchronized(this) {
    require(ids.size in 1..100 && ids.toSet().size == ids.size) { "Select 1–100 distinct native lessons" }
    val db = readableDatabase
    db.beginTransaction()
    try {
        val chats = ids.map { id ->
            val state = load(id) ?: error("Lesson missing or deleted")
            check(state.status != "running" && state.nativeTurn.isEmpty()) { "Lesson has an active or unrecovered turn" }
            state.nativeRecord ?: error("This lesson has no exportable record")
        }
        val graph = graphs()
        val nodes = joined(graph.flatMap { lines(it.nodes) }.distinct())
        val chains = joined(graph.flatMap { lines(it.chains) }.distinct())
        val selection = NativeGraphSelection.select(chats, nodes, chains)
        val assertions = (selection.nodes + selection.chains).toSet()
        val contributing = if (assertions.isEmpty()) graph else graph.filter { g ->
            (lines(g.nodes) + lines(g.chains)).any { it in assertions }
        }
        val formats = contributing.mapNotNull { it.format }.distinct()
        check(formats.size <= 1) { "Selected graphs have different format metadata; export them separately" }
        val bytes = NativeTransferPackage.write(chats, joined(selection.nodes), joined(selection.chains), formats.singleOrNull())
        db.setTransactionSuccessful()
        bytes
    } finally { db.endTransaction() }
}

/** Original native graph ledgers, isolated from legacy Reasoned extraction and practice. */
internal fun SessionStore.importedGraph(): Pair<String, String> = synchronized(this) {
    val graph = graphs()
    joined(graph.flatMap { lines(it.nodes) }.distinct()) to joined(graph.flatMap { lines(it.chains) }.distinct())
}
internal fun SessionStore.transferVersions(): List<Pair<String, String>> = synchronized(this) {
    readableDatabase.rawQuery("SELECT chat,sha FROM native_transfer_versions ORDER BY rowid DESC", null).use { r ->
        buildList { while (r.moveToNext()) add(r.getString(0) to r.getString(1)) }
    }
}
internal fun SessionStore.transferVersion(id: String, sha: String): String = synchronized(this) {
    // Both keys are bound; the text helper's identifiers are internal constants only.
    val raw = text("native_transfer_versions", "record", "sha", sha)
    require(NativeChat.parse(raw).id == id)
    raw
}
