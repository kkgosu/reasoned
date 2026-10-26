package com.kkgosu.learnharness

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.kkgosu.learnharness.LessonDisplayItem.TextDisplayItem
import com.kkgosu.learnharness.LessonDisplayItem.Role
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal data class Passage(val id: Long, val text: String) {
    val view get() = "TEACHER [$id] $text"
    val hash get() = fingerprint(view)
    val tokens get() = (view.length + 3) / 4
}

/** Canonical completed assistant text only. Never extract drafts, answers, tool questions or plan records. */
internal fun RuntimeSession.passages(): List<Passage> {
    proseIds?.let { ids -> return items.filter { it.role == Role.PROSE && it.id in ids && it.text.isNotBlank() }.map { Passage(it.id, it.text) } }
    // Conservative legacy recovery. New checkpoints mark the one rendered response row explicitly,
    // including responses assembled from several completed message items.
    val canonical = JSONArray(protocol).objects().filter { it.optString("type") == "message" && it.optString("role") == "assistant" }
        .map { message -> message.getJSONArray("content").objects().filter { it.optString("type") == "output_text" }.joinToString("") { it.getString("text") } }.toSet()
    return items.filter { it.role == Role.PROSE && it.text in canonical && it.text.isNotBlank() }.map { Passage(it.id, it.text) }
}

internal data class KnowledgeSource(val session: String, val message: Long, val title: String, val text: String)
internal data class KnowledgeNode(val id: String, val name: String, val statement: String, val aliases: List<String>, val birth: String)
internal data class KnowledgeChain(val id: String, val premises: List<String>, val conclusion: String)
internal data class KnowledgeSnapshot(val nodes: List<KnowledgeNode>, val chains: List<KnowledgeChain>, val sources: Map<String, List<KnowledgeSource>>) {
    fun data(): GraphData {
        val births = nodes.map { it.birth }.distinct()
        val clusters = births.map { birth -> sources.values.flatten().firstOrNull { it.session == birth }?.title ?: birth }
        val byId = nodes.associateBy { it.id }
        val propositions = nodes.map { node -> GraphNode(node.id, node.name, node.statement,
            chains.filter { it.conclusion == node.id }.joinToString("\n\n") { chain ->
                chain.premises.joinToString("\n", "Premises used together:\n", "\nTherefore: ${node.statement}") { "• ${byId.getValue(it).statement}" }
            }, births.indexOf(node.birth)) }
        val arguments = chains.map { chain -> GraphNode(chain.id, "∴", "Reasoning chain",
            chain.premises.joinToString("\n", "Premises used together:\n", "\nTherefore: ${byId.getValue(chain.conclusion).statement}") { "• ${byId.getValue(it).statement}" },
            births.indexOf(byId.getValue(chain.conclusion).birth)) }
        return GraphData(propositions + arguments, chains.flatMap { chain -> chain.premises.map { it to chain.id } + (chain.id to chain.conclusion) }, clusters)
    }
    fun nodeJson(node: KnowledgeNode) = JSONObject().put("id", node.id).put("name", node.name).put("statement", node.statement)
        .put("aliases", JSONArray(node.aliases)).put("session", node.birth)
}

internal data class Finding(val id: String, val name: String, val statement: String, val aliases: List<String>, val message: Long)
internal data class FindingChain(val premises: List<String>, val conclusion: String, val message: Long)
internal data class Findings(val propositions: List<Finding>, val chains: List<FindingChain>) {
    companion object {
        fun parse(args: JSONObject, batch: List<Passage>, existing: Set<String>): Findings {
            args.exactKeys(setOf("propositions", "chains"))
            val anchors = batch.map { it.id }.toSet()
            fun anchor(value: JSONObject): Long = value.strictString("messageId", 30).toLongOrNull()?.takeIf { it in anchors }
                ?: throw ProviderFailure("invalid_evidence_anchor")
            val propositions = args.getJSONArray("propositions").objects().map { p ->
                p.exactKeys(setOf("id", "name", "statement", "aliases", "messageId"))
                val aliases = p.getJSONArray("aliases").strings(12, 2000)
                Finding(p.strictString("id", 80), p.strictString("name", 120), p.strictString("statement", 2000), aliases, anchor(p))
            }
            if (propositions.size > 32 || propositions.map { it.id }.distinct().size != propositions.size ||
                propositions.any { !it.id.matches(Regex("p[0-9]{1,4}")) || it.id in existing }) throw ProviderFailure("invalid_findings")
            val ids = existing + propositions.map { it.id }
            val chains = args.getJSONArray("chains").objects().map { c ->
                c.exactKeys(setOf("premises", "conclusion", "messageId"))
                val premises = c.getJSONArray("premises").strings(12, 80)
                val conclusion = c.strictString("conclusion", 80)
                if (premises.isEmpty() || premises.distinct().size != premises.size || conclusion in premises ||
                    (premises + conclusion).any { it !in ids }) throw ProviderFailure("invalid_graph_reference")
                FindingChain(premises, conclusion, anchor(c))
            }
            if (chains.size > 48) throw ProviderFailure("knowledge_limit")
            validateDag(ids, chains.flatMap { c -> c.premises.map { it to c.conclusion } })
            return Findings(propositions, chains)
        }
    }
}

internal fun JSONArray.strings(maxCount: Int, maxLength: Int): List<String> {
    if (length() > maxCount) throw ProviderFailure("knowledge_limit")
    return (0 until length()).map { index ->
        (get(index) as? String)?.takeIf { it.isNotBlank() && it.length <= maxLength } ?: throw ProviderFailure("invalid_findings")
    }
}

internal fun createKnowledgeTables(db: SQLiteDatabase) {
    db.execSQL("CREATE TABLE knowledge_nodes(id TEXT PRIMARY KEY,name TEXT NOT NULL,statement TEXT NOT NULL,aliases TEXT NOT NULL,birth TEXT NOT NULL REFERENCES sessions(id))")
    db.execSQL("CREATE TABLE knowledge_chains(id TEXT PRIMARY KEY,premises TEXT NOT NULL,conclusion TEXT NOT NULL REFERENCES knowledge_nodes(id))")
    db.execSQL("CREATE TABLE knowledge_sources(kind TEXT NOT NULL,entity TEXT NOT NULL,session TEXT NOT NULL,message INTEGER NOT NULL,PRIMARY KEY(kind,entity,session,message),FOREIGN KEY(session,message) REFERENCES messages(session,id))")
    db.execSQL("CREATE TABLE knowledge_coverage(session TEXT NOT NULL,message INTEGER NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(session,message,fingerprint),FOREIGN KEY(session,message) REFERENCES messages(session,id))")
    db.execSQL("CREATE TABLE knowledge_runs(id TEXT PRIMARY KEY,session TEXT NOT NULL REFERENCES sessions(id),stage TEXT NOT NULL,model TEXT NOT NULL,input_tokens INTEGER NOT NULL,output_tokens INTEGER NOT NULL)")
}

internal fun SessionStore.knowledge(): KnowledgeSnapshot = synchronized(this) {
    val nodes = readableDatabase.rawQuery("SELECT id,name,statement,aliases,birth FROM knowledge_nodes ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) add(KnowledgeNode(c.getString(0), c.getString(1), c.getString(2), JSONArray(c.getString(3)).strings(128, 2000), c.getString(4))) }
    }
    val chains = readableDatabase.rawQuery("SELECT id,premises,conclusion FROM knowledge_chains ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) add(KnowledgeChain(c.getString(0), JSONArray(c.getString(1)).strings(12, 80), c.getString(2))) }
    }
    val sources = readableDatabase.rawQuery("SELECT k.entity,k.session,k.message,s.goal,m.text FROM knowledge_sources k JOIN sessions s ON s.id=k.session JOIN messages m ON m.session=k.session AND m.id=k.message ORDER BY k.rowid", null).use { c ->
        val result = linkedMapOf<String, MutableList<KnowledgeSource>>()
        while (c.moveToNext()) result.getOrPut(c.getString(0)) { mutableListOf() }.add(KnowledgeSource(c.getString(1), c.getLong(2), c.getString(3), c.getString(4)))
        result
    }
    KnowledgeSnapshot(nodes, chains, sources)
}

internal fun SessionStore.uncovered(session: RuntimeSession): List<Passage> = synchronized(this) {
    val covered = readableDatabase.rawQuery("SELECT fingerprint FROM knowledge_coverage WHERE session=?", arrayOf(session.id)).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }
    session.passages().filter { it.hash !in covered }
}

internal fun SessionStore.extractionUsage(session: String): Pair<Long, Long> = synchronized(this) {
    readableDatabase.rawQuery("SELECT coalesce(sum(input_tokens),0),coalesce(sum(output_tokens),0) FROM knowledge_runs WHERE session=?", arrayOf(session)).use {
        it.moveToFirst(); it.getLong(0) to it.getLong(1)
    }
}

internal fun SessionStore.recordExtraction(id: String, session: RuntimeSession, stage: String, response: JSONObject) = synchronized(this) {
    val usage = response.optJSONObject("usage")
    writableDatabase.insertOrThrow("knowledge_runs", null, ContentValues().apply {
        put("id", id); put("session", session.id); put("stage", stage); put("model", session.model)
        put("input_tokens", usage?.optLong("input_tokens")?.coerceIn(0, 1_000_000) ?: 0)
        put("output_tokens", usage?.optLong("output_tokens")?.coerceIn(0, 1_000_000) ?: 0)
    })
}

/** One transaction writes propositions, joint-premise arguments, evidence AND coverage. Replay is a no-op. */
internal fun SessionStore.mergeKnowledge(session: String, batch: List<Passage>, findings: Findings, links: Map<String, String>, seen: Set<String>): Pair<Int, Int> = synchronized(this) {
    val db = writableDatabase
    db.beginTransaction()
    try {
        val current = load(session) ?: throw ProviderFailure("session_missing")
        val pending = uncovered(current).map { it.hash }.toSet()
        if (batch.all { it.hash !in pending }) { db.setTransactionSuccessful(); return@synchronized 0 to 0 }
        if (batch.any { it.hash !in pending }) throw ProviderFailure("evidence_changed")
        val anchors = batch.map { it.id }.toSet()
        if (findings.propositions.map { it.id }.distinct().size != findings.propositions.size ||
            findings.propositions.any { it.message !in anchors } || findings.chains.any { it.message !in anchors }) throw ProviderFailure("invalid_evidence_anchor")
        val snapshot = knowledge()
        val existing = snapshot.nodes.associateBy { it.id }
        val locals = findings.propositions.map { it.id }.toSet()
        if (links.keys.any { it !in locals } || links.values.any { it !in existing || it !in seen }) throw ProviderFailure("invalid_identity_link")
        val byStatement = snapshot.nodes.associate { it.statement to it.id }.toMutableMap()
        val resolved = mutableMapOf<String, String>()
        val allIds = existing.keys.toMutableSet()
        var added = 0
        fun source(kind: String, entity: String, message: Long) {
            // Several local findings can resolve to the same claim and source anchor.
            db.execSQL("INSERT OR IGNORE INTO knowledge_sources(kind,entity,session,message) VALUES(?,?,?,?)",
                arrayOf(kind, entity, session, message))
        }
        findings.propositions.forEach { p ->
            val target = links[p.id] ?: byStatement[p.statement] ?: "n_" + fingerprint("$session\u0000${p.message}\u0000${p.statement}").take(32)
            resolved[p.id] = target
            if (allIds.add(target)) {
                db.insertOrThrow("knowledge_nodes", null, ContentValues().apply {
                    put("id", target); put("name", p.name); put("statement", p.statement); put("aliases", JSONArray(p.aliases).toString()); put("birth", session)
                })
                added++
            } else {
                val aliases = db.rawQuery("SELECT aliases,statement FROM knowledge_nodes WHERE id=?", arrayOf(target)).use { c ->
                    check(c.moveToFirst()); (JSONArray(c.getString(0)).strings(128, 2000) + p.aliases + p.statement).filter { it != c.getString(1) }.distinct()
                }
                if (aliases.size > 128) throw ProviderFailure("knowledge_limit")
                db.update("knowledge_nodes", ContentValues().apply { put("aliases", JSONArray(aliases).toString()) }, "id=?", arrayOf(target))
            }
            byStatement.putIfAbsent(p.statement, target)
            source("node", target, p.message)
        }
        if (allIds.size > 2000) throw ProviderFailure("knowledge_limit")
        val merged = findings.chains.map { c -> KnowledgeChain("", c.premises.map { resolved[it] ?: it }.distinct().sorted(), resolved[c.conclusion] ?: c.conclusion) }
        val allChains = snapshot.chains + merged
        if (allChains.size > 4000) throw ProviderFailure("knowledge_limit")
        validateDag(allIds, allChains.flatMap { c -> c.premises.map { it to c.conclusion } }.distinct())
        var arguments = 0
        merged.forEachIndexed { index, c ->
            val id = "arg_" + fingerprint(JSONArray(c.premises).toString() + "\u0000" + c.conclusion).take(32)
            val exists = db.rawQuery("SELECT 1 FROM knowledge_chains WHERE id=?", arrayOf(id)).use { it.moveToFirst() }
            if (!exists) {
                db.insertOrThrow("knowledge_chains", null, ContentValues().apply {
                    put("id", id); put("premises", JSONArray(c.premises).toString()); put("conclusion", c.conclusion)
                }); arguments++
            }
            source("chain", id, findings.chains[index].message)
        }
        batch.forEach { p -> db.insertOrThrow("knowledge_coverage", null, ContentValues().apply {
            put("session", session); put("message", p.id); put("fingerprint", p.hash)
        }) }
        db.setTransactionSuccessful()
        added to arguments
    } finally { db.endTransaction() }
}
